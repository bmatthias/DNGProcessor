package amirz.dngprocessor.pipeline.burst;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.math.BlockDivider;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.GL_RGBA;
import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES30.GL_FLOAT;
import static android.opengl.GLES20.glReadPixels;
import static amirz.dngprocessor.util.Constants.BLOCK_HEIGHT;

/**
 * Computes a <b>dense per-tile sub-pixel alignment field</b> for burst SR/HDR,
 * using <b>hierarchical (coarse-to-fine) Gaussian pyramid block-matching</b>
 * after Google HDR+ (Hasinoff et al. 2016, hdr-plus-swift) and Wronski et al.
 * 2019 (Handheld Multi-Frame Super-Resolution).
 *
 * <h3>Why a dense grid?</h3>
 * A 5×5 grid can only express a global affine; rotation, parallax, perspective
 * and local motion all need a flow field with hundreds to thousands of control
 * points to warp differently across the image. HDR+ Swift uses tiles of size
 * 16/32/64 with 50&nbsp;% overlap → typically 49 k–3 k tiles for our sensor
 * sizes. We use {@value #TILE_SIZE_LUMA}-luma-pixel tiles with no overlap →
 * a {@code (luma_w/TILE_STRIDE_LUMA) × (luma_h/TILE_STRIDE_LUMA)} grid ≈ 32×24
 * for a 4096×3072 sensor.
 *
 * <h3>Algorithm (per non-reference frame)</h3>
 * <ol>
 *   <li>GPU shader extracts a ÷4 downsampled green / luma image.</li>
 *   <li>CPU builds a {@value #NUM_LEVELS}-level Gaussian pyramid: level 0 = ÷4
 *       input, each subsequent level = ÷2 blur+decimate. The coarsest level
 *       sees the scene at ÷32 of the sensor for robust large-motion init.</li>
 *   <li>For each tile in the dense output grid, alignment proceeds
 *       coarse→fine. At each level a small SAD search refines the upsampled
 *       estimate from the previous level (±2 finest, ±4 coarser).</li>
 *   <li>At the finest level the integer best-match is sub-pixel-refined by
 *       parabolic peak fitting and confidence is computed from block texture
 *       and SAD-peak sharpness.</li>
 *   <li>Shifts are scaled to full-resolution sensor pixels (×4) and negated
 *       so the accumulation shader can directly add them.</li>
 * </ol>
 *
 * <h3>Why hierarchical?</h3>
 * A single-level ±16-px SAD has poor signal-to-noise on low-texture tiles
 * because the SAD landscape is nearly flat over a huge candidate set. Each
 * pyramid level performs only a small ±2..4-px search, which is much sharper:
 * the coarse levels constrain global motion robustly, the fine level refines
 * locally. Total max motion handled here is ±2 + ±4·2 + ±4·4 + ±4·8 = ±58
 * luma px ≈ ±232 sensor px (vs. ±64 sensor px previously) while each level's
 * SAD is well-conditioned.
 *
 * <h3>Output layout</h3>
 * {@link TileShiftResult#shifts} is a flat float array indexed by
 * {@code frame, tileY, tileX, channel(0=dx,1=dy)}. The grid dimensions
 * ({@link TileShiftResult#nTilesX} / {@link TileShiftResult#nTilesY}) and
 * physical layout ({@link TileShiftResult#tileOriginSrcX},
 * {@link TileShiftResult#tileStrideSrcX}) are all carried in the result so
 * the consumer can upload it as a texture and the shader can map a source-
 * pixel coordinate to a tile-grid coordinate without referencing constants.
 */
public class BayerAlignment {
    private static final String TAG = "BayerAlignment";

    // -------------------- Output-grid configuration --------------------

    /**
     * Output tile size in luma pixels (= ÷4 sensor pixels). Each tile produces
     * one alignment vector. With {@link #TILE_STRIDE_LUMA}=={@code TILE_SIZE_LUMA}
     * the grid covers the image with no overlap.
     */
    public static final int TILE_SIZE_LUMA = 32;

    /** Output tile stride in luma pixels. */
    public static final int TILE_STRIDE_LUMA = 32;

    // -------------------- Pyramid configuration --------------------

    /** Number of pyramid levels. Level 0 = ÷4 sensor (luma input), level N-1 = coarsest. */
    private static final int NUM_LEVELS = 4;

    /**
     * SAD search radius (in pixels of THAT level) at each pyramid level.
     * Index 0 = finest. Total cumulative search range at level 0:
     *   r[0] + 2·r[1] + 4·r[2] + 8·r[3] = 2 + 8 + 16 + 32 = 58 luma px ≈ 232 sensor px.
     */
    private static final int[] SEARCH_PER_LEVEL = {2, 4, 4, 4};

    /**
     * Block (template) size at each pyramid level, in that level's pixels.
     * Finest level uses a bigger block (more samples → more robust sub-pixel
     * refinement). Coarser levels use 16-px blocks which cover ever-larger
     * source areas, giving robust global motion estimates.
     */
    private static final int[] BLOCK_SIZE_PER_LEVEL = {32, 16, 16, 16};

    // -------------------- Confidence configuration --------------------

    /**
     * Minimum block variance (on normalised luma in [0,1]) below which a tile
     * is considered "flat / featureless" and its shift is unreliable.
     * Empirical: a uniform area has var ≈ noise² ≈ 1e-5, a textured area ≈ 1e-3+.
     */
    private static final float MIN_BLOCK_VARIANCE = 5e-5f;

    /**
     * Minimum SAD peak sharpness (1 − bestSAD / meanSAD) below which the SAD
     * landscape is too flat for the best-matching shift to be trusted.
     * 0.0 = no peak (best == mean); 1.0 = perfect peak (best == 0).
     */
    private static final float MIN_PEAK_SHARPNESS = 0.10f;

