package amirz.dngprocessor.pipeline;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import amirz.dngprocessor.colorspace.LinearRawColorspaceConverter;
import amirz.dngprocessor.gl.GLContext;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.math.BlockDivider;
import amirz.dngprocessor.pipeline.exposefuse.EarlyExposureFusion;
import amirz.dngprocessor.pipeline.exposefuse.LateExposureFusion;
import amirz.dngprocessor.util.Constants;
import amirz.dngprocessor.util.ShaderLoader;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.params.SensorParams;
import amirz.dngprocessor.pipeline.convert.LinearRawEdgeMirror;
import amirz.dngprocessor.pipeline.convert.LinearRawPreProcess;
import amirz.dngprocessor.pipeline.exposefuse.Laplace;
import amirz.dngprocessor.pipeline.exposefuse.Merge;
import amirz.dngprocessor.pipeline.exposefuse.DoubleExpose;
import amirz.dngprocessor.pipeline.intermediate.BilateralFilter;
import amirz.dngprocessor.pipeline.intermediate.Analysis;
import amirz.dngprocessor.pipeline.intermediate.MergeDetail;
import amirz.dngprocessor.pipeline.intermediate.CLAHE;
import amirz.dngprocessor.pipeline.noisereduce.Decompose;
import amirz.dngprocessor.pipeline.noisereduce.NoiseMap;
import amirz.dngprocessor.pipeline.noisereduce.NoiseReduce;
import amirz.dngprocessor.pipeline.post.BlurLCE;
import amirz.dngprocessor.pipeline.post.HdrCompress;
import amirz.dngprocessor.pipeline.post.HistogramMatch;
import amirz.dngprocessor.pipeline.post.ToneMap;
import amirz.dngprocessor.pipeline.locallaplacian.LocalLaplacian;
import amirz.dngprocessor.pipeline.toneequalizer.ToneEqualizer;

import static amirz.dngprocessor.util.Constants.BLOCK_HEIGHT;

/**
 * Processing pipeline for already-demosaiced Linear Raw DNG files (e.g., Xiaomi UltraRAW).
 * This pipeline skips the CFA demosaicing stages since the input is already RGB data.
 */
public class LinearRawPipeline implements AutoCloseable {
    private static final String TAG = "LinearRawPipeline";

    private final List<Stage> mStages = new ArrayList<>();

    private final SensorParams mSensor;
    private final ProcessParams mProcess;
    private final GLContext mGLContext;
    private final GLPrograms mConverter;
    private final TexturePool mTexturePool;
    private final GLBlockProcessing mBlockProcessing;

