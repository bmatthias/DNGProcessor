package amirz.dngprocessor.pipeline.burst;

import android.opengl.GLES20;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.math.BlockDivider;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.parser.RawFrame;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.BayerProvider;

import static amirz.dngprocessor.util.Constants.BLOCK_HEIGHT;
import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_COLOR_BUFFER_BIT;
import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.GL_RGBA;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glClear;
import static android.opengl.GLES20.glClearColor;
import static android.opengl.GLES20.glReadPixels;
import static android.opengl.GLES30.GL_FLOAT;

/**
 * HDR+/Night-Sight frequency-domain pairwise Wiener merge — the algorithm
 * Google's "Sabre" (GCam M1+) is built on. Port of
 * `align_merge_frequency_domain` from hdr-plus-swift/burstphoto/merge/
 * frequency.swift:30 .
 *
 * <p>Replaces the old spatial robust-merge stage. Output is a
 * 1× merged Bayer texture; the 2× SR resample lives in {@link BurstSrUpsample}.
 *
 * <h3>Algorithm summary</h3>
 * <ol>
 *   <li>Pack Bayer into half-res RGBA (2×2 Bayer quad → 1 RGBA pixel).</li>
 *   <li>Run 4 grid-offset passes (0, T/2 in x, T/2 in y, T/2 in both) so each
 *       output pixel is covered by 4 overlapping tiles (Hann-window partition
 *       of unity at T/2 stride).</li>
 *   <li>Per pass: forward FFT ref → ref_FT; for each alternate k > 0 compute
 *       per-tile mismatch + highlights_norm, forward FFT aligned[k] →
 *       aligned_FT, find best 49-shift sub-pixel refinement, merge per-bin
 *       via Wiener with "mean of two central" channel collapse, accumulate
 *       running total mismatch.</li>
 *   <li>Deconvolute the accumulated FT for sharpness boost, inverse FFT,
 *       tile-border-blend with the reference, overlap-add into the running
 *       1× merged Bayer.</li>
 * </ol>
 */
public class BurstFrequencyMerge extends Stage implements BayerProvider {
    private static final String TAG = "BurstFreqMerge";

    /** FFT tile size in half-res pixels (= 2× in Bayer). Fixed by the radix-4 FFT shaders. */
    public static final int TILE_SIZE_MERGE = 8;
    /** FFT tile size in Bayer pixels (full tile span). */
    public static final int TILE_SIZE_BAYER = 2 * TILE_SIZE_MERGE;
    /**
     * Pass-to-pass overlap-add stride, in Bayer pixels. The Hann window built
     * into the forward FFT is a partition of unity when adjacent tiles are
     * offset by T/2 in half-res space (= T_FFT Bayer pixels = TILE_SIZE_MERGE
     * Bayer pixels). Sub-tile by a full T would leave nulls between tiles.
     * See hdr-plus-swift/burstphoto/merge/frequency.swift:111 .
     */
    public static final int OVERLAP_STRIDE_BAYER = TILE_SIZE_MERGE;

    /** Confidence-blend curve exponent for the per-tile flow texture. */
    public static final float TILE_CONFIDENCE_BLEND_GAMMA = 2.0f;

    // -------------------- Flow-discontinuity penalty S (Wronski Algorithm 6) --------------------
    //
    // Per flow tile we compute the max-min spread of the surrounding 3×3 flow
    // magnitudes; if (max−min)² exceeds {@link #M_TH_SQUARED} we flag the tile
    // as a motion boundary and downweight the alt contribution at that tile
    // in the per-bin Wiener (multiplier = S_LOW). Clean tiles keep S_HIGH = 1.
    //
    // M_TH_SQUARED is in Bayer-pixel² units; 16.0 ≈ 4-px gradient between
    // adjacent flow vectors. S_LOW chosen conservatively (0.15 vs. Wronski's
    // 0.05) because our 5×5 mismatch pool already suppresses single-tile
    // outliers — stacking the two too aggressively visibly suppresses real
    // motion that our flow correctly tracks.

    /** Squared flow-magnitude jump (Bayer pixels²) above which a tile is a motion boundary. */
    public static final float M_TH_SQUARED = 16.0f;
    /** Per-tile Wiener multiplier on the alt-contribution at motion boundaries. */
    public static final float FLOW_S_LOW   = 0.15f;
    /** Per-tile Wiener multiplier on the alt-contribution on clean flow tiles. */
    public static final float FLOW_S_HIGH  = 1.0f;

    /**
     * User-facing "noise reduction" knob ∈ [0, 20]. Higher → stronger denoising.
     * Maps to robustness_norm / read_noise / max_motion_norm per frequency.swift:50-59 .
     * Default 10 corresponds to "medium" denoising in the hdr-plus-swift GUI.
     */
    public static final double NOISE_REDUCTION = 10.0;

    /**
     * Whether to run the per-tile deconvolution sharpness boost. Disabled by
     * default because in our unpadded, no-border-blend pipeline the per-tile
     * boost variation (up to 17 % between adjacent tiles) leaks straight into
     * the 1× merged Bayer as an 8-Bayer-pixel grid. The grid is invisible in
     * the final JPEG (smoothed away by demosaic + tone-map) but is clearly
     * visible in the side-car DNG. See the long comment at step 6 of the
     * frequency-merge loop in {@link #execute}.
     */
    public static final boolean ENABLE_DECONVOLUTION = false;

    // -------------------------------------------------------------------------
    // Sabre-plan improvement flags (11 TODOs). For overexposure / regression
    // bisection: set ENABLE_PLAN_IMPROVEMENTS = false (baseline = pre-plan
    // merge), rebuild, confirm exposure is correct, then enable ONE sub-flag
    // at a time and re-test.
    //
    // Suggested bisection order (set ONE BISECT_* = true at a time):
    //   1. BISECT_ALIGN_FALLBACK
    //   2. BISECT_FOUR_CORNER_FLOW
    //   3. BISECT_ADAPTIVE_NOISE
    //   4. BISECT_COLOR_GUIDE
    //   5. BISECT_DIRECTION_PRESERVE
    //   6. BISECT_MISMATCH_MAXPOOL
    //   7. BISECT_FLOW_DISCONTINUITY_S
    //   8. BISECT_HOT_PIXEL (BurstHotPixel.java)
    //   9. BISECT_NEIGHBOUR_SHIFT_RECHECK (BayerAlignment.java)
    //  10. BISECT_ICA_SUBPIXEL (BayerAlignment.java)
    //  11. BISECT_DOGSON_UPSAMPLE (also needs BISECT_FOUR_CORNER_FLOW)
    // -------------------------------------------------------------------------

    /** Master switch — true enables all plan items below. */
    public static final boolean ENABLE_PLAN_IMPROVEMENTS = false;

    // Production wave-1 (direction-preserve uses min(merged,target) for OLA).
    private static final boolean BISECT_MISMATCH_MAXPOOL = true;
    private static final boolean BISECT_FLOW_DISCONTINUITY_S = true;
    private static final boolean BISECT_ALIGN_FALLBACK = false;   // wave 2
    private static final boolean BISECT_FOUR_CORNER_FLOW = true;
    private static final boolean BISECT_DIRECTION_PRESERVE = true;
    private static final boolean BISECT_COLOR_GUIDE = false;
    private static final boolean BISECT_ADAPTIVE_NOISE = false;
    private static final boolean BISECT_DOGSON_UPSAMPLE = false;

