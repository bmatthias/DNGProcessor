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
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;
import amirz.dngprocessor.pipeline.convert.RgbProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static android.opengl.GLES20.*;

/**
 * Late exposure fusion stage that runs on intermediate (xyY) data in stage3 (post-processing).
 * 
 * APPROACH:
 * 1. Convert xyY to RGB (xyY => XYZ => ProPhoto RGB)
 * 2. Extract luma from RGB, create 3 tone-mapped luma frames
 * 3. Merge luma frames using Mertens weights (reuses stage4_5_merge_3frame)
 * 4. Scale original RGB by (mergedLuma / originalLuma)
 * 
 * WHY THIS WORKS:
 * - Fusion happens in luminance space (preserves highlights better than RGB fusion)
 * - Original RGB color ratios are preserved (just scaled)
 * - Replaces the xyY => RGB conversion step in ToneMap, avoiding banding from Y/y division
 * 
 * This is used when hdrCompressionMethod == 5 (Late Exposure Fusion method).
 * It runs as the final tone mapping step in stage3, replacing both HDR compression and xyY => RGB conversion.
 */
public class LateExposureFusion extends Stage implements RgbProvider, IntermediateProvider {
    private static final String TAG = "LateExposureFusion";
    
    // Mertens weight exponents (match Merge.java)
    private static final float EXPONENT_CONTRAST = 1.0f;
    private static final float EXPONENT_SATURATION = 1.0f;
    private static final float EXPONENT_EXPOSURE = 1.0f;
    
    private Texture mRgbOutput;  // Fused RGB output (for display, null if skipRgbConversion)
    private Texture mIntermediateOutput;  // Fused xyY output (when skipRgbConversion is true)
    private Texture mPlainRgbOutput;  // Plain xyY => RGB conversion (for gain map)
    private boolean mFusionApplied = false;
    
    private float[] mXYZtoProPhoto;
    private boolean mSkipRgbConversion;  // If true, skip xyY => RGB conversion (HistogramMatch will do it)

    public LateExposureFusion(float[] XYZtoProPhoto, boolean skipRgbConversion) {
        mXYZtoProPhoto = XYZtoProPhoto;
        mSkipRgbConversion = skipRgbConversion;
    }

    @Override
    public Texture getRgbTex() {
        return mRgbOutput;
    }
    
    @Override
    public Texture getIntermediate() {
        return mIntermediateOutput;
    }
    
    /**
     * Get plain RGB conversion (without fusion) for UHDR gain map creation
     */
    public Texture getPlainRgbTex() {
        return mPlainRgbOutput;
    }
    
    /**
     * Check if fusion was actually applied (used by ToneMap for gamma compensation)
     */
    public boolean isFusionApplied() {
        return mFusionApplied;
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        Log.d(TAG, "=== LateExposureFusion.execute() START ===");
        Log.d(TAG, "LateExposureFusion.execute() - Intermediate input, luma-space fusion");
        GLPrograms converter = getConverter();
        SensorParams sensor = getSensorParams();
        ProcessParams process = getProcessParams();

        // Get intermediate texture from previous stage
        IntermediateProvider intermediateProvider = previousStages.getStageByInterface(IntermediateProvider.class);
        if (intermediateProvider == null) {
            Log.e(TAG, "No intermediate provider available");
            return;
        }
        
        Texture intermediateInput = intermediateProvider.getIntermediate();
        if (intermediateInput == null) {
            Log.e(TAG, "No intermediate texture available");
            return;
        }
        
        Log.d(TAG, "Intermediate input: " + intermediateInput.getWidth() + "x" + intermediateInput.getHeight() + 
                ", channels=" + intermediateInput.getChannels() + ", format=" + intermediateInput.getFormat());
        
        float baselineEV = sensor.baselineExposure;
        float baselineMult = (float) Math.pow(2.0, baselineEV);
        
        Log.d(TAG, String.format("baselineEV=%.4f, baselineMult=%.4f", baselineEV, baselineMult));
        
        if (baselineMult <= 0.0f || Float.isNaN(baselineMult) || Float.isInfinite(baselineMult)) {
            Log.e(TAG, "Invalid baselineMult=" + baselineMult);
            return;
        }
        
        // Check debug frame saving preference early for logging
        boolean debugEnabled = Preferences.global().saveExposureFusionFrames.get();
        Log.d(TAG, "Debug frame saving preference: " + debugEnabled);
        if (process != null) {
            Log.d(TAG, "Process outputBaseName: " + (process.outputBaseName != null ? process.outputBaseName : "null"));
        } else {
            Log.d(TAG, "Process is null");
        }
        
        // Execute luma-space Mertens fusion
        executeLumaFusion(converter, intermediateInput, baselineMult);
        mFusionApplied = true;
        Log.d(TAG, "LateExposureFusion.execute() completed, fusionApplied=" + mFusionApplied);
    }
    
