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
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.params.SensorParams;
import amirz.dngprocessor.parser.RawFrame;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.BayerProvider;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_COLOR_BUFFER_BIT;
import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glClear;
import static android.opengl.GLES20.glClearColor;

/**
 * Wronski Alg. 1 / 4 / 11: full 2× multi-frame SR merge on Bayer.
 */
public class BurstBayerSrMerge extends Stage implements BayerProvider {
    private static final String TAG = "BurstBayerSrMerge";

    public static final boolean USE_WRONSKI_2X_MERGE = true;

    public static final boolean EXPORT_ROBUSTNESS_MASKS = false;

    static final float TILE_CONFIDENCE_BLEND_GAMMA = 2.0f;

    private final BurstPreProcess mPreProcess;
    private final List<RawFrame> mFrames;
    private Texture mMergedTex;

    public BurstBayerSrMerge(BurstPreProcess preProcess, List<RawFrame> frames) {
        mPreProcess = preProcess;
        mFrames = frames;
    }

    @Override public Texture getSensorTex()  { return mMergedTex; }
    @Override public int getInWidth() {
        return mFrames.isEmpty() ? 0 : 2 * mFrames.get(0).sensor.inputWidth;
    }
    @Override public int getInHeight() {
        return mFrames.isEmpty() ? 0 : 2 * mFrames.get(0).sensor.inputHeight;
    }
    @Override public int getCfaPattern() {
        return mFrames.isEmpty() ? 0 : mFrames.get(0).sensor.cfa;
    }
    @Override public Texture getGainMapTex() { return null; }

