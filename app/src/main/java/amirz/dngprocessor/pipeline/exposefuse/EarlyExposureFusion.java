package amirz.dngprocessor.pipeline.exposefuse;

import android.graphics.Bitmap;
import android.util.Log;

import amirz.dngprocessor.Preferences;
import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.params.SensorParams;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.RgbProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static android.opengl.GLES20.*;

/**
 * Early exposure fusion stage that runs on RGB data (before xyY conversion).
 * 
 * APPROACH:
 * 1. Extract luma from RGB, create 3 tone-mapped luma frames
 * 2. Merge luma frames using Mertens weights (reuses stage4_5_merge_3frame)
 * 3. Scale original RGB by (mergedLuma / originalLuma)
 * 
 * WHY THIS WORKS:
 * - Fusion happens in luminance space (preserves highlights better than RGB fusion)
 * - Original RGB color ratios are preserved (just scaled)
 * - No xyY→RGB conversion = no banding from Y/y division
 * 
 * This is used when baselineExposureCompression == 17 (Exposure Fusion method).
 */
public class EarlyExposureFusion extends Stage implements RgbProvider {
    private static final String TAG = "EarlyExposureFusion";
    
    // Mertens weight exponents (match Merge.java)
    private static final float EXPONENT_CONTRAST = 1.0f;
    private static final float EXPONENT_SATURATION = 1.0f;
    private static final float EXPONENT_EXPOSURE = 1.0f;
    
    private Texture mRgbOutput;
    private boolean mFusionApplied = false;

    @Override
    public Texture getRgbTex() {
        return mRgbOutput;
    }
    
    /**
     * Check if fusion was actually applied (used by ToneMap for gamma compensation)
     */
    public boolean isFusionApplied() {
        return mFusionApplied;
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        Log.d(TAG, "EarlyExposureFusion.execute() - RGB input, luma-space fusion");
        GLPrograms converter = getConverter();
        SensorParams sensor = getSensorParams();
        ProcessParams process = getProcessParams();

        // Get RGB texture from previous stage (LinearRawPreProcess)
        RgbProvider rgbProvider = previousStages.getStageByInterface(RgbProvider.class);
        if (rgbProvider == null) {
            Log.e(TAG, "No RGB provider available");
            return;
        }
        
        Texture rgbInput = rgbProvider.getRgbTex();
        if (rgbInput == null) {
            Log.e(TAG, "No RGB texture available");
            return;
        }
        
        Log.d(TAG, "RGB input: " + rgbInput.getWidth() + "x" + rgbInput.getHeight() + 
                ", channels=" + rgbInput.getChannels() + ", format=" + rgbInput.getFormat());
        
        float baselineEV = sensor.baselineExposure;
        float baselineMult = (float) Math.pow(2.0, baselineEV);
        
        Log.d(TAG, String.format("baselineEV=%.4f, baselineMult=%.4f", baselineEV, baselineMult));
        
        if (baselineMult <= 0.0f || Float.isNaN(baselineMult) || Float.isInfinite(baselineMult)) {
            Log.e(TAG, "Invalid baselineMult=" + baselineMult);
            return;
        }
        
        // Execute luma-space Mertens fusion
        executeLumaFusion(converter, rgbInput, baselineMult);
        mFusionApplied = true;
    }
    