    public LinearRawPipeline(GLContext glContext, SensorParams sensor, ProcessParams process,
                             byte[] raw, Bitmap argbOutput, ShaderLoader loader) {
        mSensor = sensor;
        mProcess = process;
        mGLContext = glContext;

        // Set the defaults explicitly, as long as the preferences are hidden:
        process.baselineExposureCompression = 0;
        process.hdrCompressionMethod = process.exposeFuse ? 5 : 4;
        process.exposeFusionMethod = "mertens";
        process.demosaicingMethod = "bilinear";
        process.edgeAwareHistEq = false;
        process.localLaplacianEnabled = false;
        process.toneEqualizerEnabled = false;

        int outWidth = argbOutput.getWidth();
        int outHeight = argbOutput.getHeight();

        if (outWidth + sensor.outputOffsetX > sensor.inputWidth
                || outHeight + sensor.outputOffsetY > sensor.inputHeight) {
            throw new IllegalArgumentException("Raw image with dimensions (w=" + sensor.inputWidth
                    + ", h=" + sensor.inputHeight
                    + "), cannot converted into sRGB image with dimensions (w="
                    + outWidth + ", h=" + outHeight + ").");
        }
        Log.d(TAG, "Output width,height: " + outWidth + "," + outHeight);

        glContext.setDimens(argbOutput.getWidth(), BLOCK_HEIGHT);
        mConverter = GLPrograms.getInstance(glContext, loader);
        mTexturePool = TexturePool.getInstance(glContext);
        mBlockProcessing = new GLBlockProcessing(argbOutput);

        LinearRawColorspaceConverter colorspace = new LinearRawColorspaceConverter(sensor);

        // Linear Raw already has RGB data - just need to preprocess and convert colorspace
        addStage(new LinearRawPreProcess(raw));

        // Early exposure fusion (when baselineExposureCompression == 17)
        // Runs BEFORE LinearRawToIntermediate so it works on RGB data
        // Approach: Extract luma, merge in luma space, scale original RGB by luma ratio
        // This avoids banding because we never do xyY<->RGB conversion
        // Skip when baselineExposure == 0 (no HDR content to fuse)
        boolean addEarlyFusion = (process.baselineExposureCompression == 17 && sensor.baselineExposure != 0.0f);
        // Late exposure fusion (when hdrCompressionMethod == 5)
        // Runs right before ToneMap, replaces HDR compression and xyY => RGB conversion in ToneMap shader
        boolean addLateFusion = (process.hdrCompressionMethod == 5 && sensor.baselineExposure != 0.0f);

        if (addEarlyFusion) {
            addStage(new EarlyExposureFusion());
        }
        
        // Skip GreenDemosaic - data is already demosaiced
        // Skip ToIntermediate - use LinearRawToIntermediate instead
        addStage(new LinearRawToIntermediate(colorspace.sensorToXYZ_D50));

        addStage(new LinearRawEdgeMirror());

        // Noise Reduce (enabled/disabled via process.noiseReduce toggle)
        addStage(new Decompose());
        addStage(new NoiseMap());
        addStage(new NoiseReduce(sensor, process, colorspace.sensorToXYZ_D50));

        // Check if histogram matching should be applied (needed to optimize Analysis stage)
        boolean addHistogramMatch = process.useReferencePreview && sensor.hasPreview();
        
        // Determine what Analysis needs to compute:
        // - buildRawCdf: NO LONGER NEEDED - HistogramMatch builds its own CDF from current intermediate
        // - computeMaxHdrValue: only if HdrCompress will be used (HistogramMatch builds its own)
        // - computeMeanColor: if reference preview matching is enabled (for ToneMap color matching)
        // - computeHistogramStats: always needed (MergeDetail always uses histogram stats)
        //   Note: AutoTune also uses histogram stats, but MergeDetail is the primary consumer
        boolean willAddHdrCompress = !addLateFusion && sensor.baselineExposure > 0.0f;
        boolean buildRawCdf = false;  // HistogramMatch builds its own CDF from current intermediate
        boolean computeMaxHdrValue = willAddHdrCompress;  // Only HdrCompress needs this now
        boolean computeMeanColor = addHistogramMatch;
        boolean computeHistogramStats = true;  // Always needed: MergeDetail always uses histogram stats

        // Analysis: Compute histogram statistics for auto-tuning and tone mapping
        // Only compute what's actually needed to reduce processing time
        addStage(new Analysis(outWidth, outHeight, sensor.outputOffsetX, sensor.outputOffsetY,
                buildRawCdf, computeMaxHdrValue, computeMeanColor, computeHistogramStats));

        // Optional processing stages (each runs independently based on its toggle)
        addStage(new ToneEqualizer());        // EV-band exposure adjustment (darktable-style)
        addStage(new LocalLaplacian());       // Edge-aware local contrast (darktable-style)
        // Exposure fusion stages: only add if EarlyExposureFusion is not already added
        // EarlyExposureFusion handles exposure fusion when baselineExposureCompression == 17 AND baselineExposure != 0
        if (process.exposeFuse && !(addEarlyFusion || addLateFusion)) {
            addStage(new DoubleExpose());         // Exposure fusion: create multiple exposures
            addStage(new Laplace());              // Exposure fusion: build Laplacian pyramids
            addStage(new Merge());                 // Exposure fusion: blend exposures
        }
        addStage(new BilateralFilter(process)); // Edge-preserving smoothing
        addStage(new MergeDetail(process));   // Adaptive histogram equalization

        // Local contrast enhancement (choose one method)
        if (process.lce && "clahe".equals(process.lceMethod)) {
            process.claheStrength = 1.0f;
            addStage(new CLAHE(process));
        } else {
            addStage(new BlurLCE());          // Multi-scale blur LCE or Fast CLAHE
        }
        
        if (addLateFusion) {
            addStage(new LateExposureFusion(colorspace.XYZtoProPhoto, addHistogramMatch));
        } else if (sensor.baselineExposure > 0.0f) {
            // HDR compression stage (applies HDR compression to xyY before tone mapping)
            // Only add if LateExposureFusion is not added and baselineEV > 0
            // This preserves color saturation by working in xyY space
            // Supports multiple compression methods: Reinhard, ACES Filmic, Uncharted 2, Improved Rational, Gamma+ACES Fusion
            addStage(new HdrCompress());
        }
        
        // Histogram matching stage (applies histogram matching to xyY before tone mapping)
        // Uses histogram matching in xyY space to preserve color and saturation
        // Also handles xyY => RGB conversion if it's the last stage before ToneMap
        if (addHistogramMatch) {
            addStage(new HistogramMatch(colorspace.XYZtoProPhoto));
        }
        
        addStage(new ToneMap(colorspace.XYZtoProPhoto, colorspace.proPhotoToSRGB));
    }