    @Override
    public int getShader() {
        return R.raw.burst_normalize_bayer;
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();
        ProcessParams process = getProcessParams();
        SensorParams sensor = getSensorParams();
        List<Texture> frameTex = mPreProcess.getSensorTextures();
        BayerAlignment.TileShiftResult flow = mPreProcess.getTileShifts();

        int srcW = mFrames.get(0).sensor.inputWidth;
        int srcH = mFrames.get(0).sensor.inputHeight;
        int outW = 2 * srcW;
        int outH = 2 * srcH;
        int n = frameTex.size();
        int mergeMode = BurstSrUpsample.MERGE_SR_EQUAL;
        if ("hdr_bracket".equalsIgnoreCase(process.burstMode)
                || ("auto".equalsIgnoreCase(process.burstMode) && process.burstBracketed)) {
            mergeMode = BurstSrUpsample.MERGE_HDR_BRACKET;
        }
        float postMergeGain = (mergeMode == BurstSrUpsample.MERGE_HDR_BRACKET)
                ? (float) Math.pow(2.0, process.burstPostExposureBoostEv) : 1f;

        float[][] frameMedShifts = computeMedianShifts(flow, n);
        float[] wb = BurstWronskiRobustness.wbGain(sensor);
        int cfa = getCfaPattern();

        BurstWronskiNoiseLut noiseLut = BurstWronskiNoiseLut.fromSensor(sensor);

        Texture tileFlowTex = new Texture(flow.nTilesX, flow.nTilesY, 4, Texture.Format.Float16,
                null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        ByteBuffer tileFlowBuf = ByteBuffer.allocateDirect(flow.tilesPerFrame() * 4 * 4)
                .order(ByteOrder.nativeOrder());
        FloatBuffer tileFlowFb = tileFlowBuf.asFloatBuffer();

        final boolean robustOn = BurstWronskiBisect.ENABLE_ROBUSTNESS;
        final boolean mergeRefOn = BurstWronskiBisect.ENABLE_MERGE_REF;
        final boolean covOn = BurstWronskiBisect.ENABLE_PER_FRAME_COV;
        BurstWronskiRobustness.LocalStats refStats = null;
        Texture accRob = null;
        if (robustOn) {
            Texture refGuide = BurstWronskiRobustness.buildGuide(
                    converter, frameTex.get(0), tileFlowTex,
                    srcW, srcH, cfa, wb, flow, null, 0);
            BurstWronskiRobustness.LocalStats refGuideStats =
                    BurstWronskiRobustness.buildLocalStats(converter, refGuide);
            refGuide.close();
            refStats = BurstWronskiRobustness.upscaleStatsToBayer(
                    converter, refGuideStats, srcW, srcH, flow, tileFlowTex, null, 0);
            refGuideStats.close();
            accRob = TexturePool.get(srcW, srcH, 1, Texture.Format.Float16);
            accRob.setFrameBuffer();
            glClearColor(0f, 0f, 0f, 0f);
            glClear(GL_COLOR_BUFFER_BIT);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        }

        Texture refCov = covOn
                ? BurstWronskiKernels.estimate(converter, frameTex.get(0), srcW, srcH)
                : null;

        Texture accumA = TexturePool.get(outW, outH, 4, Texture.Format.Float16);
        Texture accumB = TexturePool.get(outW, outH, 4, Texture.Format.Float16);
        accumA.setFrameBuffer();
        glClearColor(0f, 0f, 0f, 0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        Log.i(TAG, String.format(
                "=== Wronski 2× merge START  frames=%d  %dx%d -> %dx%d  %s ===",
                n, srcW, srcH, outW, outH, BurstWronskiBisect.flagsSummary()));

        final int kStart = mergeRefOn ? 1 : 0;
        for (int k = kStart; k < n; k++) {
            RawFrame raw = mFrames.get(k);
            float relativeEv = raw.relativeEv;
            float frameScale = (float) Math.pow(2.0, -relativeEv);

            Texture frameCov = covOn
                    ? BurstWronskiKernels.estimate(converter, frameTex.get(k), srcW, srcH)
                    : null;

            Texture robustTex = null;
            if (robustOn && k > 0) {
                uploadFlowForFrame(tileFlowTex, tileFlowFb, flow, k);
                // Guide without tile flow — flow is applied only in upscale_warp_stats.
                Texture altGuide = BurstWronskiRobustness.buildGuide(
                        converter, frameTex.get(k), tileFlowTex,
                        srcW, srcH, cfa, wb, flow, frameMedShifts[k], 0);
                BurstWronskiRobustness.LocalStats altGuideStats =
                        BurstWronskiRobustness.buildLocalStats(converter, altGuide);
                altGuide.close();
                BurstWronskiRobustness.LocalStats altStats =
                        BurstWronskiRobustness.upscaleStatsToBayer(
                                converter, altGuideStats, srcW, srcH, flow, tileFlowTex,
                                frameMedShifts[k], 1);
                altGuideStats.close();
                Texture flowSTex = BurstWronskiRobustness.buildFlowSTex(
                        converter, tileFlowTex, flow);
                robustTex = BurstWronskiRobustness.buildRobustness(
                        converter, refStats, altStats, flowSTex, flow, noiseLut);
                flowSTex.close();
                altStats.close();

                Texture accRobNext = BurstWronskiRobustness.accumulateRobustness(
                        converter, accRob, robustTex);
                accRob.close();
                accRob = accRobNext;
            }

            bindAltAccumProgram(converter, flow, srcW, srcH,
                    mergeMode, frameTex.get(k), frameCov, accumA, tileFlowTex, robustTex,
                    frameMedShifts[k], relativeEv, frameScale);

            converter.drawBlocks(accumB, false);

            if (frameCov != null) {
                frameCov.close();
            }
            if (robustTex != null) {
                robustTex.close();
            }

            Texture tmp = accumA;
            accumA = accumB;
            accumB = tmp;

            Log.d(TAG, String.format("  frame[%d] relEv=%+.2f scale=%.3f", k, relativeEv, frameScale));
        }

        if (mergeRefOn) {
            bindMergeRefProgram(converter, srcW, srcH,
                    frameTex.get(0), refCov, accumA, accRob);
            converter.drawBlocks(accumB, false);

            Texture tmp = accumA;
            accumA = accumB;
            accumB = tmp;
            accumB.close();
        }

        if (refCov != null) {
            refCov.close();
        }
        if (refStats != null) {
            refStats.close();
        }
        if (accRob != null) {
            accRob.close();
        }
        tileFlowTex.close();
        noiseLut.close();

        mMergedTex = TexturePool.get(outW, outH, 1, Texture.Format.Float16);
        converter.useProgram(R.raw.burst_normalize_bayer);
        converter.setTexture("accumBuffer", accumA);
        converter.setf("postMergeGain", postMergeGain);
        converter.drawBlocks(mMergedTex, false);
        accumA.close();

        Log.i(TAG, "=== Wronski 2× merge DONE " + outW + "x" + outH + " ===");
    }

    private static void bindAltAccumProgram(GLPrograms converter,
                                            BayerAlignment.TileShiftResult flow,
                                            int srcW, int srcH,
                                            int mergeMode, Texture frameTex, Texture frameCov,
                                            Texture accumTex, Texture tileFlowTex,
                                            Texture robustTex, float[] medianShift,
                                            float relativeEv, float frameScale) {
        converter.useProgram(R.raw.burst_accum_bayer);
        converter.setf("highlightClipThreshold", BurstSrUpsample.HIGHLIGHT_CLIP_THRESHOLD);
        converter.seti("inWidth", srcW);
        converter.seti("inHeight", srcH);
        converter.setf("tileFlowOriginSrc", flow.tileOriginSrcX, flow.tileOriginSrcY);
        converter.setf("tileFlowStrideSrc", flow.tileStrideSrcX, flow.tileStrideSrcY);
        converter.seti("tileFlowSize", flow.nTilesX, flow.nTilesY);
        converter.setf("tileFlowConfGamma", TILE_CONFIDENCE_BLEND_GAMMA);
        converter.seti("useFourCornerFlow", BurstWronskiBisect.ENABLE_HANN_TILE_FLOW ? 1 : 0);
        converter.seti("useSteerableKernel",
                BurstWronskiBisect.ENABLE_STEERABLE_ACCUM ? 1 : 0);
        converter.setTexture("frameBuffer", frameTex);
        converter.setTexture("accumBuffer", accumTex);
        converter.setTexture("frameCov",
                frameCov != null ? frameCov : BurstWronskiKernels.isoCovPlaceholder());
        converter.setTexture("tileFlowTex", tileFlowTex);
        converter.seti("useTileFlow", 1);
        converter.setf("globalShift", medianShift[0], medianShift[1]);
        converter.seti("mergeMode", mergeMode);
        converter.setf("frameRelativeEv", relativeEv);
        converter.setf("frameScale", frameScale);
        if (robustTex != null) {
            converter.setTexture("robustTex", robustTex);
            converter.seti("useRobustness", 1);
            converter.seti("robustSize", srcW, srcH);
            converter.seti("robustHalfRes", 0);
            converter.setf("robustRFloor", BurstWronskiTuning.ROBUST_R_FLOOR);
        } else {
            converter.seti("useRobustness", 0);
        }
    }

    private static void bindMergeRefProgram(GLPrograms converter, int srcW, int srcH,
                                            Texture refFrame, Texture refCov,
                                            Texture accumTex, Texture accRob) {
        converter.useProgram(R.raw.burst_wronski_merge_ref);
        converter.seti("inWidth", srcW);
        converter.seti("inHeight", srcH);
        converter.setTexture("refFrame", refFrame);
        converter.setTexture("accumBuffer", accumTex);
        converter.setTexture("frameCov",
                refCov != null ? refCov : BurstWronskiKernels.isoCovPlaceholder());
        converter.seti("useSteerableKernel",
                BurstWronskiBisect.ENABLE_STEERABLE_ACCUM ? 1 : 0);
        boolean useAcc = BurstWronskiBisect.ENABLE_ACC_ROB_DENOISE && accRob != null;
        converter.seti("useAccRobDenoise", useAcc ? 1 : 0);
        converter.seti("accRobHalfRes", 0);
        converter.setf("accMaxMultiplier", BurstWronskiTuning.ACC_MAX_MULTIPLIER);
        converter.seti("accMaxFrameCount", BurstWronskiTuning.ACC_MAX_FRAME_COUNT);
        converter.seti("accRadMax", BurstWronskiTuning.ACC_RAD_MAX);
        if (useAcc) {
            converter.setTexture("accRobTex", accRob);
            converter.seti("accRobSize", srcW, srcH);
        }
    }

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
            Arrays.sort(tmpDx);
            Arrays.sort(tmpDy);
            med[k][0] = tmpDx[tilesPerFrame / 2];
            med[k][1] = tmpDy[tilesPerFrame / 2];
        }
        return med;
    }

    private static void uploadFlowForFrame(Texture tileFlowTex, FloatBuffer fb,
                                           BayerAlignment.TileShiftResult flow, int k) {
        int tilesPerFrame = flow.tilesPerFrame();
        int shiftBase = k * tilesPerFrame * 2;
        int confBase = k * tilesPerFrame;
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

    @Override
    public void close() {
        // mMergedTex returned to pool by ToIntermediate.
    }
}