    /**
     * Luma-space Mertens fusion:
     * 1. Extract luma from RGB, create 3 tone-mapped luma frames
     * 2. Blend luma using Mertens weights (reuse stage4_5_merge_3frame.glsl)
     * 3. Scale original RGB by (mergedLuma / originalLuma)
     * 
     * Frame roles:
     * - Frame 0 (Under): Exponential with 0.5x exposure (preserves highlights)
     * - Frame 1 (Center): Reinhard (balanced midtones)
     * - Frame 2 (Over): Exponential with 2x exposure (reveals shadows)
     */
    private void executeLumaFusion(GLPrograms converter, Texture rgbInput, float baselineExposure) {
        Log.d(TAG, "Executing luma-space Mertens fusion");
        Log.d(TAG, String.format("Frame strategy: under=Exp(0.5x), center=Reinhard, over=Exp(2x) (exposure=%.2f)", 
                baselineExposure));
        
        int width = rgbInput.getWidth();
        int height = rgbInput.getHeight();
        
        // =========================================================================
        // Step 1: Create 3 tone-mapped luma frames (single-channel)
        // =========================================================================
        Texture underFrame = TexturePool.get(width, height, 1, Texture.Format.Float16);
        Texture centerFrame = TexturePool.get(width, height, 1, Texture.Format.Float16);
        Texture overFrame = TexturePool.get(width, height, 1, Texture.Format.Float16);
        
        converter.useProgram(R.raw.early_fusion_extract_luma);
        converter.setTexture("rgbInput", rgbInput);
        converter.setf("baselineExposure", baselineExposure);
        
        // Frame 0: Under - Exponential with lower exposure (preserves highlights)
        converter.seti("frameType", 0);
        converter.drawBlocks(underFrame);
        Log.d(TAG, String.format("Created under frame (Exponential @ %.2fx - highlights)", baselineExposure * 0.5f));
        
        // Frame 1: Center - Reinhard (balanced midtones)
        converter.seti("frameType", 1);
        converter.drawBlocks(centerFrame);
        Log.d(TAG, String.format("Created center frame (Reinhard @ %.2fx - midtones)", baselineExposure));
        
        // Frame 2: Over - Exponential with higher exposure (reveals shadows)
        converter.seti("frameType", 2);
        converter.drawBlocks(overFrame);
        Log.d(TAG, String.format("Created over frame (Exponential @ %.2fx - shadows)", baselineExposure * 2.0f));
        
        // =========================================================================
        // Step 2: Blend luma using Mertens weights (reuse stage4_5_merge_3frame.glsl)
        // Note: stage4_5_merge_3frame expects xyY for saturation, but we pass RGB.
        // The shader will compute saturation from whatever 3-channel texture we provide.
        // =========================================================================
        Texture mergedLuma = TexturePool.get(width, height, 1, Texture.Format.Float16);
        
        converter.useProgram(R.raw.stage4_5_merge_3frame);
        converter.setTexture("frameUnder", underFrame);
        converter.setTexture("frameNormal", centerFrame);
        converter.setTexture("frameOver", overFrame);
        converter.setTexture("originalInput", rgbInput);  // For saturation weight (uses max-min of RGB)
        
        // Pass texture size for edge clamping in contrast computation
        converter.seti("texSize", width - 1, height - 1);
        
        // Pass Mertens exponents
        converter.setf("wExponentContrast", EXPONENT_CONTRAST);
        converter.setf("wExponentSaturation", EXPONENT_SATURATION);
        converter.setf("wExponentExposure", EXPONENT_EXPOSURE);
        
        // Respect user preference for fusion method
        // "laplacian" -> fullmertens (fallback, EarlyExposureFusion doesn't support Laplacian pyramids)
        // "fullmertens" -> full Mertens (contrast + saturation + well-exposedness)
        // "mertens" -> simple Mertens (well-exposedness only)
        String fusionMethod = Preferences.global().exposeFusionMethod.get();
        int useFullMertens;
        if ("mertens".equals(fusionMethod)) {
            useFullMertens = 0;  // Simple Mertens (well-exposedness only)
        } else {
            useFullMertens = 1;  // Full Mertens (or Laplacian fallback)
        }
        converter.seti("useFullMertens", useFullMertens);
        Log.d(TAG, "Using fusion method: " + fusionMethod + " (useFullMertens=" + useFullMertens + ")");
        
        converter.drawBlocks(mergedLuma);
        Log.d(TAG, "Merged luma frames using Mertens weights");
        
        // =========================================================================
        // Step 3: Scale original RGB by (mergedLuma / originalLuma)
        // =========================================================================
        mRgbOutput = TexturePool.get(width, height, 4, Texture.Format.Float16);
        
        converter.useProgram(R.raw.early_fusion_apply_luma);
        converter.setTexture("rgbInput", rgbInput);
        converter.setTexture("mergedLuma", mergedLuma);
        
        // Debug mode: 0 = normal, 1 = show scale factor, 2 = show originalLuma, 3 = show quantization steps
        // Set to 0 for normal operation, or use debug modes to visualize quantization
        converter.seti("debugMode", 3);
        
        converter.drawBlocks(mRgbOutput);
        Log.d(TAG, "Applied merged luma to original RGB (preserves color ratios)");
        
        // Save debug frames if enabled
        if (shouldSaveDebugFrames()) {
            saveDebugFrames(underFrame, centerFrame, overFrame, mergedLuma, mRgbOutput);
        }
        
        // Cleanup
        underFrame.close();
        centerFrame.close();
        overFrame.close();
        mergedLuma.close();
    }
    
    private boolean shouldSaveDebugFrames() {
        ProcessParams process = getProcessParams();
        return process != null && process.outputBaseName != null && 
               Preferences.global().saveExposureFusionFrames.get();
    }
    
