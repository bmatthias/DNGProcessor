package amirz.dngprocessor.pipeline;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import amirz.dngprocessor.colorspace.ColorspaceConverter;
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
import amirz.dngprocessor.pipeline.convert.EdgeMirror;
import amirz.dngprocessor.pipeline.convert.GreenDemosaic;
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;
import amirz.dngprocessor.pipeline.convert.PreProcess;
import amirz.dngprocessor.pipeline.convert.ToIntermediate;
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

public class StagePipeline implements AutoCloseable {
    private static final String TAG = "StagePipeline";

    private final List<Stage> mStages = new ArrayList<>();

    private final SensorParams mSensor;
    private final ProcessParams mProcess;
    private final GLContext mGLContext;
    private final GLPrograms mConverter;
    private final TexturePool mTexturePool;
    private final GLBlockProcessing mBlockProcessing;

    public StagePipeline(GLContext glContext, SensorParams sensor, ProcessParams process,
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

        ColorspaceConverter colorspace = new ColorspaceConverter(sensor);

        // RAW -> XYZ -> xyY
        addStage(new PreProcess(raw));
        addStage(new GreenDemosaic());
        addStage(new ToIntermediate(colorspace.sensorToXYZ_D50, colorspace.yuvCamMatrix));

        addStage(new EdgeMirror());

        // Early exposure fusion (when baselineExposureCompression == 17)
        // Runs right after ToIntermediate, supports both Mertens and Laplacian pyramid blending
        // Skip when baselineExposure == 0 (no HDR content to fuse)
        boolean addEarlyFusion = (process.baselineExposureCompression == 17 && sensor.baselineExposure != 0.0f);
        // Late exposure fusion (when hdrCompressionMethod == 5)
        // Runs right before ToneMap, replaces HDR compression and xyY => RGB conversion in ToneMap shader
        boolean addLateFusion = (process.hdrCompressionMethod == 5 && sensor.baselineExposure != 0.0f);

        if (addEarlyFusion) {
            addStage(new EarlyExposureFusion());
        }

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
            Log.d(TAG, "StagePipeline.execute(): Starting with " + stageCount + " stages");
            for (int i = 0; i < stageCount; i++) {
                Stage stage = mStages.get(i);
                String stageName = stage.getClass().getSimpleName();
                reporter.onProgress(i, stageCount, stageName);
                boolean enabled = stage.isEnabled();
                Log.d(TAG, "Stage " + i + "/" + stageCount + ": " + stageName + " - enabled=" + enabled);
                if (enabled) {
                    Log.d(TAG, "Executing stage: " + stageName);
                    mConverter.useProgram(stage.getShader());
                    stage.execute(new StageMap(mStages.subList(0, i)));
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
            android.util.Log.d("StagePipeline", "Starting HDR capture...");
            
            // Find ToneMap stage and enable HDR output mode
            ToneMap toneMap = null;
            for (Stage stage : mStages) {
                if (stage instanceof ToneMap) {
                    toneMap = (ToneMap) stage;
                    break;
                }
            }
            
            if (toneMap == null) {
                android.util.Log.e("StagePipeline", "ToneMap stage not found");
                return null;
            }
            
            android.util.Log.d("StagePipeline", "Found ToneMap stage, enabling HDR output mode");
            
            // Re-execute the ToneMap stage with HDR output
            // All previous stages' outputs are still in textures, so we can re-execute just ToneMap
            StagePipeline.StageMap previousStages = new StagePipeline.StageMap(
                mStages.subList(0, mStages.size() - 1));
            
            // IMPORTANT: setHdrOutputMode MUST be called BEFORE reExecute
            // because execute() checks mOutputHdr at the start to decide HDR vs SDR code path
            // If mOutputHdr is false, it will use SDR path and get RGB from HistogramMatch
            toneMap.setHdrOutputMode(true);
            
            // IMPORTANT: useProgram MUST be called AFTER setHdrOutputMode
            // because seti() sets uniforms on the currently active program
            mConverter.useProgram(toneMap.getShader());
            
            // Ensure we're rendering to the default framebuffer (0) for reading
            // The Pbuffer surface is the default framebuffer
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, 0);
            
            android.util.Log.d("StagePipeline", "Re-executing ToneMap with HDR output...");
            toneMap.reExecute(previousStages);
            
            // Check for GL errors after re-execution
            int glError = android.opengl.GLES20.glGetError();
            if (glError != android.opengl.GLES20.GL_NO_ERROR) {
                android.util.Log.w("StagePipeline", "GL error after re-execution: " + glError);
            }
            
            // Pbuffer surfaces don't support reading as GL_FLOAT
            // We need to render to a float texture first, then read from that texture's framebuffer
            android.util.Log.d("StagePipeline", "Creating temporary float texture for HDR capture...");
            // Get output dimensions from the block processing
            int outWidth = mBlockProcessing.getOutWidth();
            int outHeight = mBlockProcessing.getOutHeight();
            Texture hdrTexture = TexturePool.get(
                outWidth, outHeight, 4, 
                Texture.Format.Float16);
            
            Bitmap hdrBitmap = null;
            try {
                // Render HDR output to the float texture
                android.util.Log.d("StagePipeline", "Rendering HDR output to float texture...");
                mConverter.drawBlocks(hdrTexture);
                
                // Now read from the texture's framebuffer (supports GL_FLOAT)
                android.util.Log.d("StagePipeline", "Capturing HDR output from texture framebuffer...");
                hdrBitmap = captureHdrFromTexture(hdrTexture);
                
                if (hdrBitmap != null) {
                    android.util.Log.i("StagePipeline", "Successfully captured HDR bitmap: " + 
                          hdrBitmap.getWidth() + "x" + hdrBitmap.getHeight());
                } else {
                    android.util.Log.e("StagePipeline", "HDR capture returned null bitmap");
                }
            } finally {
                hdrTexture.close();
                // Restore SDR output mode for future renders
                toneMap.setHdrOutputMode(false);
            }
            
            return hdrBitmap;
        } catch (Exception e) {
            android.util.Log.e("StagePipeline", "Failed to capture HDR output", e);
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
        
        // Allocate pixel array for one block only (avoids OutOfMemoryError on large images)
        int[] blockPixels = new int[width * BLOCK_HEIGHT];
        
        float maxHdrValue = 16.0f;
        
        // Process image in blocks and write directly to bitmap
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
                android.util.Log.e("StagePipeline", "GL error reading block from texture framebuffer: " + 
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
                
                blockPixels[i] = android.graphics.Color.rgb(rInt, gInt, bInt);
            }
            
            // Write this block directly to the bitmap (avoids large memory allocation)
            hdrBitmap.setPixels(blockPixels, 0, width, 0, y, width, blockHeight);
        }
        
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

    public static class StageMap {
        private final List<Stage> mStages;

        public StageMap(List<Stage> stages) {
            mStages = stages;
        }

        @SuppressWarnings("unchecked")
        public <T> T getStage(Class<T> cls) {
            for (Stage stage : mStages) {
                if (stage.getClass() == cls) {
                    return (T) stage;
                }
            }
            return null;
        }

        /**
         * Find a stage that implements the given interface.
         * Useful for stages that can come from either Bayer or Linear Raw pipeline.
         * Searches in reverse order to find the most recent (last) provider.
         */
        @SuppressWarnings("unchecked")
        public <T> T getStageByInterface(Class<T> interfaceClass) {
            // Search in reverse to find the most recent provider
            for (int i = mStages.size() - 1; i >= 0; i--) {
                Stage stage = mStages.get(i);
                if (interfaceClass.isInstance(stage)) {
                    // For IntermediateProvider, check if it has a valid texture
                    if (interfaceClass == IntermediateProvider.class) {
                        IntermediateProvider provider = (IntermediateProvider) stage;
                        if (provider.getIntermediate() != null) {
                            return (T) stage;
                        }
                        // Continue searching if this provider has no texture
                        continue;
                    }
                    return (T) stage;
                }
            }
            return null;
        }
        
        /**
         * Get the most recent intermediate texture from any stage that implements IntermediateProvider.
         * This is a convenience method that wraps getStageByInterface(IntermediateProvider.class).getIntermediate().
         * 
         * @return The most recent intermediate texture, or null if no provider found
         */
        public Texture getLatestIntermediate() {
            IntermediateProvider provider = getStageByInterface(IntermediateProvider.class);
            return provider != null ? provider.getIntermediate() : null;
        }
        
        /**
         * Get intermediate texture from the most recent IntermediateProvider that comes before
         * compression stages (HdrCompress, HistogramMatch, and LateExposureFusion).
         * Used when outputHDR > 0 to get uncompressed HDR data.
         * 
         * @return The intermediate texture from before compression stages, or null if not found
         */
        public Texture getIntermediateBeforeCompression() {
            // Search in reverse order, but skip compression stages
            for (int i = mStages.size() - 1; i >= 0; i--) {
                Stage stage = mStages.get(i);
                
                // Skip compression stages
                if (stage instanceof HdrCompress ||
                    stage instanceof HistogramMatch ||
                    stage instanceof LateExposureFusion) {
                    continue;
                }
                
                // Check if this stage is an IntermediateProvider with valid texture
                if (stage instanceof IntermediateProvider) {
                    IntermediateProvider provider = (IntermediateProvider) stage;
                    Texture intermediate = provider.getIntermediate();
                    if (intermediate != null) {
                        return intermediate;
                    }
                }
            }
            return null;
        }
    }
}