    /**
     * Luma-space Mertens fusion in xyY space:
     * 1. Extract luma (Y) from xyY, create 3 tone-mapped luma frames
     * 2. Blend luma using Mertens weights (reuse stage4_5_merge_3frame.glsl)
     * 3. Convert xyY to RGB (xyY => XYZ => ProPhoto RGB)
     * 4. Scale RGB by (mergedLuma / originalLuma)
     * 5. Also create plain xyY => RGB conversion (for gain map)
     * 
     * Frame roles:
     * - Frame 0 (Under): Exponential with 0.5x exposure (preserves highlights)
     * - Frame 1 (Center): Exponential with 1.0x exposure (balanced)
     * - Frame 2 (Over): Exponential with 2x exposure (reveals shadows)
     */
    private void executeLumaFusion(GLPrograms converter, Texture intermediateInput, float baselineExposure) {
        Log.d(TAG, "Executing luma-space Mertens fusion in xyY space");
        Log.d(TAG, String.format("Frame strategy: under=Exp(0.5x), center=Exp(1.0x), over=Exp(2x) (exposure=%.2f)", 
                baselineExposure));
        
        // Log debug frame saving status
        boolean debugEnabled = Preferences.global().saveExposureFusionFrames.get();
        ProcessParams process = getProcessParams();
        Log.d(TAG, "executeLumaFusion: debugEnabled=" + debugEnabled + 
                   ", process=" + (process != null ? "not null" : "null") +
                   ", outputBaseName=" + (process != null && process.outputBaseName != null ? process.outputBaseName : "null"));
        
        int width = intermediateInput.getWidth();
        int height = intermediateInput.getHeight();
        
        // =========================================================================
        // Step 1: Create plain xyY => RGB conversion (for gain map)
        // =========================================================================
        mPlainRgbOutput = TexturePool.get(width, height, 4, Texture.Format.Float16);
        
        converter.useProgram(R.raw.late_fusion_xyy_to_rgb);
        converter.setTexture("xyYInput", intermediateInput);
        converter.setf("XYZtoProPhoto", mXYZtoProPhoto);
        converter.setf("baselineExposure", baselineExposure);
        converter.drawBlocks(mPlainRgbOutput);
        Log.d(TAG, "Created plain xyY => RGB conversion (for gain map)");
        
        // =========================================================================
        // Step 2: Create 3 tone-mapped luma frames from xyY (single-channel)
        // =========================================================================
        Texture underFrame = TexturePool.get(width, height, 1, Texture.Format.Float16);
        Texture centerFrame = TexturePool.get(width, height, 1, Texture.Format.Float16);
        Texture overFrame = TexturePool.get(width, height, 1, Texture.Format.Float16);
        
        converter.useProgram(R.raw.late_fusion_extract_luma);
        converter.setTexture("xyYInput", intermediateInput);
        converter.setf("baselineExposure", baselineExposure);
        
        // Frame 0: Gamma Based - Very low contrast, perfect highlight preservation (under)
        converter.seti("frameType", 0);
        converter.drawBlocks(underFrame);
        Log.d(TAG, "Created under frame (Gamma Based - very low contrast, perfect highlights)");
        
        // Frame 1: Reinhard - Low contrast, somewhat preserved highlights (center)
        converter.seti("frameType", 1);
        converter.drawBlocks(centerFrame);
        Log.d(TAG, "Created center frame (Reinhard - low contrast, preserved highlights)");
        
        // Frame 2: ACES Filmic Soft - Great contrast, clipped highlights (over)
        converter.seti("frameType", 2);
        converter.drawBlocks(overFrame);
        Log.d(TAG, "Created over frame (ACES Filmic Soft - great contrast, clipped highlights)");
        
        // =========================================================================
        // Step 3: Blend luma using Mertens weights (reuse stage4_5_merge_3frame.glsl)
        // =========================================================================
        Texture mergedLuma = TexturePool.get(width, height, 1, Texture.Format.Float16);
        
        converter.useProgram(R.raw.stage4_5_merge_3frame);
        converter.setTexture("frameUnder", underFrame);
        converter.setTexture("frameNormal", centerFrame);
        converter.setTexture("frameOver", overFrame);
        converter.setTexture("originalInput", intermediateInput);  // For saturation weight (uses xyY)
        
        // Pass texture size for edge clamping in contrast computation
        converter.seti("texSize", width - 1, height - 1);
        
        // Pass Mertens exponents
        converter.setf("wExponentContrast", EXPONENT_CONTRAST);
        converter.setf("wExponentSaturation", EXPONENT_SATURATION);
        converter.setf("wExponentExposure", EXPONENT_EXPOSURE);
        
        // Respect user preference for fusion method
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
        // Step 4: Create center tone-mapped RGB frame (stable source)
        // This avoids division/multiplication chain by using already tone-mapped RGB
        // =========================================================================
        Texture centerRgb = TexturePool.get(width, height, 4, Texture.Format.Float16);
        
        converter.useProgram(R.raw.late_fusion_create_center_rgb);
        converter.setTexture("rgbInput", mPlainRgbOutput);  // Use plain RGB conversion
        converter.setf("baselineExposure", baselineExposure);
        converter.drawBlocks(centerRgb);
        Log.d(TAG, "Created center tone-mapped RGB frame (stable source)");
        
        // =========================================================================
        // Step 5: Reconstruct RGB or xyY from original xyY chromaticity and merged luma
        // This preserves color saturation by using original chromaticity instead of
        // scaling tone-mapped RGB, which can lose saturation
        // =========================================================================
        if (mSkipRgbConversion) {
            // Output xyY (HistogramMatch will do xyY => RGB conversion)
            mIntermediateOutput = TexturePool.get(width, height, 4, Texture.Format.Float16);
            
            converter.useProgram(R.raw.late_fusion_apply_luma_to_xyy);
            converter.setTexture("xyYInput", intermediateInput);  // Original xyY for chromaticity
            converter.setTexture("mergedLuma", mergedLuma);
            converter.setf("baselineExposure", baselineExposure);
            
            converter.drawBlocks(mIntermediateOutput);
            Log.d(TAG, "Reconstructed xyY from original xyY chromaticity and merged luma (HistogramMatch will convert to RGB)");
        } else {
            // Output RGB directly
            mRgbOutput = TexturePool.get(width, height, 4, Texture.Format.Float16);
            
            converter.useProgram(R.raw.late_fusion_apply_luma_to_center_rgb);
            converter.setTexture("xyYInput", intermediateInput);  // Original xyY for chromaticity
            converter.setTexture("mergedLuma", mergedLuma);
            converter.setf("XYZtoProPhoto", mXYZtoProPhoto);
            
            converter.drawBlocks(mRgbOutput);
            Log.d(TAG, "Reconstructed RGB from original xyY chromaticity and merged luma (preserves color)");
        }
        
        // Cleanup center frame
        centerRgb.close();
        
        // Save debug frames if enabled
        if (shouldSaveDebugFrames()) {
            Log.d(TAG, "Saving debug frames...");
            // Recreate centerRgb for saving (it was closed above)
            Texture centerRgbForDebug = TexturePool.get(width, height, 4, Texture.Format.Float16);
            converter.useProgram(R.raw.late_fusion_create_center_rgb);
            converter.setTexture("rgbInput", mPlainRgbOutput);
            converter.setf("baselineExposure", baselineExposure);
            converter.drawBlocks(centerRgbForDebug);
            
            // Use RGB output if available, otherwise use intermediate (xyY) output
            Texture finalOutput = mRgbOutput != null ? mRgbOutput : mIntermediateOutput;
            saveDebugFrames(underFrame, centerFrame, overFrame, mergedLuma, mPlainRgbOutput, centerRgbForDebug, finalOutput);
            
            centerRgbForDebug.close();
        } else {
            Log.d(TAG, "Debug frames not saved - check shouldSaveDebugFrames() logs above");
        }
        
        // Cleanup
        underFrame.close();
        centerFrame.close();
        overFrame.close();
        mergedLuma.close();
    }
    
