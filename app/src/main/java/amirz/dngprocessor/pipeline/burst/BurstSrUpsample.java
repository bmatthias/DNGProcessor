package amirz.dngprocessor.pipeline.burst;

import android.opengl.GLES30;
import android.util.Log;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.BayerProvider;

/**
 * Single-frame Wronski anisotropic 1× → 2× Bayer upsampler. Reads the already-
 * merged 1× Bayer from {@link BurstFrequencyMerge} and produces a 2× Bayer
 * texture for downstream demosaic + tone-map stages.
 *
 * <p>This is the SR resample half of the old monolithic burst merge — without
 * any per-frame loop, robustness, or HDR-bracket weighting, since the
 * frequency-domain merge has already produced a single denoised Bayer.
 *
 * <p>Implements {@link BayerProvider} so downstream stages see the same
 * interface they previously saw from the old monolithic merge stage.
 */
public class BurstSrUpsample extends Stage implements BayerProvider {
    private static final String TAG = "BurstSrUpsample";

    /** When false, the merged 1× Bayer is passed through verbatim (1× output). */
    public static final boolean ENABLE_SR = true;

    /**
     * Debug workaround only: demosaic 1× merge then bilinear RGB 2×. This is
     * <em>not</em> Wronski SR — it drops structure-guided Bayer upsampling.
     * Use only to confirm fringing comes from Bayer SR vs merge. Production
     * path keeps Bayer {@link #ENABLE_SR} + {@link BurstSrUpsample}.
     */
    public static final boolean USE_RGB_UPSAMPLE = false;

    /**
     * DEBUG: when true, the anisotropic Gaussian SR shader is replaced with a
     * trivial nearest-neighbour 1× → 2× CFA-preserving upsample. Lets us tell
     * apart "the SR shader produces a broken output" from "the 2× DNG export
     * path is broken". If the 2× side-car DNG is clean with this flag set but
     * corrupted with it cleared, the bug is in {@code burst_sr_upsample.glsl}.
     * If it's still corrupted, the bug is downstream of the SR shader (DNG
     * readback / writer).
     */
    public static final boolean USE_NEAREST_NEIGHBOR_SR = false;

    /**
     * DEBUG: when true, both NN and Gaussian SR paths are replaced with a
     * known-gradient fill shader. Each CFA parity gets a different smooth
     * gradient; the resulting 2× DNG should show clean ramps in every row.
     * If the DNG instead shows scrambled / repeated / shifted rows, the bug
     * is conclusively in the readback or DngWriter, not in any compute
     * shader (because there is nothing left in the data path that depends
     * on the input frames).
     */
    public static final boolean USE_FILL_TEST_SR = false;

    // ---- Merge-mode constants (shared with the linear-raw burst path) ----
    /** Equal-weight Wronski SR merge — uniform-exposure burst. */
    public static final int MERGE_SR_EQUAL    = 0;
    /** Joint HDR + SR — bracketed burst. */
    public static final int MERGE_HDR_BRACKET = 1;

    /**
     * Highlight clip threshold (in normalised frame EV) used by HDR_BRACKET
     * mode to discard pixels that are saturated in their own frame.
     * 0.99 ≈ hdr-plus-swift default.
     */
    public static final float HIGHLIGHT_CLIP_THRESHOLD = 0.99f;

    /** Anisotropic kernels (legacy 2× upsample and Wronski 2× accumulate). */
    public static final boolean USE_ANISOTROPIC_KERNEL = true;
    public static final float   EDGE_STRENGTH_GAIN     = 200.0f;
    /** Keep moderate — strong anisotropy amplifies any per-channel blur mismatch. */
    public static final float   MAX_ANISOTROPY_RATIO   = 2.0f;
    public static final float   BASE_SIGMA             = 0.5f;

    private final BurstFrequencyMerge mFreqMerge;
    private Texture mUpsampledTex;

    public BurstSrUpsample(BurstFrequencyMerge freqMerge) {
        mFreqMerge = freqMerge;
    }

    // ---- BayerProvider ----

    @Override public Texture getSensorTex()  { return mUpsampledTex; }
    @Override public int getInWidth() {
        int src = mFreqMerge.getInWidth();
        return ENABLE_SR ? 2 * src : src;
    }
    @Override public int getInHeight() {
        int src = mFreqMerge.getInHeight();
        return ENABLE_SR ? 2 * src : src;
    }
    @Override public int getCfaPattern() { return mFreqMerge.getCfaPattern(); }
    @Override public Texture getGainMapTex() { return null; }

    // ---- Stage ----