    /** Plan #1: 5×5 max-pool of normalised mismatch before Wiener. */
    public static final boolean ENABLE_MISMATCH_MAXPOOL =
            ENABLE_PLAN_IMPROVEMENTS || BISECT_MISMATCH_MAXPOOL;

    /** Plan #2: Wronski flow-discontinuity S penalty in Wiener. */
    public static final boolean ENABLE_FLOW_DISCONTINUITY_S =
            ENABLE_PLAN_IMPROVEMENTS || BISECT_FLOW_DISCONTINUITY_S;

    /** Plan #3: PhotonCamera aligned vs unaligned per-pixel fallback. */
    public static final boolean ENABLE_ALIGN_FALLBACK =
            ENABLE_PLAN_IMPROVEMENTS || BISECT_ALIGN_FALLBACK;

    /**
     * Plan #4: Hann-weighted 4-corner flow sampling (PhotonCamera merge0).
     * When false, bilinearly interpolate the four corner shifts then sample once
     * (older path, pre-plan).
     */
    public static final boolean ENABLE_FOUR_CORNER_FLOW =
            ENABLE_PLAN_IMPROVEMENTS || BISECT_FOUR_CORNER_FLOW;

    /** Plan #6: direction-preserving diff after IFFT. */
    public static final boolean ENABLE_DIRECTION_PRESERVE =
            ENABLE_PLAN_IMPROVEMENTS || BISECT_DIRECTION_PRESERVE;

    /** Plan #7: Wronski colour-guide image for mismatch. */
    public static final boolean ENABLE_COLOR_GUIDE =
            ENABLE_PLAN_IMPROVEMENTS || BISECT_COLOR_GUIDE;

    /** Plan #8: per-burst adaptive noise fit in Wiener. */
    public static final boolean ENABLE_ADAPTIVE_NOISE =
            ENABLE_PLAN_IMPROVEMENTS || BISECT_ADAPTIVE_NOISE;

    /** Plan #11: Dogson biquadratic flow upsample (requires ENABLE_FOUR_CORNER_FLOW). */
    public static final boolean ENABLE_DOGSON_UPSAMPLE =
            (ENABLE_PLAN_IMPROVEMENTS || BISECT_DOGSON_UPSAMPLE) && ENABLE_FOUR_CORNER_FLOW;

    // -------------------------------------------------------------------------
    // DEBUG FLAGS — for isolating where artefacts in the side-car DNG are
    // introduced. Toggle these one at a time, rebuild, and inspect the
    // resulting _merged_1x.dng / _merged.dng:
    //
    //   ENABLE_FREQ_MERGE = false, ENABLE_SR = false
    //       → DNG = preprocessed reference frame, untouched by merge or SR.
    //         If you still see ghosting/grid here, the bug is in DNG export.
    //
    //   ENABLE_FREQ_MERGE = false, ENABLE_SR = true
    //       → DNG = SR-upsampled reference frame, no merge.
    //         If the 1× looks fine but the 2× shows artefacts, SR is at fault.
    //
    //   ENABLE_FREQ_MERGE = true,  ENABLE_SR = false
    //       → DNG = freq-merge 1× output, no SR. Lets you see the merge
    //         result without SR confounding.
    //
    //   ENABLE_FREQ_MERGE = true,  ENABLE_SR = true  (default — production)
    //       → DNG = SR-upsampled freq-merge output.
    // -------------------------------------------------------------------------

    /**
     * When false, the merged 1× Bayer is replaced by a copy of the (already
     * black-level-subtracted, normalised) reference frame from
     * {@link BurstPreProcess}. The merge loop is skipped entirely.
     */
    public static final boolean ENABLE_FREQ_MERGE = true;

    private final BurstPreProcess mPreProcess;
    private final List<RawFrame> mFrames;
    /** If set, write {@code mMergedTex} to this path at end of {@link #execute}. */
    private String mDebugExport1xPath;

    private Texture mMergedTex;

    public BurstFrequencyMerge(BurstPreProcess preProcess, List<RawFrame> frames) {
        mPreProcess = preProcess;
        mFrames = frames;
    }

    // ---- BayerProvider ----

    @Override public Texture getSensorTex()  { return mMergedTex; }
    @Override public int getInWidth()  {
        return mFrames.isEmpty() ? 0 : mFrames.get(0).sensor.inputWidth;
    }
    @Override public int getInHeight() {
        return mFrames.isEmpty() ? 0 : mFrames.get(0).sensor.inputHeight;
    }
    @Override public int getCfaPattern() {
        return mFrames.isEmpty() ? 0 : mFrames.get(0).sensor.cfa;
    }
    @Override public Texture getGainMapTex() { return null; }

    /** Debug: dump 1× freq-merge Bayer to disk before SR upsample runs. */
    public void setDebugExport1xPath(String path) {
        mDebugExport1xPath = path;
    }

    // ---- Stage ----