    private boolean shouldSaveDebugFrames() {
        ProcessParams process = getProcessParams();
        boolean prefEnabled = Preferences.global().saveExposureFusionFrames.get();
        boolean hasProcess = process != null;
        boolean hasBaseName = process != null && process.outputBaseName != null;
        
        Log.d(TAG, "shouldSaveDebugFrames: prefEnabled=" + prefEnabled + 
                   ", hasProcess=" + hasProcess + ", hasBaseName=" + hasBaseName);
        
        if (hasProcess && process.outputBaseName != null) {
            Log.d(TAG, "outputBaseName: " + process.outputBaseName);
        }
        
        return hasProcess && hasBaseName && prefEnabled;
    }
    
    private void saveDebugFrames(Texture underFrame, Texture centerFrame, Texture overFrame, 
                                  Texture mergedLuma, Texture plainRgb, Texture centerRgb, Texture finalOutput) {
        ProcessParams process = getProcessParams();
        if (process == null || process.outputBaseName == null) {
            Log.w(TAG, "Cannot save debug frames: process=" + (process != null ? "not null" : "null") + 
                       ", outputBaseName=" + (process != null && process.outputBaseName != null ? process.outputBaseName : "null"));
            return;
        }
        
        try {
            // Get output directory from save path preference
            Preferences pref = Preferences.global();
            String savePath = pref.savePath.get();
            String savePathDir = android.os.Environment.getExternalStorageDirectory().toString() + 
                                File.separator + savePath;
            File outputDir = new File(savePathDir);
            Log.d(TAG, "Saving debug frames to: " + savePathDir);
            
            if (!outputDir.exists()) {
                boolean created = outputDir.mkdirs();
                Log.d(TAG, "Created output directory: " + created);
            }
            
            String baseName = process.outputBaseName;
            Log.d(TAG, "Base name: " + baseName);
            
            // Save the luma frames (single-channel)
            saveLumaTextureToFile(underFrame, new File(outputDir, baseName + "_late_fusion_under.png"), "Underexposed (Exp 0.5x - highlights)");
            saveLumaTextureToFile(centerFrame, new File(outputDir, baseName + "_late_fusion_center.png"), "Center (Exp 1.0x - balanced)");
            saveLumaTextureToFile(overFrame, new File(outputDir, baseName + "_late_fusion_over.png"), "Overexposed (Exp 2x - shadows)");
            saveLumaTextureToFile(mergedLuma, new File(outputDir, baseName + "_late_fusion_merged_luma.png"), "Merged Luma");
            
            // Save the plain RGB conversion (for gain map)
            saveRgbTextureToFile(plainRgb, new File(outputDir, baseName + "_late_fusion_plain_rgb.png"), "Plain xyY => RGB (for gain map)");
            
            // Save the center tone-mapped RGB frame (stable source)
            saveRgbTextureToFile(centerRgb, new File(outputDir, baseName + "_late_fusion_center_rgb.png"), "Center Tone-Mapped RGB (stable source)");
            
            // Save the final fused RGB output
            saveRgbTextureToFile(finalOutput, new File(outputDir, baseName + "_late_fusion_fused_rgb.png"), "Fused RGB Output (with merged luma applied)");
            
            Log.i(TAG, "Successfully saved late fusion debug frames to: " + outputDir.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save debug frames", e);
            e.printStackTrace();
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
                    
                    // Normalize HDR values for display (simple Reinhard tone mapping)
                    // This prevents HDR values from appearing too bright in saved images
                    float maxChannel = Math.max(Math.max(r, g), b);
                    if (maxChannel > 1.0f) {
                        // Apply simple tone mapping: x / (1 + x) for values > 1.0
                        float toneMapFactor = maxChannel / (1.0f + maxChannel);
                        r = r * toneMapFactor / maxChannel;
                        g = g * toneMapFactor / maxChannel;
                        b = b * toneMapFactor / maxChannel;
                    }
                    
                    // Clamp to [0, 1] before gamma encoding
                    r = Math.max(0.0f, Math.min(1.0f, r));
                    g = Math.max(0.0f, Math.min(1.0f, g));
                    b = Math.max(0.0f, Math.min(1.0f, b));
                    
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
        return R.raw.late_fusion_extract_luma;
    }

    @Override
    public void close() {
        if (mRgbOutput != null) {
            mRgbOutput.close();
        }
        if (mIntermediateOutput != null) {
            mIntermediateOutput.close();
        }
        if (mPlainRgbOutput != null) {
            mPlainRgbOutput.close();
        }
    }
}