    @Override
    public int getShader() {
        return R.raw.burst_sr_upsample;
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        Texture merged = mFreqMerge.getSensorTex();
        int srcW = mFreqMerge.getInWidth();
        int srcH = mFreqMerge.getInHeight();

        if (!ENABLE_SR) {
            mUpsampledTex = merged;
            Log.i(TAG, "SR disabled — pass-through " + srcW + "x" + srcH);
            return;
        }

        GLPrograms converter = getConverter();
        int outW = 2 * srcW;
        int outH = 2 * srcH;

        // -------- Diagnostic: GPU limits at 2× allocation time --------
        // GL_MAX_VIEWPORT_DIMS is the prime suspect for the "4 quadrants"
        // artefact: drawBlocks() calls glViewport(0, *, outW, BLOCK_HEIGHT)
        // and on many Adreno/Mali GPUs the implementation silently clamps
        // viewport width to GL_MAX_VIEWPORT_DIMS[0]. If that limit is
        // smaller than outW (e.g. 4096 for an 8192-wide SR texture), only
        // the left half of each block is rendered, and the right half
        // retains whatever data was previously in the texture.
        int[] maxTexSize  = new int[1];
        int[] maxVpDims   = new int[2];
        int[] maxRbSize   = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE,      maxTexSize, 0);
        GLES30.glGetIntegerv(GLES30.GL_MAX_VIEWPORT_DIMS,     maxVpDims,  0);
        GLES30.glGetIntegerv(GLES30.GL_MAX_RENDERBUFFER_SIZE, maxRbSize,  0);
        Log.i(TAG, String.format(
                "GPU limits  MAX_TEXTURE_SIZE=%d  MAX_VIEWPORT_DIMS=[%d,%d]  "
                        + "MAX_RENDERBUFFER_SIZE=%d  needed=%dx%d  "
                        + "viewport-fits=%b  texture-fits=%b",
                maxTexSize[0], maxVpDims[0], maxVpDims[1], maxRbSize[0],
                outW, outH,
                outW <= maxVpDims[0] && outH <= maxVpDims[1],
                outW <= maxTexSize[0] && outH <= maxTexSize[0]));

        mUpsampledTex = TexturePool.get(outW, outH, 1, Texture.Format.Float16);

        if (USE_FILL_TEST_SR) {
            // DEBUG path: write a deterministic gradient pattern. Bypasses
            // every compute shader so any corruption in the resulting 2×
            // DNG is conclusively a readback / DngWriter bug.
            Log.w(TAG, "USE_FILL_TEST_SR=true — writing gradient pattern instead of SR");
            converter.useProgram(R.raw.burst_sr_fill);
            converter.seti("outWidth", outW);
            converter.seti("outHeight", outH);

            // Direct viewport sanity probe: ask the driver to use a viewport
            // matching the full 2× texture, then read back what it actually
            // accepted. If it clamped, we've found the bug.
            mUpsampledTex.setFrameBuffer();
            GLES30.glViewport(0, 0, outW, outH);
            int[] vpAfter = new int[4];
            GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, vpAfter, 0);
            Log.i(TAG, String.format(
                    "Viewport probe  requested=(0,0,%d,%d)  actual=(%d,%d,%d,%d)  "
                    + "matches=%b",
                    outW, outH,
                    vpAfter[0], vpAfter[1], vpAfter[2], vpAfter[3],
                    vpAfter[0] == 0 && vpAfter[1] == 0
                            && vpAfter[2] == outW && vpAfter[3] == outH));

            converter.drawBlocks(mUpsampledTex);
            merged.close();
            Log.i(TAG, "SR upsample (fill debug) done " + outW + "x" + outH);
            return;
        }

        if (USE_NEAREST_NEIGHBOR_SR) {
            // DEBUG path: skip the anisotropic Gaussian entirely. Pure
            // nearest-neighbour CFA-preserving 2× expansion.
            Log.w(TAG, "USE_NEAREST_NEIGHBOR_SR=true — using debug NN upsample");
            converter.useProgram(R.raw.burst_sr_nn);
            converter.setTexture("mergedBayerTex", merged);
            converter.seti("inWidth", srcW);
            converter.seti("inHeight", srcH);
            converter.drawBlocks(mUpsampledTex);
            merged.close();
            Log.i(TAG, "SR upsample (NN debug) done " + outW + "x" + outH);
            return;
        }

        // Structure tensor for the anisotropic kernel.
        Texture structTensor = null;
        if (USE_ANISOTROPIC_KERNEL) {
            structTensor = TexturePool.get(srcW, srcH, 4, Texture.Format.Float16);
            converter.useProgram(R.raw.burst_structure_tensor);
            converter.setTexture("refFrame", merged);
            converter.seti("inWidth", srcW);
            converter.seti("inHeight", srcH);
            converter.drawBlocks(structTensor);
        }

        converter.useProgram(R.raw.burst_sr_upsample);
        converter.setTexture("mergedBayerTex", merged);
        converter.seti("inWidth", srcW);
        converter.seti("inHeight", srcH);
        converter.setf("baseSigma", BASE_SIGMA);
        converter.setf("edgeStrengthGain", EDGE_STRENGTH_GAIN);
        converter.setf("maxAnisotropyRatio", MAX_ANISOTROPY_RATIO);
        converter.seti("useAnisotropicKernel", USE_ANISOTROPIC_KERNEL ? 1 : 0);
        if (structTensor != null) {
            converter.setTexture("structureTensor", structTensor);
        } else {
            // Driver still requires the sampler to be bound; use merged as a dummy
            // — the shader skips reads when useAnisotropicKernel == 0.
            converter.setTexture("structureTensor", merged);
        }
        converter.drawBlocks(mUpsampledTex);

        if (structTensor != null) structTensor.close();
        merged.close();

        Log.i(TAG, "SR upsample done " + outW + "x" + outH);
    }

    @Override
    public void close() {
        // ownership transferred downstream via getSensorTex()
    }
}