    /**
     * Radius (in tiles) of the confidence-weighted Gaussian smoothing applied
     * to the dense shift grid after pyramidal SAD. 0 = no smoothing (raw
     * per-tile shifts uploaded → maximum responsiveness to real local motion
     * but also maximum visible warping from per-tile SAD/parabolic noise on
     * static scenes). 1 = 3×3 neighbourhood, 2 = 5×5, …
     *
     * <p>Replaces HDR+'s {@code correct_upsampling_error} step (which picks
     * the lowest-SAD of 3 candidate alignments per tile to enforce smoothness):
     * a single confidence-weighted pass over the final grid is much cheaper
     * and removes the per-tile sub-pixel noise that otherwise warps flat
     * regions visibly.
     */
    private static final int SHIFT_SMOOTH_RADIUS = 1;

    /**
     * Whether to upgrade the finest-level parabolic sub-pixel fit to Wronski's
     * inverse-compositional ICA refinement (Algorithm 3, ICA.py). ICA gives
     * true sub-pixel accuracy at the cost of ~3 Gauss-Newton iterations per
     * tile (each ≈ a 32×32 bilinear sample over the tile). Mostly redundant
     * with the 49-shift Fourier sub-pixel search in burst_freq_merge.glsl —
     * the freq-merge can refine the residual on its own — but a sharper
     * input alignment frees up that ±0.5-px refinement budget for actual
     * motion rather than for alignment cleanup.
     */
    private static final boolean BISECT_NEIGHBOUR_SHIFT_RECHECK = true;
    private static final boolean BISECT_ICA_SUBPIXEL = true;

    /** Plan #9: neighbour-shift SAD recheck after pyramidal search. */
    public static final boolean ENABLE_NEIGHBOUR_SHIFT_RECHECK =
            BurstFrequencyMerge.ENABLE_PLAN_IMPROVEMENTS || BISECT_NEIGHBOUR_SHIFT_RECHECK;

    /** Plan #10: ICA sub-pixel refinement at finest pyramid level. */
    public static final boolean ENABLE_ICA_SUBPIXEL =
            BurstFrequencyMerge.ENABLE_PLAN_IMPROVEMENTS || BISECT_ICA_SUBPIXEL;

    /** Number of ICA refinement iterations per tile. 2–3 is usual. */
    private static final int ICA_ITERATIONS = 3;

    /** Convergence threshold on |delta|² between ICA iterations. */
    private static final float ICA_CONVERGENCE_SQ = 1e-4f;

    /** Reject ICA result if it has drifted more than this from the SAD prior (pixels). */
    private static final float ICA_MAX_DRIFT = 2.0f;

    /**
     * σ (in tiles) for the Gaussian weights inside the smoothing kernel.
     * For a 3×3 box (radius 1), σ ≈ 0.7 puts ~0.13 weight at neighbours and
     * the rest at the centre — strong enough to suppress noise, weak enough
     * to keep real perspective gradients alive.
     */
    private static final float SHIFT_SMOOTH_SIGMA = 0.7f;

