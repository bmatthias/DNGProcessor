package amirz.dngprocessor.pipeline.burst;

import android.util.Log;

import java.util.Arrays;
import java.util.List;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.parser.RawFrame;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.RgbProvider;

import static android.opengl.GLES20.GL_COLOR_BUFFER_BIT;
import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glClear;
import static android.opengl.GLES20.glClearColor;

/**
 * Multi-frame HDR merge for linear-raw (RGBA) inputs.
 *
 * <p>Uses an explicit read-accumulate-write ping-pong pattern instead of GL_BLEND
 * (which is unsupported on Float16 render targets on most Android devices).
 * Implements {@link RgbProvider} so that the downstream
 * {@link amirz.dngprocessor.pipeline.LinearRawToIntermediate} stage can use it
 * transparently.
 */
public class BurstLinearSrMerge extends Stage implements RgbProvider {
    private static final String TAG = "BurstLinearSrMerge";

    private final BurstLinearPreProcess mPreProcess;
    private final List<RawFrame> mFrames;

    /** Final merged RGBA texture. */
    private Texture mMergedTex;

    public BurstLinearSrMerge(BurstLinearPreProcess preProcess,
                              List<RawFrame> frames) {
        mPreProcess = preProcess;
        mFrames = frames;
    }

    // ---- RgbProvider ----

    @Override public Texture getRgbTex()     { return mMergedTex; }
    /** Returns 2x the input width — the SR output is twice the sensor resolution. */
    @Override public int getInWidth()    { return mFrames.isEmpty() ? 0 : 2 * mFrames.get(0).sensor.inputWidth; }
    /** Returns 2x the input height — the SR output is twice the sensor resolution. */
    @Override public int getInHeight()   { return mFrames.isEmpty() ? 0 : 2 * mFrames.get(0).sensor.inputHeight; }

    @Override public Texture getGainMapTex() {
        // Gain map already applied per-frame in LinearRawPreProcess
        return null;
    }

    // ---- Stage ----

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();
        ProcessParams process = getProcessParams();
        List<Texture> frameTex = mPreProcess.getRgbTextures();
        // BurstLinearSrMerge keeps the old "global median shift only" path; we don't
        // currently feed per-tile flow or confidences to the RGB accum shader.
        BayerAlignment.TileShiftResult tileShiftResult = mPreProcess.getTileShifts();
        float[] tileShifts = tileShiftResult.shifts;

        int W = getInWidth(), H = getInHeight();
        int srcW = W / 2, srcH = H / 2;
        int mergeMode = BurstSrUpsample.MERGE_SR_EQUAL;
        if ("hdr_bracket".equalsIgnoreCase(process.burstMode)
                || ("auto".equalsIgnoreCase(process.burstMode) && process.burstBracketed)) {
            mergeMode = BurstSrUpsample.MERGE_HDR_BRACKET;
        }
        float postMergeGain = (mergeMode == BurstSrUpsample.MERGE_HDR_BRACKET)
                ? (float) Math.pow(2.0, process.burstPostExposureBoostEv) : 1f;
        int tilesPerFrame = tileShiftResult.tilesPerFrame();

        // Derive one robust median shift per frame (same approach as the Bayer burst pipeline)
        float[] tmpDx = new float[tilesPerFrame];
        float[] tmpDy = new float[tilesPerFrame];
        float[][] frameShifts = new float[frameTex.size()][2];
        for (int k = 0; k < frameTex.size(); k++) {
            int base = k * tilesPerFrame * 2;
            for (int t = 0; t < tilesPerFrame; t++) {
                tmpDx[t] = tileShifts[base + t * 2];
                tmpDy[t] = tileShifts[base + t * 2 + 1];
            }
            Arrays.sort(tmpDx);
            Arrays.sort(tmpDy);
            frameShifts[k][0] = tmpDx[tilesPerFrame / 2];
            frameShifts[k][1] = tmpDy[tilesPerFrame / 2];
        }

        Texture accumA = TexturePool.get(W, H, 4, Texture.Format.Float16);
        Texture accumB = TexturePool.get(W, H, 4, Texture.Format.Float16);

        accumA.setFrameBuffer();
        glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        Log.i(TAG, "=== Linear SR Merge START (Lanczos2, median tile shift) ===");
        Log.i(TAG, "  Frames: " + frameTex.size()
                + "  mergeMode=" + (mergeMode == BurstSrUpsample.MERGE_HDR_BRACKET ? "HDR_BRACKET" : "SR_EQUAL")
                + "  bracketed=" + process.burstBracketed
                + "  postBoost=" + String.format("%+.2fEV", process.burstPostExposureBoostEv)
                + "  src=" + srcW + "×" + srcH + "  out=" + W + "×" + H);

        converter.useProgram(R.raw.burst_accum_rgb);

        for (int k = 0; k < frameTex.size(); k++) {
            float dx = frameShifts[k][0], dy = frameShifts[k][1];
            RawFrame raw = mFrames.get(k);
            float exposureTime = raw.exposureTime;
            float relativeEv = raw.relativeEv;
            float frameScale = (float) Math.pow(2.0, -relativeEv);

            converter.setTexture("frameBuffer", frameTex.get(k));
            converter.setTexture("accumBuffer", accumA);
            converter.setf("shift", dx, dy);
            converter.setf("exposureTime", exposureTime);
            converter.setf("frameRelativeEv", relativeEv);
            converter.setf("frameScale", frameScale);
            converter.setf("highlightClipThreshold", BurstSrUpsample.HIGHLIGHT_CLIP_THRESHOLD);
            converter.seti("mergeMode", mergeMode);
            converter.seti("inWidth", srcW);
            converter.seti("inHeight", srcH);

            converter.drawBlocks(accumB);

            Texture tmp = accumA;
            accumA = accumB;
            accumB = tmp;

            Log.d(TAG, String.format("  [%d] dx=%.3f dy=%.3f expT=%.4fs relEv=%+.2f scale=%.3f",
                    k, dx, dy, exposureTime, relativeEv, frameScale));
        }

        // accumA holds the final sums; accumB is stale
        accumB.close();

        // Normalize at 2x resolution
        mMergedTex = TexturePool.get(W, H, 4, Texture.Format.Float16);
        converter.useProgram(R.raw.burst_normalize_rgb);
        converter.setTexture("accumBuffer", accumA);
        converter.setf("postMergeGain", postMergeGain);
        converter.drawBlocks(mMergedTex);

        accumA.close();
        Log.i(TAG, "=== WRONSKI-LANCZOS2 Linear SR Merge DONE  " + W + "x" + H
                + " (src " + srcW + "x" + srcH + ") ===");
    }

    @Override
    public int getShader() {
        return R.raw.burst_normalize_rgb;
    }

    @Override
    public void close() {
        // mMergedTex is closed by downstream stages (same ownership as BurstBayerSrMerge).
    }
}