    private void saveDebugFrames(Texture underFrame, Texture centerFrame, Texture overFrame, 
                                  Texture mergedLuma, Texture finalOutput) {
        ProcessParams process = getProcessParams();
        if (process == null || process.outputBaseName == null) {
            Log.w(TAG, "Cannot save debug frames: output base name not set");
            return;
        }
        
        try {
            // Get output directory from save path preference
            Preferences pref = Preferences.global();
            String savePathDir = android.os.Environment.getExternalStorageDirectory().toString() + 
                                File.separator + pref.savePath.get();
            File outputDir = new File(savePathDir);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            String baseName = process.outputBaseName;
            
            // Save the luma frames (single-channel)
            saveLumaTextureToFile(underFrame, new File(outputDir, baseName + "_early_fusion_under.png"), "Underexposed (Exp 0.5x - highlights)");
            saveLumaTextureToFile(centerFrame, new File(outputDir, baseName + "_early_fusion_center.png"), "Center (Reinhard - midtones)");
            saveLumaTextureToFile(overFrame, new File(outputDir, baseName + "_early_fusion_over.png"), "Overexposed (Exp 2x - shadows)");
            saveLumaTextureToFile(mergedLuma, new File(outputDir, baseName + "_early_fusion_merged_luma.png"), "Merged Luma");
            
            // Save the final RGB output
            saveRgbTextureToFile(finalOutput, new File(outputDir, baseName + "_early_fusion_rgb_output.png"), "Final RGB Output");
            
            Log.i(TAG, "Saved early fusion debug frames to: " + outputDir.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save debug frames", e);
        }
    }
    
    private void saveLumaTextureToFile(Texture texture, File file, String name) {
        try {
            int width = texture.getWidth();
            int height = texture.getHeight();
            
            // Use texture's framebuffer method
            texture.setFrameBuffer();
            
            // Read pixels row by row to avoid large memory allocation
            int rowSize = width * 4; // 4 channels (RGBA)
            FloatBuffer rowBuffer = ByteBuffer.allocateDirect(rowSize * 4) // 4 bytes per float
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            
            // Convert to ARGB bitmap
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] rowPixels = new int[width];
            
            // Read and convert row by row
            for (int y = 0; y < height; y++) {
                rowBuffer.rewind();
                glReadPixels(0, height - 1 - y, width, 1, GL_RGBA, GL_FLOAT, rowBuffer);
                rowBuffer.rewind();
                
                // Convert row to ARGB (grayscale from single-channel luma)
                for (int x = 0; x < width; x++) {
                    float luma = rowBuffer.get(x * 4);  // R channel (luma in single-channel texture)
                    
                    // Apply sRGB gamma encoding for proper display
                    float srgbLuma = luma <= 0.0031308f ? luma * 12.92f : (float)(1.055 * Math.pow(luma, 1.0/2.4) - 0.055);
                    
                    // Convert from [0,1] to [0,255] and clamp
                    int gray = Math.max(0, Math.min(255, (int)(srgbLuma * 255.0f)));
                    
                    rowPixels[x] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
                }
                
                // Set row pixels in bitmap
                bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1);
            }
            
            // Save as PNG
            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            bitmap.recycle();
            
            // Cleanup - restore default framebuffer
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            
            Log.d(TAG, "Saved " + name + " frame to: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save " + name + " frame", e);
        }
    }
    
    private void saveRgbTextureToFile(Texture texture, File file, String name) {
        try {
            int width = texture.getWidth();
            int height = texture.getHeight();
            
            texture.setFrameBuffer();
            
            int rowSize = width * 4;
            FloatBuffer rowBuffer = ByteBuffer.allocateDirect(rowSize * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] rowPixels = new int[width];
            
            for (int y = 0; y < height; y++) {
                rowBuffer.rewind();
                glReadPixels(0, height - 1 - y, width, 1, GL_RGBA, GL_FLOAT, rowBuffer);
                rowBuffer.rewind();
                
                for (int x = 0; x < width; x++) {
                    float r = rowBuffer.get(x * 4);
                    float g = rowBuffer.get(x * 4 + 1);
                    float b = rowBuffer.get(x * 4 + 2);
                    
                    // Apply sRGB gamma encoding
                    float srgbR = r <= 0.0031308f ? r * 12.92f : (float)(1.055 * Math.pow(r, 1.0/2.4) - 0.055);
                    float srgbG = g <= 0.0031308f ? g * 12.92f : (float)(1.055 * Math.pow(g, 1.0/2.4) - 0.055);
                    float srgbB = b <= 0.0031308f ? b * 12.92f : (float)(1.055 * Math.pow(b, 1.0/2.4) - 0.055);
                    
                    int ir = Math.max(0, Math.min(255, (int)(srgbR * 255.0f)));
                    int ig = Math.max(0, Math.min(255, (int)(srgbG * 255.0f)));
                    int ib = Math.max(0, Math.min(255, (int)(srgbB * 255.0f)));
                    
                    rowPixels[x] = 0xFF000000 | (ir << 16) | (ig << 8) | ib;
                }
                
                bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1);
            }
            
            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            bitmap.recycle();
            
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            
            Log.d(TAG, "Saved " + name + " frame to: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save " + name + " frame", e);
        }
    }

    @Override
    public int getShader() {
        // Return first shader we use (pipeline requires a valid ID)
        // We handle all shader switching internally in execute()
        return R.raw.early_fusion_extract_luma;
    }

    @Override
    public void close() {
        if (mRgbOutput != null) {
            mRgbOutput.close();
        }
    }
}