    private void addStage(Stage stage) {
        Log.d(TAG, "addStage: " + stage.getClass().getSimpleName());
        stage.init(mConverter, mSensor, mProcess);
        mStages.add(stage);
        Log.d(TAG, "addStage complete, total stages=" + mStages.size());
    }

    public void execute(OnProgressReporter reporter) {
        // Set up TexturePool for this thread during execution
        TexturePool.setCurrent(mTexturePool);
        try {
            int stageCount = mStages.size();
            Log.d(TAG, "LinearRawPipeline.execute(): Starting with " + stageCount + " stages");
            for (int i = 0; i < stageCount; i++) {
                Stage stage = mStages.get(i);
                String stageName = stage.getClass().getSimpleName();
                reporter.onProgress(i, stageCount, stageName);
                boolean enabled = stage.isEnabled();
                Log.d(TAG, "Stage " + i + "/" + stageCount + ": " + stageName + " - enabled=" + enabled);
                if (enabled) {
                    Log.d(TAG, "Executing stage: " + stageName);
                    mConverter.useProgram(stage.getShader());
                    stage.execute(new StagePipeline.StageMap(mStages.subList(0, i)));
                    Log.d(TAG, "Completed stage: " + stageName);
                } else {
                    Log.d(TAG, "Skipping disabled stage: " + stageName);
                }
            }

            // Assume that last stage set everything but did not render yet.
            // First render SDR version (clamped)
            mBlockProcessing.drawBlocksToOutput(mConverter);

            reporter.onProgress(stageCount, stageCount, "Done");
        } finally {
            TexturePool.clearCurrent();
        }
    }