    /**
     * Distance (in tiles) from the image edge over which tile confidence is
     * linearly ramped down to 0. Edge tiles have a SAD search range clipped
     * by the frame boundary, so the "best" match they report is the best
     * <i>in range</i>, not the true optimum. Their reported shifts are
     * therefore noisier than interior tiles.
     *
     * <p>By lowering their confidence we make the shader fall back to the
     * global median shift in those tiles (via {@code mix(median, measured,
     * conf^γ)} in {@code sampleLocalShift}). The median is much more robust
     * for border regions than a clipped per-tile SAD.
     *
     * <p>Set to 0 to disable the falloff entirely (legacy behaviour).
     */
    private static final int EDGE_CONFIDENCE_FALLOFF_TILES = 2;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Result of {@link #computeTileShifts}: a per-frame, per-tile shift +
     * confidence grid, plus the physical tile-grid layout so the consumer can
     * map any source-pixel coordinate to a tile-grid coordinate without
     * referencing alignment-internal constants.
     */
    public static class TileShiftResult {
        /**
         * Flat float[n × {@link #nTilesY} × {@link #nTilesX} × 2] in
         * full-resolution sensor pixels, NEGATED so the accumulation shader
         * can do {@code src_x_shifted = src_x - shift.x}. Frame 0 is all zero.
         */
        public final float[] shifts;
        /** Flat float[n × {@link #nTilesY} × {@link #nTilesX}] in [0, 1]; frame 0 is all 1. */
        public final float[] confidences;
        public final int nTilesX;
        public final int nTilesY;
        /** Source-pixel X coord of tile (0, 0) centre. */
        public final float tileOriginSrcX;
        /** Source-pixel Y coord of tile (0, 0) centre. */
        public final float tileOriginSrcY;
        /** Source-pixel stride between adjacent tile centres in X. */
        public final float tileStrideSrcX;
        /** Source-pixel stride between adjacent tile centres in Y. */
        public final float tileStrideSrcY;

        public TileShiftResult(float[] shifts, float[] confidences,
                               int nTilesX, int nTilesY,
                               float tileOriginSrcX, float tileOriginSrcY,
                               float tileStrideSrcX, float tileStrideSrcY) {
            this.shifts = shifts;
            this.confidences = confidences;
            this.nTilesX = nTilesX;
            this.nTilesY = nTilesY;
            this.tileOriginSrcX = tileOriginSrcX;
            this.tileOriginSrcY = tileOriginSrcY;
            this.tileStrideSrcX = tileStrideSrcX;
            this.tileStrideSrcY = tileStrideSrcY;
        }

        /** Number of tiles per frame. */
        public int tilesPerFrame() {
            return nTilesX * nTilesY;
        }
    }

    /**
     * Compute per-frame, per-tile shifts relative to frame 0.
     *
     * @param converter   active {@link GLPrograms} instance
     * @param frameTex    preprocessed sensor textures (one per frame)
     * @param fullW       full-resolution sensor width
     * @param fullH       full-resolution sensor height
     * @param isLinearRaw true if textures are 4-channel linear RGB, false if 1-channel Bayer
     */
    public static TileShiftResult computeTileShifts(GLPrograms converter,
                                                    List<Texture> frameTex,
                                                    int fullW, int fullH,
                                                    boolean isLinearRaw) {
        return computeTileShifts(converter, frameTex, fullW, fullH, isLinearRaw, null);
    }

    /**
     * Same as {@link #computeTileShifts(GLPrograms, List, int, int, boolean)} but
     * applies a per-frame exposure-equalisation scale to the green-channel extract
     * before block matching. Used by the bracket-burst path so that SAD compares
     * pixels in the same EV space across exposures (hdr-plus-swift
     * {@code prepare_texture} behaviour).
     *
     * @param frameScales per-frame multiplier ({@code 1 / 2^relativeEv}); pass
     *                    {@code null} or an array of all-1.0 for uniform bursts.
     */
    public static TileShiftResult computeTileShifts(GLPrograms converter,
                                                    List<Texture> frameTex,
                                                    int fullW, int fullH,
                                                    boolean isLinearRaw,
                                                    float[] frameScales) {
        int n = frameTex.size();

        int dw = Math.max(1, fullW / 4);
        int dh = Math.max(1, fullH / 4);

        // --------- Derive dense tile-grid layout from luma dimensions ---------
        // Grid: as many tiles as fit at the given stride, starting at half-tile
        // offset (so the first tile centre is at TILE_SIZE_LUMA/2).
        //
        //   nTilesX = max(1, floor((dw - tileSize) / stride) + 1)
        //
        // In luma coords: tile centre (gx, gy) = (tileSize/2 + gx·stride,
        //                                          tileSize/2 + gy·stride).
        // In sensor coords: × 4.
        int nTilesX = Math.max(1, (dw  - TILE_SIZE_LUMA) / TILE_STRIDE_LUMA + 1);
        int nTilesY = Math.max(1, (dh  - TILE_SIZE_LUMA) / TILE_STRIDE_LUMA + 1);
        float tileOriginSrcX = (TILE_SIZE_LUMA * 0.5f) * 4f;
        float tileOriginSrcY = (TILE_SIZE_LUMA * 0.5f) * 4f;
        float tileStrideSrcX = TILE_STRIDE_LUMA * 4f;
        float tileStrideSrcY = TILE_STRIDE_LUMA * 4f;

        int tilesPerFrame = nTilesX * nTilesY;
        float[] shifts      = new float[n * tilesPerFrame * 2]; // frame 0 stays all-zero
        float[] confidences = new float[n * tilesPerFrame];

        // Frame 0 is the reference: confidence = 1.0 everywhere.
        for (int t = 0; t < tilesPerFrame; t++) {
            confidences[t] = 1f;
        }

        // Build a Gaussian pyramid per frame ONCE up-front.
        // Per-frame exposure scale equalises bracket frames to the ref frame's EV
        // before block matching — without this, SAD on a +2 EV comparison frame
        // is dominated by the EV gap rather than scene structure.
        float[][] luma = new float[n][];
        for (int k = 0; k < n; k++) {
            float scale = (frameScales != null && k < frameScales.length) ? frameScales[k] : 1f;
            luma[k] = extractGreen(converter, frameTex.get(k), dw, dh, isLinearRaw, scale);
        }
        float[][][] pyr = new float[n][][];
        int[] levelW = new int[NUM_LEVELS];
        int[] levelH = new int[NUM_LEVELS];
        for (int k = 0; k < n; k++) {
            pyr[k] = buildPyramid(luma[k], dw, dh, NUM_LEVELS, levelW, levelH);
        }

        long t0 = System.nanoTime();
        for (int k = 1; k < n; k++) {
            float[] tileGrid = pyramidalSearchGrid(pyr[0], pyr[k], levelW, levelH,
                    nTilesX, nTilesY);
            int shiftBase = k * tilesPerFrame * 2;
            int confBase  = k * tilesPerFrame;
            for (int t = 0; t < tilesPerFrame; t++) {
                // Negate: shift stored as "subtract this from output coord to reach test source"
                shifts[shiftBase + t * 2]     = -tileGrid[t * 3]     * 4.0f;
                shifts[shiftBase + t * 2 + 1] = -tileGrid[t * 3 + 1] * 4.0f;
                confidences[confBase + t]     =  tileGrid[t * 3 + 2];
            }
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        Log.i(TAG, "Pyramidal alignment: " + nTilesX + "×" + nTilesY
                + " grid (" + tilesPerFrame + " tiles), "
                + (n - 1) + " non-ref frames in " + elapsedMs + "ms");

        return new TileShiftResult(shifts, confidences,
                nTilesX, nTilesY,
                tileOriginSrcX, tileOriginSrcY,
                tileStrideSrcX, tileStrideSrcY);
    }

    // -----------------------------------------------------------------------
    // GPU helpers
    // -----------------------------------------------------------------------

    private static float[] extractGreen(GLPrograms converter, Texture src,
                                        int dw, int dh, boolean isLinearRaw,
                                        float frameScale) {
        Texture greenTex = new Texture(dw, dh, 1, Texture.Format.Float16, null,
                GL_NEAREST, GL_CLAMP_TO_EDGE);
        converter.useProgram(R.raw.burst_extract_green);
        converter.setTexture("rawBuffer", src);
        converter.seti("isLinearRaw", isLinearRaw ? 1 : 0);
        converter.setf("frameScale", frameScale);
        converter.drawBlocks(greenTex);

        float[] pixels = downloadR16F(greenTex, dw, dh);
        greenTex.close();
        return pixels;
    }

    private static float[] downloadR16F(Texture tex, int w, int h) {
        float[] out = new float[w * h];
        ByteBuffer buf = ByteBuffer.allocateDirect(w * BLOCK_HEIGHT * 4 * 4)
                .order(ByteOrder.nativeOrder());
        FloatBuffer fb = buf.asFloatBuffer();

        tex.setFrameBuffer();
        BlockDivider divider = new BlockDivider(h, BLOCK_HEIGHT);
        int[] row = new int[2];
        while (divider.nextBlock(row)) {
            int y = row[0], bh = row[1];
            fb.position(0);
            glReadPixels(0, y, w, bh, GL_RGBA, GL_FLOAT, fb);
            fb.rewind();
            for (int i = 0; i < w * bh; i++) {
                out[y * w + i] = fb.get(i * 4); // .r component
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return out;
    }

    // -----------------------------------------------------------------------
    // Pyramid construction
    // -----------------------------------------------------------------------

    /**
     * Build a {@code numLevels}-deep Gaussian pyramid. Level 0 is the input
     * (referenced, not copied). Each subsequent level is half the size,
     * obtained by blurring with a separable 5-tap [1,4,6,4,1]/16 kernel and
     * decimating by 2 (HDR+ convention).
     */
    private static float[][] buildPyramid(float[] luma, int w, int h, int numLevels,
                                          int[] outLevelW, int[] outLevelH) {
        float[][] pyr = new float[numLevels][];
        pyr[0] = luma;
        outLevelW[0] = w;
        outLevelH[0] = h;
        int cw = w, ch = h;
        for (int l = 1; l < numLevels; l++) {
            int nw = Math.max(1, cw / 2);
            int nh = Math.max(1, ch / 2);
            pyr[l] = gaussianDownsample2x(pyr[l - 1], cw, ch, nw, nh);
            outLevelW[l] = nw;
            outLevelH[l] = nh;
            cw = nw;
            ch = nh;
        }
        return pyr;
    }

    /**
     * Separable Gaussian blur (5-tap [1,4,6,4,1]/16) followed by 2× decimation
     * (drop every other pixel). Border pixels use replication.
     */
    private static float[] gaussianDownsample2x(float[] src, int sw, int sh,
                                                int dw, int dh) {
        float[] tmp = new float[sw * sh];
        for (int y = 0; y < sh; y++) {
            int row = y * sw;
            for (int x = 0; x < sw; x++) {
                int xm2 = clamp(x - 2, 0, sw - 1);
                int xm1 = clamp(x - 1, 0, sw - 1);
                int xp1 = clamp(x + 1, 0, sw - 1);
                int xp2 = clamp(x + 2, 0, sw - 1);
                tmp[row + x] = (src[row + xm2] + 4f * src[row + xm1]
                              + 6f * src[row + x]
                              + 4f * src[row + xp1] + src[row + xp2]) * (1f / 16f);
            }
        }
        float[] out = new float[dw * dh];
        for (int dy = 0; dy < dh; dy++) {
            int sy  = dy * 2;
            int ym2 = clamp(sy - 2, 0, sh - 1);
            int ym1 = clamp(sy - 1, 0, sh - 1);
            int y0  = sy;
            int yp1 = clamp(sy + 1, 0, sh - 1);
            int yp2 = clamp(sy + 2, 0, sh - 1);
            int rm2 = ym2 * sw, rm1 = ym1 * sw, r0 = y0 * sw,
                rp1 = yp1 * sw, rp2 = yp2 * sw;
            int dr = dy * dw;
            for (int dx = 0; dx < dw; dx++) {
                int sx = dx * 2;
                if (sx >= sw) sx = sw - 1;
                out[dr + dx] = (tmp[rm2 + sx] + 4f * tmp[rm1 + sx]
                              + 6f * tmp[r0  + sx]
                              + 4f * tmp[rp1 + sx] + tmp[rp2 + sx]) * (1f / 16f);
            }
        }
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // -----------------------------------------------------------------------
    // Hierarchical SAD search
    // -----------------------------------------------------------------------

    /**
     * Run hierarchical block-matching for an {@code nTilesY × nTilesX} grid.
     *
     * @return float[nTilesY × nTilesX × 3] in level-0 (÷4 sensor) pixels:
     *         per tile (dx, dy, confidence) where confidence ∈ [0, 1].
     */
    private static float[] pyramidalSearchGrid(float[][] refPyr, float[][] testPyr,
                                               int[] levelW, int[] levelH,
                                               int nTilesX, int nTilesY) {
        int tilesPerFrame = nTilesX * nTilesY;
        float[] result = new float[tilesPerFrame * 3];

        int lowConfCount = 0;
        float minDx = Float.MAX_VALUE, maxDx = -Float.MAX_VALUE;
        float minDy = Float.MAX_VALUE, maxDy = -Float.MAX_VALUE;
        double sumDx = 0, sumDy = 0;

        int edgeFalloffApplied = 0;
        for (int gy = 0; gy < nTilesY; gy++) {
            for (int gx = 0; gx < nTilesX; gx++) {
                int idx = gy * nTilesX + gx;

                // Tile centre in LEVEL-0 (luma) pixels.
                int cx0 = TILE_SIZE_LUMA / 2 + gx * TILE_STRIDE_LUMA;
                int cy0 = TILE_SIZE_LUMA / 2 + gy * TILE_STRIDE_LUMA;

                float[] s = pyramidalTileShift(refPyr, testPyr, levelW, levelH, cx0, cy0);

                // Edge-tile confidence falloff: tiles within
                // EDGE_CONFIDENCE_FALLOFF_TILES of any image boundary have a
                // clipped SAD search and therefore unreliable shifts. Ramp
                // their confidence linearly down to 0 at the very border.
                float conf = s[2];
                if (EDGE_CONFIDENCE_FALLOFF_TILES > 0) {
                    int dxEdge = Math.min(gx, nTilesX - 1 - gx);
                    int dyEdge = Math.min(gy, nTilesY - 1 - gy);
                    int edgeDist = Math.min(dxEdge, dyEdge);
                    if (edgeDist < EDGE_CONFIDENCE_FALLOFF_TILES) {
                        float edgeFactor = (float) edgeDist
                                / EDGE_CONFIDENCE_FALLOFF_TILES;
                        conf *= edgeFactor;
                        edgeFalloffApplied++;
                    }
                }

                result[idx * 3]     = s[0];
                result[idx * 3 + 1] = s[1];
                result[idx * 3 + 2] = conf;
                if (conf < 0.5f) lowConfCount++;
                if (s[0] < minDx) minDx = s[0]; if (s[0] > maxDx) maxDx = s[0];
                if (s[1] < minDy) minDy = s[1]; if (s[1] > maxDy) maxDy = s[1];
                sumDx += s[0]; sumDy += s[1];
            }
        }
        if (edgeFalloffApplied > 0) {
            Log.d(TAG, String.format(
                    "Edge tile falloff: %d/%d border tiles had confidence scaled "
                            + "(falloff radius = %d tiles → ~%.0f sensor px)",
                    edgeFalloffApplied, tilesPerFrame,
                    EDGE_CONFIDENCE_FALLOFF_TILES,
                    EDGE_CONFIDENCE_FALLOFF_TILES * TILE_STRIDE_LUMA * 4f));
        }

        float meanDx = (float)(sumDx / tilesPerFrame);
        float meanDy = (float)(sumDy / tilesPerFrame);
        Log.d(TAG, String.format(
                "Pyramid grid (sensor px): mean=(%.2f,%.2f)  range=(%.2f..%.2f, %.2f..%.2f)"
                        + "  lowConfTiles=%d/%d  levels=%d",
                meanDx * 4f, meanDy * 4f,
                minDx * 4f, maxDx * 4f, minDy * 4f, maxDy * 4f,
                lowConfCount, tilesPerFrame, NUM_LEVELS));

        // PhotonCamera-borrow (align.glsl `getPrevOffset`, HDR+
        // `correct_upsampling_error`): for each tile, also evaluate SAD at
        // each of its 4 cardinal neighbours' shifts and pick whichever
        // candidate has the lowest SAD. This rescues tiles whose own SAD
        // landscape is fooled by a motion boundary — a true motion-boundary
        // tile will reliably pick the neighbour's shift even if it's locally
        // inconsistent. Cheap (5× block SAD per tile, all integer-precision).
        if (ENABLE_NEIGHBOUR_SHIFT_RECHECK) {
            neighbourShiftSadRecheck(result, refPyr[0], testPyr[0],
                    levelW[0], levelH[0], nTilesX, nTilesY);
        }

        if (SHIFT_SMOOTH_RADIUS > 0) {
            float noiseBefore = tileToTileNoise(result, nTilesX, nTilesY);
            smoothShiftGridInPlace(result, nTilesX, nTilesY,
                    SHIFT_SMOOTH_RADIUS, SHIFT_SMOOTH_SIGMA);
            float noiseAfter  = tileToTileNoise(result, nTilesX, nTilesY);
            Log.d(TAG, String.format(
                    "Shift smoothing: radius=%d σ=%.2f  tile-to-tile noise %.3f → %.3f sensor px",
                    SHIFT_SMOOTH_RADIUS, SHIFT_SMOOTH_SIGMA,
                    noiseBefore * 4f, noiseAfter * 4f));
        }

        return result;
    }

    /**
     * PhotonCamera-style neighbour-shift SAD recheck (the HDR+
     * {@code correct_upsampling_error} analogue extended to 5 candidates).
     *
     * <p>For each tile, evaluate the level-0 SAD at the integer shift of:
     * <ul>
     *   <li>the tile itself</li>
     *   <li>each of its 4 cardinal neighbours (W, E, N, S)</li>
     * </ul>
     * If a neighbour's shift produces a lower SAD, copy the neighbour's
     * full (sub-pixel) shift to this tile. Confidence is kept conservatively
     * at the neighbour's confidence (× 0.85 attenuation to reflect that the
     * sub-pixel refinement was done at the neighbour's tile centre, not at
     * ours). The result array layout is unchanged.
     */
    private static void neighbourShiftSadRecheck(float[] result,
                                                 float[] ref, float[] test,
                                                 int w, int h,
                                                 int nTilesX, int nTilesY) {
        int blockSize = BLOCK_SIZE_PER_LEVEL[0];
        float[] newResult = result.clone();
        // Self first so when ties occur we keep our own answer.
        int[] offsets = {0, 0,  -1, 0,  1, 0,  0, -1,  0, 1};
        int swapped = 0;
        for (int gy = 0; gy < nTilesY; gy++) {
            for (int gx = 0; gx < nTilesX; gx++) {
                int cx = TILE_SIZE_LUMA / 2 + gx * TILE_STRIDE_LUMA;
                int cy = TILE_SIZE_LUMA / 2 + gy * TILE_STRIDE_LUMA;
                int selfIdx = gy * nTilesX + gx;

                int bestIdx = selfIdx;
                float bestSad = Float.POSITIVE_INFINITY;
                for (int c = 0; c < 5; c++) {
                    int nx = gx + offsets[c * 2];
                    int ny = gy + offsets[c * 2 + 1];
                    if (nx < 0 || ny < 0 || nx >= nTilesX || ny >= nTilesY) continue;
                    int nIdx = ny * nTilesX + nx;
                    int cdx = Math.round(result[nIdx * 3]);
                    int cdy = Math.round(result[nIdx * 3 + 1]);
                    float sad = sadAtIntegerShift(ref, test, w, h, cx, cy,
                            cdx, cdy, blockSize);
                    if (sad < bestSad) {
                        bestSad = sad;
                        bestIdx = nIdx;
                    }
                }
                if (bestIdx != selfIdx) {
                    newResult[selfIdx * 3]     = result[bestIdx * 3];
                    newResult[selfIdx * 3 + 1] = result[bestIdx * 3 + 1];
                    newResult[selfIdx * 3 + 2] = Math.max(result[selfIdx * 3 + 2],
                            result[bestIdx * 3 + 2] * 0.85f);
                    swapped++;
                }
            }
        }
        System.arraycopy(newResult, 0, result, 0, result.length);
        if (swapped > 0) {
            Log.d(TAG, String.format(
                    "Neighbour-shift SAD recheck: %d/%d tiles swapped to neighbour shift.",
                    swapped, nTilesX * nTilesY));
        }
    }

    /**
     * Sum-of-absolute-differences between {@code ref} and {@code test} for an
     * (integer) shift at a tile centred at (cx, cy), block size {@code blockSize}.
     * Returns {@link Float#POSITIVE_INFINITY} if either block falls outside
     * the level-0 image.
     */
    private static float sadAtIntegerShift(float[] ref, float[] test, int w, int h,
                                           int cx, int cy, int dx, int dy,
                                           int blockSize) {
        int half = blockSize / 2;
        int bx0 = cx - half;
        int by0 = cy - half;
        int bx1 = bx0 + blockSize;
        int by1 = by0 + blockSize;
        if (bx0 < 0 || by0 < 0 || bx1 > w || by1 > h) return Float.POSITIVE_INFINITY;
        int tx0 = bx0 + dx;
        int ty0 = by0 + dy;
        int tx1 = bx1 + dx;
        int ty1 = by1 + dy;
        if (tx0 < 0 || ty0 < 0 || tx1 > w || ty1 > h) return Float.POSITIVE_INFINITY;

        float sad = 0f;
        for (int y = 0; y < blockSize; y++) {
            int rRow = (by0 + y) * w + bx0;
            int tRow = (ty0 + y) * w + tx0;
            for (int x = 0; x < blockSize; x++) {
                sad += Math.abs(ref[rRow + x] - test[tRow + x]);
            }
        }
        return sad;
    }

    /**
     * Apply a confidence-weighted Gaussian smoothing to the dense shift grid
     * IN PLACE (the confidence channel is untouched).
     *
     * <p>For each tile, the new (dx, dy) is the weighted average of all tiles
     * in a (2·radius+1)² neighbourhood, with weights
     *   {@code w = exp(-(dx² + dy²) / (2σ²)) · confidence}.
     * Confidence-weighted averaging means a noisy low-confidence tile cannot
     * pollute its high-confidence neighbours' shifts: the high-conf tile gets
     * mostly itself plus a tiny pull from its also-high-conf neighbours.
     *
     * <p>This removes the per-tile sub-pixel SAD/parabolic noise that
     * otherwise warps flat regions visibly (each tile's ~0.1–0.3 src-px noise
     * becomes a smooth gradient under bilinear interp in the shader).
     */
    private static void smoothShiftGridInPlace(float[] grid, int nx, int ny,
                                               int radius, float sigma) {
        float[] outDx = new float[nx * ny];
        float[] outDy = new float[nx * ny];
        float invTwoSigma2 = 1f / (2f * sigma * sigma);

        for (int gy = 0; gy < ny; gy++) {
            for (int gx = 0; gx < nx; gx++) {
                float sumDx = 0f, sumDy = 0f, sumW = 0f;
                for (int dy = -radius; dy <= radius; dy++) {
                    int ny_ = clamp(gy + dy, 0, ny - 1);
                    for (int dx = -radius; dx <= radius; dx++) {
                        int nx_ = clamp(gx + dx, 0, nx - 1);
                        int nIdx = (ny_ * nx + nx_) * 3;
                        float spatial = (float) Math.exp(-(dx * dx + dy * dy) * invTwoSigma2);
                        // Small epsilon so 0-confidence tiles still vote a tiny
                        // amount; otherwise an entire 3×3 of zero-conf tiles
                        // would have sumW=0 and we'd lose all info.
                        float w = spatial * (grid[nIdx + 2] + 1e-3f);
                        sumDx += w * grid[nIdx];
                        sumDy += w * grid[nIdx + 1];
                        sumW  += w;
                    }
                }
                int o = gy * nx + gx;
                outDx[o] = sumDx / sumW;
                outDy[o] = sumDy / sumW;
            }
        }
        for (int i = 0; i < nx * ny; i++) {
            grid[i * 3]     = outDx[i];
            grid[i * 3 + 1] = outDy[i];
            // confidence channel (grid[i*3+2]) is preserved unchanged
        }
    }

    /**
     * Compute the RMS difference between each tile shift and its 4-neighbour
     * average — a simple measure of how "noisy" the shift grid is between
     * adjacent tiles (in level-0 / luma pixels).
     */
    private static float tileToTileNoise(float[] grid, int nx, int ny) {
        if (nx < 2 || ny < 2) return 0f;
        double ss = 0;
        int n = 0;
        for (int gy = 1; gy < ny - 1; gy++) {
            for (int gx = 1; gx < nx - 1; gx++) {
                int c  = (gy * nx + gx) * 3;
                int l  = (gy * nx + (gx - 1)) * 3;
                int r  = (gy * nx + (gx + 1)) * 3;
                int u  = ((gy - 1) * nx + gx) * 3;
                int d  = ((gy + 1) * nx + gx) * 3;
                float meanDx = 0.25f * (grid[l] + grid[r] + grid[u] + grid[d]);
                float meanDy = 0.25f * (grid[l + 1] + grid[r + 1] + grid[u + 1] + grid[d + 1]);
                float ex = grid[c] - meanDx;
                float ey = grid[c + 1] - meanDy;
                ss += ex * ex + ey * ey;
                n++;
            }
        }
        if (n == 0) return 0f;
        return (float) Math.sqrt(ss / n);
    }

    /**
     * Coarse-to-fine SAD refinement for a single tile.
     * Returns {@code {subDx, subDy, confidence}} in level-0 pixels.
     */
    private static float[] pyramidalTileShift(float[][] refPyr, float[][] testPyr,
                                              int[] levelW, int[] levelH,
                                              int cx0, int cy0) {
        // Prior shift starts at (0, 0) at the COARSEST level.
        int priorDx = 0;
        int priorDy = 0;

        for (int l = NUM_LEVELS - 1; l >= 1; l--) {
            int cxL = cx0 >> l;
            int cyL = cy0 >> l;
            int blockSize = BLOCK_SIZE_PER_LEVEL[l];
            int searchR   = SEARCH_PER_LEVEL[l];
            int[] best = integerSadSearch(refPyr[l], testPyr[l],
                    levelW[l], levelH[l], cxL, cyL,
                    priorDx, priorDy, blockSize, searchR);
            // Scale up to the next FINER level (image is 2× larger there).
            priorDx = best[0] * 2;
            priorDy = best[1] * 2;
        }

        // Finest level: integer SAD + sub-pixel parabolic refinement + confidence.
        return finestLevelMatch(refPyr[0], testPyr[0], levelW[0], levelH[0],
                cx0, cy0, priorDx, priorDy);
    }

    /**
     * Integer-precision SAD search around a prior shift at a single pyramid
     * level. Returns the integer {dx, dy} that minimises SAD over a (2R+1)²
     * grid centred at the prior. If the reference block does not fit at the
     * tile centre, returns the prior unchanged so finer levels can still try.
     */
    private static int[] integerSadSearch(float[] ref, float[] test, int w, int h,
                                          int cx, int cy, int priorDx, int priorDy,
                                          int blockSize, int searchR) {
        int half = blockSize / 2;
        int bx0 = cx - half;
        int by0 = cy - half;
        int bx1 = bx0 + blockSize;
        int by1 = by0 + blockSize;
        if (bx0 < 0 || by0 < 0 || bx1 > w || by1 > h) {
            return new int[]{priorDx, priorDy};
        }

        int bestDx = priorDx;
        int bestDy = priorDy;
        float bestSad = Float.MAX_VALUE;

        for (int sy = -searchR; sy <= searchR; sy++) {
            int dy = priorDy + sy;
            int ty0 = by0 + dy;
            int ty1 = by1 + dy;
            if (ty0 < 0 || ty1 > h) continue;
            for (int sx = -searchR; sx <= searchR; sx++) {
                int dx = priorDx + sx;
                int tx0 = bx0 + dx;
                int tx1 = bx1 + dx;
                if (tx0 < 0 || tx1 > w) continue;

                float sad = 0f;
                for (int y = 0; y < blockSize; y++) {
                    int rRow = (by0 + y) * w + bx0;
                    int tRow = (ty0 + y) * w + tx0;
                    for (int x = 0; x < blockSize; x++) {
                        sad += Math.abs(ref[rRow + x] - test[tRow + x]);
                    }
                }
                if (sad < bestSad) {
                    bestSad = sad;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }
        return new int[]{bestDx, bestDy};
    }

    /**
     * Finest-level SAD search around a prior shift, with sub-pixel parabolic
     * refinement and confidence scoring.
     *
     * @return float[3] = {subDx, subDy, confidence}
     */
    private static float[] finestLevelMatch(float[] ref, float[] test, int w, int h,
                                            int cx, int cy, int priorDx, int priorDy) {
        int blockSize = BLOCK_SIZE_PER_LEVEL[0];
        int searchR   = SEARCH_PER_LEVEL[0];
        int half = blockSize / 2;
        int bx0 = cx - half;
        int by0 = cy - half;
        int bx1 = bx0 + blockSize;
        int by1 = by0 + blockSize;
        if (bx0 < 0 || by0 < 0 || bx1 > w || by1 > h) {
            return new float[]{priorDx, priorDy, 0f};
        }
        int blockN = blockSize * blockSize;

        // Block variance (texture score)
        float blockMean = 0f;
        for (int y = by0; y < by1; y++) {
            int row = y * w;
            for (int x = bx0; x < bx1; x++) blockMean += ref[row + x];
        }
        blockMean /= blockN;
        float blockVar = 0f;
        for (int y = by0; y < by1; y++) {
            int row = y * w;
            for (int x = bx0; x < bx1; x++) {
                float d = ref[row + x] - blockMean;
                blockVar += d * d;
            }
        }
        blockVar /= blockN;

        // SAD landscape (small (2R+1)² grid around prior)
        int gridN = 2 * searchR + 1;
        float[][] sadMap = new float[gridN][gridN];
        int bestDxI = priorDx, bestDyI = priorDy;
        float bestSad = Float.MAX_VALUE;
        float sadSum = 0f;
        int sadCount = 0;
        for (int sy = -searchR; sy <= searchR; sy++) {
            int dy = priorDy + sy;
            int ty0 = by0 + dy;
            int ty1 = by1 + dy;
            if (ty0 < 0 || ty1 > h) {
                for (int sx = 0; sx < gridN; sx++) sadMap[sy + searchR][sx] = Float.NaN;
                continue;
            }
            for (int sx = -searchR; sx <= searchR; sx++) {
                int dx = priorDx + sx;
                int tx0 = bx0 + dx;
                int tx1 = bx1 + dx;
                if (tx0 < 0 || tx1 > w) {
                    sadMap[sy + searchR][sx + searchR] = Float.NaN;
                    continue;
                }
                float sad = 0f;
                for (int y = 0; y < blockSize; y++) {
                    int rRow = (by0 + y) * w + bx0;
                    int tRow = (ty0 + y) * w + tx0;
                    for (int x = 0; x < blockSize; x++) {
                        sad += Math.abs(ref[rRow + x] - test[tRow + x]);
                    }
                }
                sadMap[sy + searchR][sx + searchR] = sad;
                sadSum += sad;
                sadCount++;
                if (sad < bestSad) {
                    bestSad = sad;
                    bestDxI = dx;
                    bestDyI = dy;
                }
            }
        }
        if (sadCount == 0) {
            return new float[]{priorDx, priorDy, 0f};
        }

        // Parabolic sub-pixel refinement
        float subDx = bestDxI, subDy = bestDyI;
        int xi = bestDxI - priorDx + searchR;
        int yi = bestDyI - priorDy + searchR;
        if (xi > 0 && xi < gridN - 1) {
            float l = sadMap[yi][xi - 1], c = sadMap[yi][xi], r = sadMap[yi][xi + 1];
            if (!Float.isNaN(l) && !Float.isNaN(r)) {
                float den = 2f * c - l - r;
                if (Math.abs(den) > 1e-6f) {
                    float off = 0.5f * (r - l) / den;
                    if (off > 1f) off = 1f;
                    if (off < -1f) off = -1f;
                    subDx = bestDxI + off;
                }
            }
        }
        if (yi > 0 && yi < gridN - 1) {
            float u = sadMap[yi - 1][xi], c = sadMap[yi][xi], d = sadMap[yi + 1][xi];
            if (!Float.isNaN(u) && !Float.isNaN(d)) {
                float den = 2f * c - u - d;
                if (Math.abs(den) > 1e-6f) {
                    float off = 0.5f * (d - u) / den;
                    if (off > 1f) off = 1f;
                    if (off < -1f) off = -1f;
                    subDy = bestDyI + off;
                }
            }
        }

        // Confidence score in [0, 1]
        float meanSad = sadSum / sadCount;
        float peakSharpness = (meanSad > 1e-6f)
                ? Math.max(0f, (meanSad - bestSad) / meanSad) : 0f;
        float sharpScore = Math.min(1f, peakSharpness / MIN_PEAK_SHARPNESS);
        float varScore   = Math.min(1f, blockVar / MIN_BLOCK_VARIANCE);
        float confidence = sharpScore * varScore;

        // ICA-refine the sub-pixel shift using gradient-based Gauss-Newton.
        // Only run if the parabolic fit gave a non-zero confidence — at zero
        // confidence the tile is texture-less and ICA would oscillate.
        if (ENABLE_ICA_SUBPIXEL && confidence > 0.05f) {
            float[] ica = icaRefine(ref, test, w, h, cx, cy,
                    blockSize, subDx, subDy);
            // Reject ICA if it drifted unreasonably far (likely texture-less tile).
            if (Math.abs(ica[0] - subDx) <= ICA_MAX_DRIFT
                    && Math.abs(ica[1] - subDy) <= ICA_MAX_DRIFT) {
                subDx = ica[0];
                subDy = ica[1];
            }
        }

        return new float[]{subDx, subDy, confidence};
    }

    /**
     * Wronski 2019 inverse-compositional ICA sub-pixel refinement
     * (ICA.py:36 + Algorithm 3). Replaces the parabolic SAD-landscape fit
     * with a Gauss-Newton iteration that minimises
     * {@code Σ_p [test(p + shift) − ref(p)]²} over the tile.
     *
     * <p>The Hessian {@code H = Σ_p ∇I_ref(p) ∇I_ref(p)ᵀ} is computed once
     * on the REFERENCE (the "inverse-compositional" trick — see Baker &amp;
     * Matthews 2004), and only the per-iteration residual sums
     * {@code S = Σ_p (test(p + shift) − ref(p)) ∇I_ref(p)} change. Each
     * iteration then solves the 2×2 linear system
     * {@code H · δ = −S} for the shift update.
     *
     * @param ref       full-resolution reference luma buffer (level 0)
     * @param test      full-resolution test luma buffer (level 0)
     * @param w         buffer width in pixels
     * @param h         buffer height in pixels
     * @param cx        tile centre x (level-0 pixels)
     * @param cy        tile centre y (level-0 pixels)
     * @param blockSize tile block size (matches the SAD search)
     * @param initDx    initial (sub-pixel) shift from the parabolic fit
     * @param initDy    initial (sub-pixel) shift from the parabolic fit
     * @return {@code float[2] = {subDx, subDy}}. Returns the unchanged input
     *         if the Hessian is too ill-conditioned to invert.
     */
    private static float[] icaRefine(float[] ref, float[] test, int w, int h,
                                     int cx, int cy, int blockSize,
                                     float initDx, float initDy) {
        int half = blockSize / 2;
        int bx0 = cx - half;
        int by0 = cy - half;
        int bx1 = bx0 + blockSize;
        int by1 = by0 + blockSize;
        // Need 1-pixel border for central-difference gradient.
        if (bx0 < 1 || by0 < 1 || bx1 + 1 > w || by1 + 1 > h) {
            return new float[] { initDx, initDy };
        }

        // Precompute gradients and reference values for the tile, and the
        // 2×2 Hessian of the reference inside the tile.
        int blockN = blockSize * blockSize;
        float[] gxArr  = new float[blockN];
        float[] gyArr  = new float[blockN];
        float[] refArr = new float[blockN];
        float Hxx = 0f, Hxy = 0f, Hyy = 0f;
        int idx = 0;
        for (int y = by0; y < by1; y++) {
            int row     = y * w;
            int rowUp   = (y - 1) * w;
            int rowDown = (y + 1) * w;
            for (int x = bx0; x < bx1; x++) {
                float gx = 0.5f * (ref[row + x + 1] - ref[row + x - 1]);
                float gy = 0.5f * (ref[rowDown + x] - ref[rowUp   + x]);
                gxArr[idx]  = gx;
                gyArr[idx]  = gy;
                refArr[idx] = ref[row + x];
                Hxx += gx * gx;
                Hxy += gx * gy;
                Hyy += gy * gy;
                idx++;
            }
        }

        float det = Hxx * Hyy - Hxy * Hxy;
        if (det < 1e-10f) {
            // Texture-less tile — ICA undefined.
            return new float[] { initDx, initDy };
        }
        float invDet = 1f / det;

        float dx = initDx;
        float dy = initDy;
        for (int iter = 0; iter < ICA_ITERATIONS; iter++) {
            float Sx = 0f;
            float Sy = 0f;
            idx = 0;
            for (int y = by0; y < by1; y++) {
                for (int x = bx0; x < bx1; x++) {
                    float testX = (float) x + dx;
                    float testY = (float) y + dy;
                    int ix = (int) Math.floor(testX);
                    int iy = (int) Math.floor(testY);
                    if (ix < 0 || iy < 0 || ix + 1 >= w || iy + 1 >= h) {
                        idx++;
                        continue;
                    }
                    float fx = testX - ix;
                    float fy = testY - iy;
                    int row0 = iy * w + ix;
                    int row1 = row0 + w;
                    float t00 = test[row0];
                    float t10 = test[row0 + 1];
                    float t01 = test[row1];
                    float t11 = test[row1 + 1];
                    float t = (1f - fy) * ((1f - fx) * t00 + fx * t10)
                            +       fy  * ((1f - fx) * t01 + fx * t11);
                    float residual = t - refArr[idx];
                    Sx += gxArr[idx] * residual;
                    Sy += gyArr[idx] * residual;
                    idx++;
                }
            }
            // δ = −H⁻¹ S  (LK update for shift composition).
            float deltaX = -invDet * (Hyy * Sx - Hxy * Sy);
            float deltaY = -invDet * (Hxx * Sy - Hxy * Sx);
            dx += deltaX;
            dy += deltaY;
            if (deltaX * deltaX + deltaY * deltaY < ICA_CONVERGENCE_SQ) break;
        }
        return new float[] { dx, dy };
    }
}
