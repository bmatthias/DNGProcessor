package amirz.dngprocessor.pipeline.burst;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import amirz.dngprocessor.colorspace.ColorspaceConverter;
import amirz.dngprocessor.gl.GLContext;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.params.SensorParams;
import amirz.dngprocessor.parser.RawFrame;
import amirz.dngprocessor.pipeline.GLBlockProcessing;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.BayerProvider;
import amirz.dngprocessor.pipeline.convert.EdgeMirror;
import amirz.dngprocessor.pipeline.convert.GreenDemosaic;
import amirz.dngprocessor.pipeline.convert.ToIntermediate;
import amirz.dngprocessor.pipeline.exposefuse.Laplace;
import amirz.dngprocessor.pipeline.exposefuse.Merge;
import amirz.dngprocessor.pipeline.exposefuse.DoubleExpose;
import amirz.dngprocessor.pipeline.intermediate.Analysis;
import amirz.dngprocessor.pipeline.intermediate.BilateralFilter;
import amirz.dngprocessor.pipeline.intermediate.CLAHE;
import amirz.dngprocessor.pipeline.intermediate.MergeDetail;
import amirz.dngprocessor.pipeline.noisereduce.Decompose;
import amirz.dngprocessor.pipeline.noisereduce.NoiseMap;
import amirz.dngprocessor.pipeline.noisereduce.NoiseReduce;
import amirz.dngprocessor.pipeline.post.BlurLCE;
import amirz.dngprocessor.pipeline.post.HdrCompress;
import amirz.dngprocessor.pipeline.post.HistogramMatch;
import amirz.dngprocessor.pipeline.post.ToneMap;
import amirz.dngprocessor.pipeline.locallaplacian.LocalLaplacian;
import amirz.dngprocessor.pipeline.toneequalizer.ToneEqualizer;
import amirz.dngprocessor.util.ShaderLoader;

import static amirz.dngprocessor.util.Constants.BLOCK_HEIGHT;

/**
 * Processing pipeline for a burst of Bayer (CFA) raw frames.
 *
 * <p>Stages:
 * <ol>
 *   <li>{@link BurstPreProcess} – normalise N frames, align them.</li>
 *   <li>{@link BurstBayerSrMerge} (default when SR on) – Wronski 2× Lanczos
 *       accumulation with per-tile flow; each frame splats onto the HR grid.</li>
 *   <li>{@link BurstFrequencyMerge} + {@link BurstSrUpsample} (legacy) – HDR+
 *       Wiener merge at 1× then single-frame Bayer upsample.</li>
 *   <li>Standard stages from {@link GreenDemosaic} onward (same as single-frame pipeline).</li>
 * </ol>
 */
public class BurstStagePipeline implements AutoCloseable {
    private static final String TAG = "BurstStagePipeline";

    /**
     * If true, ALSO dump the 1× freq-merge output as {@code <name>_merged_1x.dng}
     * (in addition to the SR-upsampled 2× DNG). Lets you compare the merged
     * Bayer before and after SR upsample side-by-side and pinpoint which
     * stage is introducing any artefact.
     */
    public static final boolean EXPORT_DEBUG_DNGS = true;

    private final List<Stage> mStages = new ArrayList<>();
    private final SensorParams mSensor;
    private final ProcessParams mProcess;
    private final GLContext mGLContext;
    private final GLPrograms mConverter;
    private final TexturePool mTexturePool;
    private final GLBlockProcessing mBlockProcessing;

    public BurstStagePipeline(GLContext glContext, SensorParams refSensor, ProcessParams process,
                              List<RawFrame> frames, Bitmap argbOutput, ShaderLoader loader) {
        this(glContext, refSensor, process, frames, argbOutput, loader, null);
    }