    /**
     * Capture HDR version of the output (before clamping).
     * This should be called after execute() to get the HDR version.
     * 
     * @return Bitmap with HDR values (gamma-encoded sRGB, may exceed 1.0, stored with logarithmic encoding)
     */
    public Bitmap captureHdrOutput() {
        TexturePool.setCurrent(mTexturePool);
        try {
            Log.d(TAG, "Starting HDR capture...");
            
            // Find ToneMap stage and enable HDR output mode
            ToneMap toneMap = null;
            for (Stage stage : mStages) {
                if (stage instanceof ToneMap) {
                    toneMap = (ToneMap) stage;
                    break;
                }
            }
            
            if (toneMap == null) {
                Log.e(TAG, "ToneMap stage not found");
                return null;
            }
            
            Log.d(TAG, "Found ToneMap stage, enabling HDR output mode");
            
            // Re-execute the ToneMap stage with HDR output
            // All previous stages' outputs are still in textures, so we can re-execute just ToneMap
            StagePipeline.StageMap previousStages = new StagePipeline.StageMap(
                mStages.subList(0, mStages.size() - 1));
            
            // IMPORTANT: useProgram MUST be called BEFORE setHdrOutputMode
            // because seti() sets uniforms on the currently active program
            mConverter.useProgram(toneMap.getShader());
            
            // Ensure we're rendering to the default framebuffer (0) for reading
            // The Pbuffer surface is the default framebuffer
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, 0);
            
            Log.d(TAG, "Re-executing ToneMap with HDR output...");
            toneMap.reExecute(previousStages);
            
            // IMPORTANT: setHdrOutputMode MUST be called AFTER reExecute
            // because execute() sets outputHDR=0 by default
            toneMap.setHdrOutputMode(true);
            
            // Check for GL errors after re-execution
            int glError = android.opengl.GLES20.glGetError();
            if (glError != android.opengl.GLES20.GL_NO_ERROR) {
                Log.w(TAG, "GL error after re-execution: " + glError);
            }
            
            // Pbuffer surfaces don't support reading as GL_FLOAT
            // We need to render to a float texture first, then read from that texture's framebuffer
            Log.d(TAG, "Creating temporary float texture for HDR capture...");
            int outWidth = mBlockProcessing.getOutWidth();
            int outHeight = mBlockProcessing.getOutHeight();
            Texture hdrTexture = TexturePool.get(
                outWidth, outHeight, 4, 
                Texture.Format.Float16);
            
            Bitmap hdrBitmap = null;
            try {
                // Render HDR output to the float texture
                Log.d(TAG, "Rendering HDR output to float texture...");
                mConverter.drawBlocks(hdrTexture);
                
                // Now read from the texture's framebuffer (supports GL_FLOAT)
                Log.d(TAG, "Capturing HDR output from texture framebuffer...");
                hdrBitmap = captureHdrFromTexture(hdrTexture);
                
                if (hdrBitmap != null) {
                    Log.i(TAG, "Successfully captured HDR bitmap: " + 
                          hdrBitmap.getWidth() + "x" + hdrBitmap.getHeight());
                } else {
                    Log.e(TAG, "HDR capture returned null bitmap");
                }
            } finally {
                hdrTexture.close();
                // Restore SDR output mode for future renders
                toneMap.setHdrOutputMode(false);
            }
            
            return hdrBitmap;
        } catch (Exception e) {
            Log.e(TAG, "Failed to capture HDR output", e);
            e.printStackTrace();
            return null;
        } finally {
            TexturePool.clearCurrent();
        }
    }

    /**
     * Capture HDR output from a float texture's framebuffer.
     * This reads as GL_FLOAT which is supported by texture framebuffers.
     * Uses block-based reading to avoid OutOfMemoryError on large images.
     */
    private Bitmap captureHdrFromTexture(Texture texture) {
        int width = texture.getWidth();
        int height = texture.getHeight();
        
        // Set the texture's framebuffer as current
        texture.setFrameBuffer();
        
        android.graphics.Bitmap hdrBitmap = android.graphics.Bitmap.createBitmap(width, height, 
            android.graphics.Bitmap.Config.ARGB_8888);
        
        // Use block-based reading to avoid large memory allocations
        final int BLOCK_HEIGHT = Constants.BLOCK_HEIGHT;
        BlockDivider divider =
            new BlockDivider(height, BLOCK_HEIGHT);
        int[] row = new int[2];
        
        // Allocate buffer for one block only (much smaller than full image)
        java.nio.ByteBuffer byteBuffer = java.nio.ByteBuffer.allocateDirect(
                width * BLOCK_HEIGHT * 4 * 4) // width * blockHeight * RGBA * float size
                .order(java.nio.ByteOrder.nativeOrder());
        java.nio.FloatBuffer floatBuffer = byteBuffer.asFloatBuffer();
        
        // Pre-allocate pixel array for the entire image (int array is smaller than float buffer)
        int[] pixels = new int[width * height];
        int pixelIndex = 0;
        
        float maxHdrValue = 16.0f;
        
        // Process image in blocks
        while (divider.nextBlock(row)) {
            int y = row[0];
            int blockHeight = row[1];
            
            // Read this block as float
            floatBuffer.position(0);
            android.opengl.GLES20.glReadPixels(0, y, width, blockHeight, 
                android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_FLOAT, floatBuffer);
            
            // Check for GL errors
            int glError = android.opengl.GLES20.glGetError();
            if (glError != android.opengl.GLES20.GL_NO_ERROR) {
                Log.e(TAG, "GL error reading block from texture framebuffer: " + 
                      glError + " (y=" + y + ", height=" + blockHeight + ")");
                hdrBitmap.recycle();
                return null;
            }
            
            // Convert float values to bitmap pixels
            // Note: The shader already applies logarithmic encoding, so we just clamp to [0,1] and scale
            floatBuffer.rewind();
            int blockPixelCount = width * blockHeight;
            for (int i = 0; i < blockPixelCount; i++) {
                float r = floatBuffer.get(i * 4 + 0);
                float g = floatBuffer.get(i * 4 + 1);
                float b = floatBuffer.get(i * 4 + 2);
                
                // Shader already applied log encoding: log2(1 + gammaValue) / log2(17)
                // Values should already be in [0, 1] range, just clamp and scale to 8-bit
                int rInt = Math.max(0, Math.min(255, (int)(r * 255.0f)));
                int gInt = Math.max(0, Math.min(255, (int)(g * 255.0f)));
                int bInt = Math.max(0, Math.min(255, (int)(b * 255.0f)));
                
                pixels[pixelIndex++] = android.graphics.Color.rgb(rInt, gInt, bInt);
            }
        }
        
        // Set all pixels at once
        hdrBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        
        // Restore default framebuffer
        android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, 0);
        
        return hdrBitmap;
    }

    @Override
    public void close() {
        for (Stage stage : mStages) {
            stage.close();
        }
        mStages.clear();
        mTexturePool.logLeaks();
    }

    public interface OnProgressReporter {
        void onProgress(int completed, int total, String tag);
    }

    // Use StagePipeline.StageMap for compatibility with existing stages
}