    @Override
    public int getShader() {
        // This stage drives many sub-shaders directly inside execute(); we just
        // need to return a valid resource so program creation succeeds. The
        // overlap-add is implemented by burst_freq_rgba_to_bayer.
        return R.raw.burst_freq_rgba_to_bayer;
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();
        ProcessParams process = getProcessParams();
        List<Texture> frameTex = mPreProcess.getSensorTextures();
        BayerAlignment.TileShiftResult flow = mPreProcess.getTileShifts();

        int srcW = mFrames.get(0).sensor.inputWidth;
        int srcH = mFrames.get(0).sensor.inputHeight;
        int N = frameTex.size();

        // -------- DEBUG: bypass the entire merge if disabled --------
        if (!ENABLE_FREQ_MERGE) {
            Log.w(TAG, "=== ENABLE_FREQ_MERGE=false — bypassing merge, "
                    + "mMergedTex = copy of preprocessed reference frame ===");
            mMergedTex = TexturePool.get(srcW, srcH, 1, Texture.Format.Float16);
            converter.useProgram(R.raw.burst_copy_r);
            converter.setTexture("srcTex", frameTex.get(0));
            converter.drawBlocks(mMergedTex);
            if (mDebugExport1xPath != null && !mDebugExport1xPath.isEmpty()) {
                MergedDngExporter.exportBayer(mMergedTex, mFrames.get(0).sensor, mFrames.get(0),
                        1, mDebugExport1xPath);
            }
            return;
        }

        // -------- Noise / robustness parameters (frequency.swift:38-60) --------
        boolean uniformExposure = !process.burstBracketed;
        double exposureCorr1 = 0.0;
        double exposureCorr2 = 0.0;
        for (int k = 0; k < N; k++) {
            double exposureFactor = Math.pow(2.0, mFrames.get(k).relativeEv);
            exposureCorr1 += 0.5 + 0.5 / exposureFactor;
            exposureCorr2 += Math.min(4.0, exposureFactor);
        }
        exposureCorr1 /= N;
        exposureCorr2 /= N;

        double robustnessRev = 0.5 * ((uniformExposure ? 26.5 : 28.5)
                - Math.round(NOISE_REDUCTION));
        float robustnessNorm = (float) (exposureCorr1 / exposureCorr2
                * Math.pow(2.0, -robustnessRev + 7.5));
        float readNoise = (float) Math.pow(Math.pow(2.0, -robustnessRev + 10.0), 1.6);
        float maxMotionNorm = (float) Math.max(1.0, Math.pow(1.3, 11.0 - robustnessRev));

        // --------- CRITICAL: rescale noise constants to normalized [0, 1] signal ---------
        // hdr-plus-swift's `prepare_texture_bayer` produces a FLOAT texture in the
        // ORIGINAL SENSOR UNITS (i.e. signal in [0, whiteLevel - blackLevel],
        // typically [0, ~16384) for 14-bit or [0, ~65535) for 16-bit). The Wiener
        // constants robustnessNorm (~0.6) and readNoise (~7) are calibrated for
        // that scale, so the per-bin denominator
        //   noise_norm = (rms + readNoise) · T² · robustnessNorm  ≈  3·10⁵
        // is the right order of magnitude as d² ≈ noise² ≈ 3·10⁴ for an
        // aligned 16-bit frame, giving Wiener weight ≈ 0.1 for aligned bins
        // and ≈ 1 for misaligned ones.
        //
        // OUR BurstPreProcess normalises the signal to [0, 1] (float16). At
        // that scale rms ≈ 0.1 and d² ≈ 7·10⁻⁶, but the raw metal constants
        // give noise_norm ≈ 273 — so the filter always picks the alternate
        // regardless of alignment quality. That's the SOURCE OF THE GHOSTING:
        // misaligned alternates are blindly averaged in with weight ≈ 1.
        //
        // Fix: divide both readNoise and robustnessNorm by the sensor's
        // effective dynamic range so the denominator matches the squared
        // signal-domain noise level we're comparing it against. Note rms is
        // already at the correct scale because it's measured on the
        // normalised signal directly.
        RawFrame ref = mFrames.get(0);
        double blackMean = 0.0;
        if (ref.sensor.blackLevelPattern != null
                && ref.sensor.blackLevelPattern.length > 0) {
            double s = 0.0;
            for (int b : ref.sensor.blackLevelPattern) s += b;
            blackMean = s / ref.sensor.blackLevelPattern.length;
        }
        float signalScale = Math.max(1.0f,
                (float) (ref.sensor.whiteLevel - blackMean));
        readNoise     /= signalScale;
        robustnessNorm /= signalScale;

        Log.i(TAG, "=== Frequency-domain Wiener Merge START ===");
        Log.i(TAG, String.format(
                "  planImprovements=%s  maxPool=%s  flowS=%s  alignFallback=%s  "
                        + "fourCornerFlow=%s  dirPreserve=%s  colorGuide=%s  "
                        + "adaptiveNoise=%s  dogson=%s",
                ENABLE_PLAN_IMPROVEMENTS, ENABLE_MISMATCH_MAXPOOL,
                ENABLE_FLOW_DISCONTINUITY_S, ENABLE_ALIGN_FALLBACK,
                ENABLE_FOUR_CORNER_FLOW, ENABLE_DIRECTION_PRESERVE,
                ENABLE_COLOR_GUIDE, ENABLE_ADAPTIVE_NOISE, ENABLE_DOGSON_UPSAMPLE));
        Log.i(TAG, String.format("  Frames=%d  src=%dx%d  T=%d (half-res, =%d Bayer)",
                N, srcW, srcH, TILE_SIZE_MERGE, TILE_SIZE_BAYER));
        Log.i(TAG, String.format("  noiseReduction=%.1f  robustnessNorm=%.3f  readNoise=%.3f  "
                + "maxMotionNorm=%.2f  uniformExposure=%s",
                NOISE_REDUCTION, robustnessNorm, readNoise, maxMotionNorm, uniformExposure));

        // -------- Tile grid for the FT --------
        // Use the minimum (n_tiles_x * T) that fits BOTH grid offsets (0 and T/2 in
        // half-res). Lets all 4 passes share the same FT/RGBA dims.
        int halfW = srcW / 2;
        int halfH = srcH / 2;
        int nTilesX = Math.max(1, (halfW - TILE_SIZE_MERGE) / TILE_SIZE_MERGE);
        int nTilesY = Math.max(1, (halfH - TILE_SIZE_MERGE) / TILE_SIZE_MERGE);
        int rgbaW = nTilesX * TILE_SIZE_MERGE;
        int rgbaH = nTilesY * TILE_SIZE_MERGE;
        int ftW = 2 * rgbaW;
        int ftH = rgbaH;

        Log.i(TAG, String.format("  nTiles=%dx%d  rgbaDims=%dx%d  ftDims=%dx%d",
                nTilesX, nTilesY, rgbaW, rgbaH, ftW, ftH));

        // -------- Allocate textures --------
        Texture refRgba    = TexturePool.get(rgbaW, rgbaH, 4, Texture.Format.Float16);
        // PhotonCamera per-pixel aligned/unaligned fallback (merge0.glsl:120-127):
        // pack the alt frame twice — once with per-tile flow applied, once at
        // the same coord with NO flow — then per-pixel select whichever is
        // closer to ref. The selected ("fused") alt is the input to abs_diff,
        // highlights_norm and the forward FFT.
        Texture altRgbaAligned   = TexturePool.get(rgbaW, rgbaH, 4, Texture.Format.Float16);
        Texture altRgbaUnaligned = TexturePool.get(rgbaW, rgbaH, 4, Texture.Format.Float16);
        Texture altRgba          = TexturePool.get(rgbaW, rgbaH, 4, Texture.Format.Float16);
        Texture absDiffTex       = TexturePool.get(rgbaW, rgbaH, 4, Texture.Format.Float16);
        // PhotonCamera direction-preserve: running mean of signed (alt − ref).
        // Ping-pong because we can't read+write the same texture in one pass.
        Texture origDiffA        = TexturePool.get(rgbaW, rgbaH, 4, Texture.Format.Float16);
        Texture origDiffB        = TexturePool.get(rgbaW, rgbaH, 4, Texture.Format.Float16);
        Texture correctedIfftOut = TexturePool.get(rgbaW, rgbaH, 4, Texture.Format.Float16);
        // Wronski colour-guide textures (only allocated when the feature is on).
        Texture refGuide         = ENABLE_COLOR_GUIDE
                ? TexturePool.get(rgbaW, rgbaH, 4, Texture.Format.Float16) : null;
        Texture altGuide         = ENABLE_COLOR_GUIDE
                ? TexturePool.get(rgbaW, rgbaH, 4, Texture.Format.Float16) : null;
        // CFA + inverse-WB derived from the reference frame (constant for the burst).
        int cfaEnum = mFrames.get(0).sensor.cfa;
        float[] wbGain = computeWbGain(mFrames.get(0));
        Texture ifftInter  = TexturePool.get(ftW,   ftH,   4, Texture.Format.Float16);
        Texture ifftOut    = TexturePool.get(rgbaW, rgbaH, 4, Texture.Format.Float16);
        Texture fftInter   = TexturePool.get(ftW,   ftH,   4, Texture.Format.Float16);
        Texture refFt      = TexturePool.get(ftW,   ftH,   4, Texture.Format.Float16);
        Texture altFt      = TexturePool.get(ftW,   ftH,   4, Texture.Format.Float16);
        Texture accumFtA   = TexturePool.get(ftW,   ftH,   4, Texture.Format.Float16);
        Texture accumFtB   = TexturePool.get(ftW,   ftH,   4, Texture.Format.Float16);

        Texture rmsTex            = TexturePool.get(nTilesX, nTilesY, 4, Texture.Format.Float16);
        // Per-tile mean brightness — needed by the adaptive noise fit.
        Texture tileMeanTex       = TexturePool.get(nTilesX, nTilesY, 4, Texture.Format.Float16);
        Texture mismatchTex       = TexturePool.get(nTilesX, nTilesY, 1, Texture.Format.Float16);
        Texture normMismatchTex   = TexturePool.get(nTilesX, nTilesY, 1, Texture.Format.Float16);
        // Wronski Algorithm 9 (local_min): 5×5 max-pool of normalised mismatch
        // (= 5×5 local-min of robustness). Dilates rejection by 2 tiles in each
        // direction so a single corrupted tile cannot leak through the Wiener.
        Texture pooledMismatchTex = TexturePool.get(nTilesX, nTilesY, 1, Texture.Format.Float16);
        Texture totalMismatchTexA = TexturePool.get(nTilesX, nTilesY, 1, Texture.Format.Float16);
        Texture totalMismatchTexB = TexturePool.get(nTilesX, nTilesY, 1, Texture.Format.Float16);
        Texture highlightsNormTex = TexturePool.get(nTilesX, nTilesY, 1, Texture.Format.Float16);
        Texture bestShiftTex      = TexturePool.get(nTilesX, nTilesY, 4, Texture.Format.Float16);
        // Per-merge-tile Wronski flow-S penalty texture. Re-uploaded per alt.
        Texture flowSTex          = TexturePool.get(nTilesX, nTilesY, 1, Texture.Format.Float16);

        // Per-frame Wronski flow-discontinuity S, resampled onto the merge-tile
        // grid. Independent of pass offset (8-Bayer-px pass offset << 128-Bayer
        // flow stride), so computed once per frame and reused across all 4 passes.
        float[][] flowSPerMergeTile = ENABLE_FLOW_DISCONTINUITY_S
                ? precomputeFlowSPerMergeTile(flow, N, nTilesX, nTilesY)
                : null;
        ByteBuffer flowSBuf = ByteBuffer.allocateDirect(nTilesX * nTilesY * 4)
                .order(ByteOrder.nativeOrder());
        FloatBuffer flowSFb = flowSBuf.asFloatBuffer();

        // Per-frame tile-flow texture (re-uploaded each alternate).
        Texture tileFlowTex = new Texture(flow.nTilesX, flow.nTilesY, 4, Texture.Format.Float16,
                null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        ByteBuffer tileFlowBuf = ByteBuffer.allocateDirect(flow.tilesPerFrame() * 4 * 4)
                .order(ByteOrder.nativeOrder());
        FloatBuffer tileFlowFb = tileFlowBuf.asFloatBuffer();

        // Final merged Bayer accumulator (W × H, R16F). Cleared to zero before pass 0.
        mMergedTex = TexturePool.get(srcW, srcH, 1, Texture.Format.Float16);
        mMergedTex.setFrameBuffer();
        glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        // Per-frame median shifts (in 1× source pixels) — used as the low-confidence
        // fallback in the per-pixel flow lookup of burst_freq_bayer_to_rgba.
        float[][] frameMedShifts = computeMedianShifts(flow, N);

        // -------- 4 grid-offset passes --------
        // Offsets step by OVERLAP_STRIDE_BAYER = TILE_SIZE_MERGE Bayer pixels =
        // T/2 in half-res space, so that the Hann-windowed tiles produced by the
        // forward FFT form a partition of unity in overlap-add.
        int[][] gridOffsets = new int[][] {
                {0,                     0},
                {OVERLAP_STRIDE_BAYER,  0},
                {0,                     OVERLAP_STRIDE_BAYER},
                {OVERLAP_STRIDE_BAYER,  OVERLAP_STRIDE_BAYER}
        };

        Texture refFrame = frameTex.get(0);
        BurstAdaptiveNoise adaptiveNoise = null;
        for (int pass = 0; pass < 4; pass++) {
            int offX = gridOffsets[pass][0];
            int offY = gridOffsets[pass][1];

            // 1. Pack reference Bayer → ref RGBA (no flow).
            packBayerToRgba(converter, refFrame, refRgba, srcW, srcH,
                    offX, offY,
                    tileFlowTex, flow, false /*applyFlow*/, null /*median*/,
                    1f /*exposureScale*/);

            // 1'. Compute colour-guide image for ref (no flow).
            if (ENABLE_COLOR_GUIDE) {
                packColorGuide(converter, refFrame, refGuide, srcW, srcH,
                        offX, offY, cfaEnum, wbGain,
                        tileFlowTex, flow, false /*applyFlow*/, null,
                        1f /*exposureScale*/);
            }

            // 2. Forward FFT(ref) → refFt.
            forwardFft(converter, refRgba, fftInter, refFt);

            // 3. Per-tile RMS of refRgba.
            converter.useProgram(R.raw.burst_freq_rms);
            converter.setTexture("refRgbaTex", refRgba);
            converter.seti("tileSize", TILE_SIZE_MERGE);
            converter.drawBlocks(rmsTex);

            // 3'. Per-tile MEAN brightness (feeds the adaptive-noise Wiener
            //     model and the per-tile noise lookup in the merge).
            converter.useProgram(R.raw.burst_freq_tile_mean);
            converter.setTexture("refRgbaTex", refRgba);
            converter.seti("tileSize", TILE_SIZE_MERGE);
            converter.drawBlocks(tileMeanTex);

            // 3''. On pass 0 only, fit the per-burst adaptive noise model from
            //      the (mean, rms²) cloud. The same coefficients are used for
            //      all four passes (the underlying sensor noise behaviour is
            //      independent of the FFT grid offset).
            if (pass == 0 && ENABLE_ADAPTIVE_NOISE && adaptiveNoise == null) {
                adaptiveNoise = BurstAdaptiveNoise.fit(tileMeanTex, rmsTex,
                        nTilesX, nTilesY);
            }

            // 4. Initialize accumulator FT = ref_FT (copy).
            //    Done implicitly: first merge call uses refFt as accumFtTex input;
            //    we use accumFtA = ref+merge(k=1), then ping-pong.
            Texture accumIn = refFt;     // initial input to first merge
            Texture accumOut = accumFtA;

            // Clear total_mismatch accumulator to 0.
            clearSingleChannelTex(totalMismatchTexA);
            Texture totalMmIn = totalMismatchTexA;
            Texture totalMmOut = totalMismatchTexB;

            // Clear running mean signed (alt − ref) accumulator.
            clearSingleChannelTex(origDiffA);
            Texture origDiffIn  = origDiffA;
            Texture origDiffOut = origDiffB;

            // 5. Per alternate frame.
            for (int k = 1; k < N; k++) {
                double exposureFactor = Math.pow(2.0,
                        (mFrames.get(k).relativeEv - mFrames.get(0).relativeEv));
                // Scale alt radiance to reference EV (hdr-plus prepare_texture_bayer).
                float altExposureScale = (float) Math.pow(2.0,
                        mFrames.get(0).relativeEv - mFrames.get(k).relativeEv);

                uploadFlowForFrame(tileFlowTex, tileFlowFb, flow, k);

                if (ENABLE_ALIGN_FALLBACK) {
                    // 5a. Pack aligned[k] with flow + unaligned without flow, then fuse.
                    packBayerToRgba(converter, frameTex.get(k), altRgbaAligned, srcW, srcH,
                            offX, offY, tileFlowTex, flow, true /*applyFlow*/,
                            frameMedShifts[k], altExposureScale);
                    packBayerToRgba(converter, frameTex.get(k), altRgbaUnaligned, srcW, srcH,
                            offX, offY, tileFlowTex, flow, false /*applyFlow*/, null,
                            altExposureScale);
                    converter.useProgram(R.raw.burst_freq_align_fallback);
                    converter.setTexture("refRgbaTex", refRgba);
                    converter.setTexture("alignedAltTex", altRgbaAligned);
                    converter.setTexture("unalignedAltTex", altRgbaUnaligned);
                    converter.drawBlocks(altRgba);
                } else {
                    // Pre-plan path: single flow-aligned pack into altRgba.
                    packBayerToRgba(converter, frameTex.get(k), altRgba, srcW, srcH,
                            offX, offY, tileFlowTex, flow, true /*applyFlow*/,
                            frameMedShifts[k], altExposureScale);
                }

                // 5a''. Compute colour-guide image for the fused alt. We use
                // the fused alt's underlying Bayer (= aligned-flow path) since
                // the guide is computed at the same Bayer-pixel positions; the
                // per-pixel fallback above only affects the per-CFA RGBA used
                // by the FFT, not the robustness signal.
                if (ENABLE_COLOR_GUIDE) {
                    packColorGuide(converter, frameTex.get(k), altGuide,
                            srcW, srcH, offX, offY, cfaEnum, wbGain,
                            tileFlowTex, flow, true /*applyFlow*/, frameMedShifts[k],
                            altExposureScale);
                }

                // 5a'. Upload per-merge-tile Wronski S penalty (or 1.0 = no penalty).
                flowSFb.position(0);
                if (ENABLE_FLOW_DISCONTINUITY_S) {
                    flowSFb.put(flowSPerMergeTile[k]);
                } else {
                    for (int t = 0; t < nTilesX * nTilesY; t++) {
                        flowSFb.put(FLOW_S_HIGH);
                    }
                }
                flowSFb.position(0);
                flowSTex.setPixels(flowSFb);

                // 5b. abs_diff(ref, aligned) — feeds the per-tile mismatch /
                // robustness signal. When the colour-guide path is on, we
                // diff the WB-corrected (R, G_avg, B) guides instead of the
                // per-CFA RGGB textures so chroma motion is a true colour-
                // space distance and not three independent per-channel ones.
                converter.useProgram(R.raw.burst_freq_abs_diff);
                if (ENABLE_COLOR_GUIDE) {
                    converter.setTexture("refRgbaTex", refGuide);
                    converter.setTexture("alignedRgbaTex", altGuide);
                } else {
                    converter.setTexture("refRgbaTex", refRgba);
                    converter.setTexture("alignedRgbaTex", altRgba);
                }
                converter.drawBlocks(absDiffTex);

                // 5c. mismatch from abs_diff + rms.
                converter.useProgram(R.raw.burst_freq_mismatch);
                converter.setTexture("absDiffTex", absDiffTex);
                converter.setTexture("rmsTex", rmsTex);
                converter.seti("tileSize", TILE_SIZE_MERGE);
                converter.setf("exposureFactor", (float) exposureFactor);
                converter.seti("absDiffSize", rgbaW, rgbaH);
                converter.drawBlocks(mismatchTex);

                // 5d. CPU readback mean(mismatch).
                float meanMm = readbackMeanR16(mismatchTex);

                // 5e. normalize_mismatch.
                converter.useProgram(R.raw.burst_freq_normalize_mismatch);
                converter.setTexture("mismatchTex", mismatchTex);
                converter.setf("meanMismatch", meanMm);
                converter.drawBlocks(normMismatchTex);

                Texture mismatchForMerge = normMismatchTex;
                if (ENABLE_MISMATCH_MAXPOOL) {
                    converter.useProgram(R.raw.burst_freq_mismatch_maxpool);
                    converter.setTexture("mismatchTex", normMismatchTex);
                    converter.seti("nTiles", nTilesX, nTilesY);
                    converter.drawBlocks(pooledMismatchTex);
                    mismatchForMerge = pooledMismatchTex;
                }

                // 5f. highlights_norm.
                converter.useProgram(R.raw.burst_freq_highlights_norm);
                converter.setTexture("alignedRgbaTex", altRgba);
                converter.seti("tileSize", TILE_SIZE_MERGE);
                converter.setf("exposureFactor", (float) exposureFactor);
                converter.setf("whiteLevel", -1f);
                converter.setf("blackLevelMean", 0f);
                converter.drawBlocks(highlightsNormTex);

                // 5g. forward FFT(aligned) → altFt.
                forwardFft(converter, altRgba, fftInter, altFt);

                // 5h. best 49-shift per tile.
                converter.useProgram(R.raw.burst_freq_merge_best_shift);
                converter.setTexture("refFtTex", refFt);
                converter.setTexture("alignedFtTex", altFt);
                converter.seti("tileSize", TILE_SIZE_MERGE);
                converter.drawBlocks(bestShiftTex);

                // 5i. per-bin Wiener merge → accumulator.
                converter.useProgram(R.raw.burst_freq_merge);
                converter.setTexture("refFtTex", refFt);
                converter.setTexture("alignedFtTex", altFt);
                converter.setTexture("accumFtTex", accumIn);
                converter.setTexture("rmsTex", rmsTex);
                converter.setTexture("mismatchTex", mismatchForMerge);
                converter.setTexture("highlightsNormTex", highlightsNormTex);
                converter.setTexture("flowSTex", flowSTex);
                converter.setTexture("tileMeanTex", tileMeanTex);
                if (adaptiveNoise != null) {
                    converter.setf("noiseModelA",
                            adaptiveNoise.a[0], adaptiveNoise.a[1],
                            adaptiveNoise.a[2], adaptiveNoise.a[3]);
                    converter.setf("noiseModelB",
                            adaptiveNoise.b[0], adaptiveNoise.b[1],
                            adaptiveNoise.b[2], adaptiveNoise.b[3]);
                    converter.seti("useAdaptiveNoise", 1);
                } else {
                    converter.setf("noiseModelA", 0f, 0f, 0f, 0f);
                    converter.setf("noiseModelB", 0f, 0f, 0f, 0f);
                    converter.seti("useAdaptiveNoise", 0);
                }
                converter.setTexture("bestShiftTex", bestShiftTex);
                converter.setf("robustnessNorm", robustnessNorm);
                converter.setf("readNoise", readNoise);
                converter.setf("maxMotionNorm",
                        (float) (uniformExposure
                                ? maxMotionNorm
                                : Math.min(4.0, exposureFactor) * Math.sqrt(maxMotionNorm)));
                converter.seti("tileSize", TILE_SIZE_MERGE);
                converter.seti("uniformExposure", uniformExposure ? 1 : 0);
                converter.drawBlocks(accumOut);

                // Swap accum ping-pong.
                Texture tmp = accumIn;
                accumIn = accumOut;
                accumOut = (tmp == refFt) ? accumFtB : tmp;

                // 5j. accumulate total mismatch: total += mismatch / N
                //     Use the pooled mismatch so the deconvolution boost sees
                //     the same dilated rejection region as the Wiener.
                accumulateTotalMismatch(converter, mismatchForMerge, totalMmIn, totalMmOut, N);
                Texture tmpM = totalMmIn;
                totalMmIn = totalMmOut;
                totalMmOut = tmpM;

                // 5k. accumulate mean signed (alt − ref) for direction-preserve.
                if (ENABLE_DIRECTION_PRESERVE) {
                    converter.useProgram(R.raw.burst_freq_accumulate_signed_diff);
                    converter.setTexture("refRgbaTex", refRgba);
                    converter.setTexture("alignedAltTex", altRgba);
                    converter.setTexture("accumTex", origDiffIn);
                    // N − 1 = number of alternates contributing to the mean.
                    converter.setf("scale", 1f / (float) Math.max(1, N - 1));
                    converter.drawBlocks(origDiffOut);
                    Texture tmpD = origDiffIn;
                    origDiffIn = origDiffOut;
                    origDiffOut = tmpD;
                }
            }

            // 6. Deconvolute accumIn using totalMmIn (per-tile sharpness boost).
            //
            // The deconvolution multiplies each FT bin by a per-tile scale
            //   (1 + w·cw[dm]) · (1 + w·cw[dn])  ∈ [1.00, 1.17]
            // where w depends on the per-tile mismatch (0 → full boost, ≥0.3 →
            // no boost) and on the per-bin magnitude ratio. So adjacent tiles
            // in the SAME pass can have boosts differing by up to 17 %.
            //
            // In hdr-plus-swift, the resulting tile-boundary discontinuities
            // are then masked by `reduce_artifacts_tile_border` (which we had
            // to disable — see note below) and by the explicit padding+crop.
            // Without those, the per-tile boost variation propagates straight
            // into the 1× merged Bayer as an 8-Bayer-pixel grid that survives
            // into the side-car DNG (linear) but gets smoothed away by the
            // demosaic + bilateral + tone-map stages in the JPEG pipeline.
            //
            // For now: gate this behind {@link #ENABLE_DECONVOLUTION}. Leave
            // OFF until we can either (a) implement metal's padding+crop
            // (proper fix) or (b) replace the per-tile boost with a more
            // boundary-aware operator. Disabling costs only a small global
            // sharpness, which is recovered downstream by the SR upsample +
            // BilateralFilter + MergeDetail stages.
            Texture mergedFt;
            if (ENABLE_DECONVOLUTION) {
                converter.useProgram(R.raw.burst_freq_deconvolute);
                converter.setTexture("accumFtTex", accumIn);
                converter.setTexture("totalMismatchTex", totalMmIn);
                converter.seti("tileSize", TILE_SIZE_MERGE);
                converter.drawBlocks(accumOut);
                mergedFt = accumOut;
            } else {
                mergedFt = accumIn;
            }

            // 7. Inverse FFT(mergedFt) → ifftOut, dividing by N.
            backwardFft(converter, mergedFt, ifftInter, ifftOut, N);

            // 7'. Direction-preserve (clamp + re-rotate) the (merged − ref)
            //     diff against the accumulated mean signed (alt − ref). No-op
            //     when N == 1 (no alternates → no accumulator content).
            Texture overlapAddInput = ifftOut;
            if (ENABLE_DIRECTION_PRESERVE && N > 1) {
                converter.useProgram(R.raw.burst_freq_direction_preserve);
                converter.setTexture("ifftTex", ifftOut);
                converter.setTexture("refRgbaTex", refRgba);
                converter.setTexture("origDiffTex", origDiffIn);
                converter.drawBlocks(correctedIfftOut);
                overlapAddInput = correctedIfftOut;
            }

            // NOTE: hdr-plus-swift calls `reduce_artifacts_tile_border` here to
            // blend the IFFT output with the reference at every tile's outer
            // pixel row/column. That blend breaks the Hann partition-of-unity
            // whenever ref ≠ merged (i.e. for any misalignment > 0). In the
            // Swift pipeline this is hidden because the image is explicitly
            // padded before FFT and cropped afterwards, so the affected border
            // tiles fall in the discarded margin. We don't pad/crop, so the
            // blend would create a visible 8-Bayer-pixel grid across the
            // entire output. Skip it. The Wiener weight already suppresses
            // misalignment-induced ringing.

            // 8. Overlap-add into running 1× merged Bayer.
            converter.useProgram(R.raw.burst_freq_rgba_to_bayer);
            converter.setTexture("rgbaTex", overlapAddInput);
            converter.setTexture("accumBayerTex", mMergedTex);
            converter.seti("gridOffsetBayer", offX, offY);
            converter.seti("halfResSize", rgbaW, rgbaH);
            converter.setf("passWeight", 1.0f);
            // We need to ping-pong the merged Bayer too (can't read+write).
            Texture mergedNext = TexturePool.get(srcW, srcH, 1, Texture.Format.Float16);
            converter.drawBlocks(mergedNext);
            mMergedTex.close();
            mMergedTex = mergedNext;

            Log.d(TAG, String.format("  pass %d/4 done  offset=(%d,%d)", pass + 1, offX, offY));
        }

        // -------- Release scratch textures --------
        refRgba.close();
        altRgbaAligned.close();
        altRgbaUnaligned.close();
        altRgba.close();
        absDiffTex.close();
        origDiffA.close();
        origDiffB.close();
        correctedIfftOut.close();
        if (refGuide != null) refGuide.close();
        if (altGuide != null) altGuide.close();
        ifftInter.close();
        ifftOut.close();
        fftInter.close();
        refFt.close();
        altFt.close();
        accumFtA.close();
        accumFtB.close();
        rmsTex.close();
        tileMeanTex.close();
        mismatchTex.close();
        normMismatchTex.close();
        pooledMismatchTex.close();
        totalMismatchTexA.close();
        totalMismatchTexB.close();
        highlightsNormTex.close();
        bestShiftTex.close();
        flowSTex.close();
        tileFlowTex.close();

        Log.i(TAG, "=== Frequency-domain Wiener Merge DONE  " + srcW + "x" + srcH + " ===");

        if (mDebugExport1xPath != null && !mDebugExport1xPath.isEmpty()) {
            MergedDngExporter.exportBayer(mMergedTex, mFrames.get(0).sensor, mFrames.get(0),
                    1, mDebugExport1xPath);
        }
    }

    /**
     * Per-frame median tile shift (in source pixels), used as the low-confidence
     * fallback in the per-pixel flow lookup.
     */
    private static float[][] computeMedianShifts(BayerAlignment.TileShiftResult flow, int n) {
        int tilesPerFrame = flow.tilesPerFrame();
        float[][] med = new float[n][2];
        float[] tmpDx = new float[tilesPerFrame];
        float[] tmpDy = new float[tilesPerFrame];
        for (int k = 0; k < n; k++) {
            int base = k * tilesPerFrame * 2;
            for (int t = 0; t < tilesPerFrame; t++) {
                tmpDx[t] = flow.shifts[base + t * 2];
                tmpDy[t] = flow.shifts[base + t * 2 + 1];
            }
            java.util.Arrays.sort(tmpDx);
            java.util.Arrays.sort(tmpDy);
            med[k][0] = tmpDx[tilesPerFrame / 2];
            med[k][1] = tmpDy[tilesPerFrame / 2];
        }
        return med;
    }

    /**
     * Per-frame, per-merge-tile flow-discontinuity penalty S (Wronski
     * Algorithm 6, robustness.py:536-611).
     *
     * <p>For each FLOW tile, scan its 3×3 neighbourhood of flow magnitudes and
     * compute {@code (maxMag - minMag)²}. When that exceeds {@link #M_TH_SQUARED}
     * the tile is on a motion boundary → S = {@link #FLOW_S_LOW}; otherwise
     * {@link #FLOW_S_HIGH}. The per-flow-tile values are then resampled
     * (nearest neighbour) onto the merge-tile grid.
     *
     * <p>Returns {@code float[N][mergeTilesPerFrame]}. Frame 0 is filled with
     * {@link #FLOW_S_HIGH} so the merge of the reference against itself is a
     * pure pass-through.
     */
    private static float[][] precomputeFlowSPerMergeTile(
            BayerAlignment.TileShiftResult flow, int n,
            int mergeNTilesX, int mergeNTilesY) {
        int fW = flow.nTilesX;
        int fH = flow.nTilesY;
        int tilesPerFrame = flow.tilesPerFrame();
        int mergeTiles    = mergeNTilesX * mergeNTilesY;
        float[][] out = new float[n][mergeTiles];
        // Frame 0 (reference): identity.
        java.util.Arrays.fill(out[0], FLOW_S_HIGH);

        // Each merge tile spans TILE_SIZE_MERGE half-res = TILE_SIZE_BAYER
        // source pixels. The merge-tile centre in source pixels (for pass 0)
        // is mtx · T_B + T_B/2.
        float mergeCentreStride = TILE_SIZE_BAYER;            // 16 src px
        float mergeCentreOffset = TILE_SIZE_BAYER * 0.5f;     // 8 src px
        float fOriginX = flow.tileOriginSrcX;
        float fOriginY = flow.tileOriginSrcY;
        float fStrideX = flow.tileStrideSrcX;
        float fStrideY = flow.tileStrideSrcY;

        float[] perFlowTileS = new float[tilesPerFrame];
        for (int k = 1; k < n; k++) {
            // 1) Per-flow-tile S from 3×3 max-min on the FLOW MAGNITUDES.
            int base = k * tilesPerFrame * 2;
            for (int ty = 0; ty < fH; ty++) {
                for (int tx = 0; tx < fW; tx++) {
                    float maxMag = -1f;
                    float minMag = Float.POSITIVE_INFINITY;
                    int y0 = Math.max(0, ty - 1);
                    int y1 = Math.min(fH - 1, ty + 1);
                    int x0 = Math.max(0, tx - 1);
                    int x1 = Math.min(fW - 1, tx + 1);
                    for (int ny = y0; ny <= y1; ny++) {
                        for (int nx = x0; nx <= x1; nx++) {
                            int idx = base + (ny * fW + nx) * 2;
                            float dx = flow.shifts[idx];
                            float dy = flow.shifts[idx + 1];
                            float magSq = dx * dx + dy * dy;
                            float mag = (float) Math.sqrt(magSq);
                            if (mag > maxMag) maxMag = mag;
                            if (mag < minMag) minMag = mag;
                        }
                    }
                    float spread = maxMag - minMag;
                    perFlowTileS[ty * fW + tx] =
                            (spread * spread > M_TH_SQUARED) ? FLOW_S_LOW : FLOW_S_HIGH;
                }
            }
            // 2) Resample to merge-tile grid (nearest neighbour).
            float[] mergeS = out[k];
            for (int mty = 0; mty < mergeNTilesY; mty++) {
                float srcY = mty * mergeCentreStride + mergeCentreOffset;
                int fTy = (int) Math.round((srcY - fOriginY) / fStrideY);
                fTy = Math.max(0, Math.min(fH - 1, fTy));
                int rowSrc = fTy * fW;
                int rowDst = mty * mergeNTilesX;
                for (int mtx = 0; mtx < mergeNTilesX; mtx++) {
                    float srcX = mtx * mergeCentreStride + mergeCentreOffset;
                    int fTx = (int) Math.round((srcX - fOriginX) / fStrideX);
                    fTx = Math.max(0, Math.min(fW - 1, fTx));
                    mergeS[rowDst + mtx] = perFlowTileS[rowSrc + fTx];
                }
            }
        }
        return out;
    }

    /**
     * Upload one frame's tile-flow grid (R=dx, G=dy, B=conf) to the texture.
     */
    private static void uploadFlowForFrame(Texture tileFlowTex, FloatBuffer fb,
                                           BayerAlignment.TileShiftResult flow, int k) {
        int tilesPerFrame = flow.tilesPerFrame();
        int shiftBase = k * tilesPerFrame * 2;
        int confBase  = k * tilesPerFrame;
        fb.position(0);
        for (int t = 0; t < tilesPerFrame; t++) {
            fb.put(flow.shifts[shiftBase + t * 2]);
            fb.put(flow.shifts[shiftBase + t * 2 + 1]);
            fb.put(flow.confidences[confBase + t]);
            fb.put(0f);
        }
        fb.position(0);
        tileFlowTex.setPixels(fb);
    }

    /**
     * Bayer → half-res 3-channel "colour guide" image (Wronski
     * {@code compute_guide_image}). Per-channel inverse white-balance brings
     * R, G_avg and B into the same physical scale so chroma motion can be
     * detected as a true colour-space distance.
     */
    private void packColorGuide(GLPrograms converter, Texture bayer, Texture outRgba,
                                 int srcW, int srcH,
                                 int gridOffX, int gridOffY,
                                 int cfaEnum, float[] wbGain,
                                 Texture tileFlowTex,
                                 BayerAlignment.TileShiftResult flow,
                                 boolean applyFlow,
                                 float[] medianShift,
                                 float exposureScale) {
        converter.useProgram(R.raw.burst_freq_color_guide);
        converter.setTexture("bayerTex", bayer);
        converter.seti("inWidth", srcW);
        converter.seti("inHeight", srcH);
        converter.seti("gridOffsetBayer", gridOffX, gridOffY);
        converter.seti("cfaPattern", cfaEnum);
        converter.setf("wbGain", wbGain[0], wbGain[1], wbGain[2]);
        converter.seti("useTileFlow", applyFlow ? 1 : 0);
        converter.setf("tileFlowOriginSrc", flow.tileOriginSrcX, flow.tileOriginSrcY);
        converter.setf("tileFlowStrideSrc", flow.tileStrideSrcX, flow.tileStrideSrcY);
        converter.seti("tileFlowSize", flow.nTilesX, flow.nTilesY);
        converter.setf("tileFlowConfGamma", TILE_CONFIDENCE_BLEND_GAMMA);
        if (medianShift != null) {
            converter.setf("globalShift", medianShift[0], medianShift[1]);
        } else {
            converter.setf("globalShift", 0f, 0f);
        }
        converter.seti("useDogsonUpsample", ENABLE_DOGSON_UPSAMPLE ? 1 : 0);
        converter.seti("useFourCornerFlow", ENABLE_FOUR_CORNER_FLOW ? 1 : 0);
        converter.setf("exposureScale", exposureScale);
        converter.setTexture("tileFlowTex", tileFlowTex);
        converter.drawBlocks(outRgba);
    }

    /**
     * Per-channel inverse-white-balance multipliers derived from the
     * camera's "as-shot neutral" point. Rescaled so the green channel is 1.0
     * — that means the multipliers map the per-channel sensor units to the
     * green channel's scale.
     */
    private static float[] computeWbGain(RawFrame frame) {
        if (frame.sensor.neutralColorPoint == null
                || frame.sensor.neutralColorPoint.length < 3) {
            return new float[] { 1f, 1f, 1f };
        }
        float nR = Math.max(1e-6f, frame.sensor.neutralColorPoint[0]);
        float nG = Math.max(1e-6f, frame.sensor.neutralColorPoint[1]);
        float nB = Math.max(1e-6f, frame.sensor.neutralColorPoint[2]);
        return new float[] {
                nG / nR,    // R gain
                1f,         // G gain (identity by construction)
                nG / nB     // B gain
        };
    }

    /** Bayer → half-res RGBA pack with optional per-tile flow. */
    private void packBayerToRgba(GLPrograms converter, Texture bayer, Texture outRgba,
                                  int srcW, int srcH,
                                  int gridOffX, int gridOffY,
                                  Texture tileFlowTex,
                                  BayerAlignment.TileShiftResult flow,
                                  boolean applyFlow,
                                  float[] medianShift,
                                  float exposureScale) {
        converter.useProgram(R.raw.burst_freq_bayer_to_rgba);
        converter.setTexture("bayerTex", bayer);
        converter.seti("inWidth", srcW);
        converter.seti("inHeight", srcH);
        converter.seti("gridOffsetBayer", gridOffX, gridOffY);
        converter.seti("useTileFlow", applyFlow ? 1 : 0);
        converter.setf("tileFlowOriginSrc", flow.tileOriginSrcX, flow.tileOriginSrcY);
        converter.setf("tileFlowStrideSrc", flow.tileStrideSrcX, flow.tileStrideSrcY);
        converter.seti("tileFlowSize", flow.nTilesX, flow.nTilesY);
        converter.setf("tileFlowConfGamma", TILE_CONFIDENCE_BLEND_GAMMA);
        if (medianShift != null) {
            converter.setf("globalShift", medianShift[0], medianShift[1]);
        } else {
            converter.setf("globalShift", 0f, 0f);
        }
        converter.seti("useDogsonUpsample", ENABLE_DOGSON_UPSAMPLE ? 1 : 0);
        converter.seti("useFourCornerFlow", ENABLE_FOUR_CORNER_FLOW ? 1 : 0);
        converter.setf("exposureScale", exposureScale);
        converter.setTexture("tileFlowTex", tileFlowTex);
        converter.drawBlocks(outRgba);
    }

    private void forwardFft(GLPrograms converter, Texture inputRgba,
                            Texture intermediate, Texture output) {
        converter.useProgram(R.raw.burst_freq_forward_fft_y);
        converter.setTexture("inputTex", inputRgba);
        converter.seti("tileSize", TILE_SIZE_MERGE);
        converter.drawBlocks(intermediate);

        converter.useProgram(R.raw.burst_freq_forward_fft_x);
        converter.setTexture("intermediateTex", intermediate);
        converter.seti("tileSize", TILE_SIZE_MERGE);
        converter.drawBlocks(output);
    }

    private void backwardFft(GLPrograms converter, Texture inputFt,
                              Texture intermediate, Texture output, int nTextures) {
        converter.useProgram(R.raw.burst_freq_backward_fft_y);
        converter.setTexture("inputFtTex", inputFt);
        converter.seti("tileSize", TILE_SIZE_MERGE);
        converter.drawBlocks(intermediate);

        converter.useProgram(R.raw.burst_freq_backward_fft_x);
        converter.setTexture("intermediateTex", intermediate);
        converter.seti("tileSize", TILE_SIZE_MERGE);
        converter.seti("nTextures", nTextures);
        converter.drawBlocks(output);
    }

    /** Accumulate `mismatch / nTextures` into `total`: total_out = total_in + mm / nTextures. */
    private void accumulateTotalMismatch(GLPrograms converter, Texture mm,
                                          Texture totalIn, Texture totalOut, int nTextures) {
        converter.useProgram(R.raw.burst_freq_accumulate_mismatch);
        converter.setTexture("addTex", mm);
        converter.setTexture("accumTex", totalIn);
        converter.setf("scale", 1f / (float) nTextures);
        converter.drawBlocks(totalOut);
    }

    private void clearSingleChannelTex(Texture tex) {
        tex.setFrameBuffer();
        glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** Read back a single-channel R16F texture, compute mean. */
    private float readbackMeanR16(Texture tex) {
        int w = tex.getWidth();
        int h = tex.getHeight();
        ByteBuffer buf = ByteBuffer.allocateDirect(w * BLOCK_HEIGHT * 4 * 4)
                .order(ByteOrder.nativeOrder());
        FloatBuffer fb = buf.asFloatBuffer();

        tex.setFrameBuffer();
        double sum = 0.0;
        int count = 0;
        BlockDivider divider = new BlockDivider(h, BLOCK_HEIGHT);
        int[] row = new int[2];
        while (divider.nextBlock(row)) {
            int y = row[0], bh = row[1];
            fb.position(0);
            glReadPixels(0, y, w, bh, GL_RGBA, GL_FLOAT, fb);
            fb.rewind();
            for (int i = 0; i < w * bh; i++) {
                sum += fb.get(i * 4);  // .r component
                count++;
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return (count > 0) ? (float) (sum / count) : 0f;
    }
}