    /**
     * @param mergedDngPath if non-null, the merged Bayer texture is also exported as a
     *                      self-contained DNG to this path (between the merge and
     *                      the downstream demosaic/JPEG stages). Pass {@code null}
     *                      to skip the side-car DNG export.
     */
    public BurstStagePipeline(GLContext glContext, SensorParams refSensor, ProcessParams process,
                              List<RawFrame> frames, Bitmap argbOutput, ShaderLoader loader,
                              String mergedDngPath) {
        mSensor = refSensor;
        mProcess = process;
        mGLContext = glContext;

        Log.i(TAG, String.format(
                "BurstStagePipeline: USE_WRONSKI_2X=%s  %s  freqMerge=%s  SR=%s  debugDng=%s",
                BurstBayerSrMerge.USE_WRONSKI_2X_MERGE,
                BurstWronskiBisect.flagsSummary(),
                BurstFrequencyMerge.ENABLE_FREQ_MERGE,
                BurstSrUpsample.ENABLE_SR,
                EXPORT_DEBUG_DNGS));

        // Burst replaces single-frame exposure fusion
        process.exposeFuse = false;
        process.baselineExposureCompression = 0;
        process.hdrCompressionMethod = refSensor.baselineExposure > 0.5f ? 4 : 0;

        // Mirror every flag that StagePipeline (the regular single-frame pipeline)
        // explicitly overrides so that the burst pipeline and the regular pipeline
        // behave identically for all downstream stages.
        //
        // WITHOUT these overrides, flags read from user preferences by BurstParser
        // are passed straight through.  E.g. if the user has LocalLaplacian or
        // ToneEqualizer turned on, those stages run in the burst pipeline but are
        // forcibly disabled in the regular pipeline — and a bug in either of those
        // multi-scale shaders produces the "downsampled-and-upscaled block" artifact.
        process.demosaicingMethod    = "bilinear"; // DHT/AAHD exceed mobile GPU instruction limits
        process.localLaplacianEnabled = false;     // StagePipeline always forces false
        process.toneEqualizerEnabled  = false;     // StagePipeline always forces false
        process.edgeAwareHistEq       = false;     // StagePipeline always forces false

        int outWidth  = argbOutput.getWidth();
        int outHeight = argbOutput.getHeight();

        glContext.setDimens(outWidth, BLOCK_HEIGHT);
        mConverter      = GLPrograms.getInstance(glContext, loader);
        mTexturePool    = TexturePool.getInstance(glContext);
        mBlockProcessing = new GLBlockProcessing(argbOutput);

        ColorspaceConverter colorspace = new ColorspaceConverter(refSensor);

        // Burst-specific stages
        BurstPreProcess preProcess = new BurstPreProcess(frames);
        BurstHotPixel hotPixel = new BurstHotPixel(preProcess, frames);

        final boolean wronski2x = BurstSrUpsample.ENABLE_SR
                && BurstBayerSrMerge.USE_WRONSKI_2X_MERGE;
        final boolean useRgbUpsample = BurstSrUpsample.ENABLE_SR
                && BurstSrUpsample.USE_RGB_UPSAMPLE;

        BurstBayerSrMerge bayerSrMerge = wronski2x
                ? new BurstBayerSrMerge(preProcess, frames) : null;
        BurstBayerSrSharpen bayerSrSharpen = wronski2x
                ? new BurstBayerSrSharpen(bayerSrMerge) : null;
        BurstFrequencyMerge freqMerge = wronski2x
                ? null : new BurstFrequencyMerge(preProcess, frames);
        BurstSrUpsample srUpsample = (wronski2x || useRgbUpsample)
                ? null : new BurstSrUpsample(freqMerge);

        addStage(preProcess);
        if (hotPixel.isEnabled()) {
            addStage(hotPixel);
        }

        if (wronski2x) {
            addStage(bayerSrMerge);
            addStage(bayerSrSharpen);
            BayerProvider mergedOut = bayerSrSharpen.isEnabled() ? bayerSrSharpen : bayerSrMerge;
            if (mergedDngPath != null && !mergedDngPath.isEmpty()) {
                addStage(new MergedDngExporter(mergedOut, frames.get(0), 2, mergedDngPath));
            }
            Log.i(TAG, "Merge path: Wronski 2× (" + BurstWronskiBisect.flagsSummary()
                    + (bayerSrSharpen.isEnabled() ? " +unsharp" : "") + ")");
        } else {
            addStage(freqMerge);
            if (EXPORT_DEBUG_DNGS && mergedDngPath != null && !mergedDngPath.isEmpty()) {
                String mergedDng1xPath = mergedDngPath.replace("_merged.dng", "_merged_1x.dng");
                freqMerge.setDebugExport1xPath(mergedDng1xPath);
                Log.i(TAG, "Will export 1× freq-merge DNG -> " + mergedDng1xPath);
            }
            Log.i(TAG, "Merge path: BurstFrequencyMerge (1×) + BurstSrUpsample (anisotropic 2×)");
            if (!useRgbUpsample) {
                addStage(srUpsample);
                if (mergedDngPath != null && !mergedDngPath.isEmpty()) {
                    int srFactor = BurstSrUpsample.ENABLE_SR ? 2 : 1;
                    addStage(new MergedDngExporter(srUpsample, frames.get(0), srFactor, mergedDngPath));
                }
            } else if (mergedDngPath != null && !mergedDngPath.isEmpty()) {
                Log.i(TAG, "USE_RGB_UPSAMPLE: skipping Bayer 2× _merged.dng export");
            }
        }

        addStage(new GreenDemosaic());
        addStage(new ToIntermediate(colorspace.sensorToXYZ_D50, colorspace.yuvCamMatrix));
        if (useRgbUpsample) {
            addStage(new BurstRgbUpsample());
        }

        EdgeMirror edgeMirror = new EdgeMirror();
        edgeMirror.setOffsetScale(BurstSrUpsample.ENABLE_SR ? 2 : 1);
        addStage(edgeMirror);

        // Noise reduction
        addStage(new Decompose());
        addStage(new NoiseMap());
        addStage(new NoiseReduce(refSensor, process, colorspace.sensorToXYZ_D50));

        boolean addHistogramMatch = process.useReferencePreview && refSensor.hasPreview();
        boolean willAddHdrCompress = refSensor.baselineExposure > 0.0f;

        // Offsets are scaled by the SR factor (2× in SR mode, 1× in plain-merge mode).
        int srFactor = BurstSrUpsample.ENABLE_SR ? 2 : 1;
        addStage(new Analysis(outWidth, outHeight,
                srFactor * refSensor.outputOffsetX, srFactor * refSensor.outputOffsetY,
                false, willAddHdrCompress, addHistogramMatch, true));

        addStage(new ToneEqualizer());
        addStage(new LocalLaplacian());
        addStage(new BilateralFilter(process));
        addStage(new MergeDetail(process));

        if (process.lce && "clahe".equals(process.lceMethod)) {
            process.claheStrength = 1.0f;
            addStage(new CLAHE(process));
        } else {
            BlurLCE blurLCE = new BlurLCE();
            blurLCE.setOffsetScale(BurstSrUpsample.ENABLE_SR ? 2 : 1);
            addStage(blurLCE);
        }

        if (refSensor.baselineExposure > 0.0f) {
            addStage(new HdrCompress());
        }

        if (addHistogramMatch) {
            addStage(new HistogramMatch(colorspace.XYZtoProPhoto));
        }

        ToneMap toneMap = new ToneMap(colorspace.XYZtoProPhoto, colorspace.proPhotoToSRGB);
        toneMap.setOutOffsetScale(BurstSrUpsample.ENABLE_SR ? 2 : 1);
        addStage(toneMap);
    }

    private void addStage(Stage stage) {
        stage.init(mConverter, mSensor, mProcess);
        mStages.add(stage);
    }

    public void execute(StagePipeline.OnProgressReporter reporter) {
        TexturePool.setCurrent(mTexturePool);
        try {
            int total = mStages.size();
            for (int i = 0; i < total; i++) {
                Stage stage = mStages.get(i);
                reporter.onProgress(i, total, stage.getClass().getSimpleName());
                if (stage.isEnabled()) {
                    mConverter.useProgram(stage.getShader());
                    stage.execute(new StagePipeline.StageMap(mStages.subList(0, i)));
                }
            }
            mBlockProcessing.drawBlocksToOutput(mConverter);
            reporter.onProgress(total, total, "Done");
        } finally {
            TexturePool.clearCurrent();
        }
    }

    @Override
    public void close() {
        for (Stage s : mStages) s.close();
        mStages.clear();
    }
}
