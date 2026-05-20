package amirz.dngprocessor.pipeline.burst;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import amirz.dngprocessor.colorspace.LinearRawColorspaceConverter;
import amirz.dngprocessor.gl.GLContext;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.params.SensorParams;
import amirz.dngprocessor.parser.RawFrame;
import amirz.dngprocessor.pipeline.GLBlockProcessing;
import amirz.dngprocessor.pipeline.LinearRawToIntermediate;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.LinearRawEdgeMirror;
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
 * Processing pipeline for a burst of linear-raw (already demosaiced) frames.
 *
 * <p>Stages:
 * <ol>
 *   <li>{@link BurstLinearPreProcess} – normalise N frames, align them.</li>
 *   <li>{@link BurstLinearSrMerge} – accumulate into a merged RGBA texture.</li>
 *   <li>Standard stages from {@link LinearRawToIntermediate} onward.</li>
 * </ol>
 */
public class BurstLinearStagePipeline implements AutoCloseable {
    private static final String TAG = "BurstLinearStagePipeline";

    private final List<Stage> mStages = new ArrayList<>();
    private final SensorParams mSensor;
    private final ProcessParams mProcess;
    private final GLContext mGLContext;
    private final GLPrograms mConverter;
    private final TexturePool mTexturePool;
    private final GLBlockProcessing mBlockProcessing;

    public BurstLinearStagePipeline(GLContext glContext, SensorParams refSensor, ProcessParams process,
                                    List<RawFrame> frames, Bitmap argbOutput, ShaderLoader loader) {
        mSensor = refSensor;
        mProcess = process;
        mGLContext = glContext;

        process.exposeFuse = false;
        process.baselineExposureCompression = 0;
        process.hdrCompressionMethod = refSensor.baselineExposure > 0.5f ? 4 : 0;

        int outWidth  = argbOutput.getWidth();
        int outHeight = argbOutput.getHeight();

        glContext.setDimens(outWidth, BLOCK_HEIGHT);
        mConverter      = GLPrograms.getInstance(glContext, loader);
        mTexturePool    = TexturePool.getInstance(glContext);
        mBlockProcessing = new GLBlockProcessing(argbOutput);

        LinearRawColorspaceConverter colorspace = new LinearRawColorspaceConverter(refSensor);

        BurstLinearPreProcess preProcess = new BurstLinearPreProcess(frames);
        BurstLinearSrMerge srMerge = new BurstLinearSrMerge(preProcess, frames);

        addStage(preProcess);
        addStage(srMerge);

        addStage(new LinearRawToIntermediate(colorspace.sensorToXYZ_D50));
        addStage(new LinearRawEdgeMirror());

        addStage(new Decompose());
        addStage(new NoiseMap());
        addStage(new NoiseReduce(refSensor, process, colorspace.sensorToXYZ_D50));

        boolean addHistogramMatch = process.useReferencePreview && refSensor.hasPreview();
        boolean willAddHdrCompress = refSensor.baselineExposure > 0.0f;

        // Offsets are doubled because the merged RGB texture is at 2x resolution.
        addStage(new Analysis(outWidth, outHeight,
                2 * refSensor.outputOffsetX, 2 * refSensor.outputOffsetY,
                false, willAddHdrCompress, addHistogramMatch, true));

        addStage(new ToneEqualizer());
        addStage(new LocalLaplacian());
        addStage(new BilateralFilter(process));
        addStage(new MergeDetail(process));

        if (process.lce && "clahe".equals(process.lceMethod)) {
            process.claheStrength = 1.0f;
            addStage(new CLAHE(process));
        } else {
            addStage(new BlurLCE());
        }

        if (refSensor.baselineExposure > 0.0f) {
            addStage(new HdrCompress());
        }

        if (addHistogramMatch) {
            addStage(new HistogramMatch(colorspace.XYZtoProPhoto));
        }

        addStage(new ToneMap(colorspace.XYZtoProPhoto, colorspace.proPhotoToSRGB));
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
