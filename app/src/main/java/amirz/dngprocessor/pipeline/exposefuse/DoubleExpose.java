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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static android.opengl.GLES20.*;

public class DoubleExpose extends Stage {
    private static final String TAG = "DoubleExpose";
    
    private Texture mUnderexposed;
    private Texture mNormalExposure;  // Center frame - actual exposure (gamma = 1.0)
    private Texture mOverexposed;

    public Texture getUnderexposed() {
        return mUnderexposed;
    }

    public Texture getExtraHighlight() {
        // For backward compatibility, return normal exposure as "extra highlight"
        return mNormalExposure;
    }

    public Texture getOverexposed() {
        return mOverexposed;
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();
        SensorParams sensor = getSensorParams();
        
        // Read user preference for fusion method (for logging/consistency)
        // Note: DoubleExpose only creates frames; merging happens in Merge.java or EarlyExposureFusion.java
        String fusionMethod = Preferences.global().exposeFusionMethod.get();
        Log.d(TAG, "Exposure fusion method preference: " + fusionMethod);

        Texture normalExposure = previousStages.getStageByInterface(IntermediateProvider.class).getIntermediate();

        // Always use adaptive HDR fusion mode when fusion is enabled
        // The algorithm adapts exposure factors and gamma based on baseline exposure
        float baselineEV = sensor.baselineExposure;
        float baselineMult = (float) Math.pow(2.0, baselineEV);
        boolean isHDR = true;  // Always use HDR mode when fusion is enabled
        
        // Night mode detection (similar to convert_uraw script)
        // Light Value < 0 indicates a dark scene that needs special processing
        boolean isNightMode = sensor.lightValue < 0f;
        Log.d(TAG, "DoubleExpose: lightValue=" + sensor.lightValue + ", isNightMode=" + isNightMode + 
                  ", isHDR=" + isHDR + ", baselineEV=" + baselineEV);
        
        float underFactor, normalFactor, overFactor;
        float underGamma, normalGamma, overGamma;
        
        // =================================================================
        // EXPOSURE FUSION: Creates three exposure versions for blending
        // =================================================================
        // Creates three exposure versions and blends them for optimal dynamic range:
        // - Data is [0, 1] normalized to sensor saturation
        // - NOTE: Baseline exposure is already applied in PreProcess with compression curve
        //
        // Under-exposed frame: Preserves highlights (FIXED, not affected by light value)
        //   - Factor = 1.0 (original data, already has baseline exposure applied)
        //   - Gamma > 1.0 (darker, preserves highlights)
        //   - We use pow(y, gamma) where gamma > 1.0 makes darker
        //
        // Normal-exposed frame: Center frame - actual exposure (slightly pushed)
        //   - Factor = 1.0 (original data)
        //   - Gamma = 0.95 (slightly brighter mid-tones, pushed)
        //   - This is the baseline that the other frames are compared to
        //
        // Over-exposed frame: Reveals shadows (ADJUSTED by light value)
        //   - Factor = 1.0 (original data)
        //   - Gamma < 1.0 (brighter, reveals shadows)
        //   - Gamma adjusted based on light value to control shadow brightening
        //   - We use pow(y, gamma) where gamma < 1.0 makes brighter
        //
        // Merge: Uses underexposed in highlights, normal in mid-tones, overexposed in shadows
        // =================================================================
        {
            // Underexposed frame: Fixed gamma to preserve highlights
            // Always darker than original (gamma > 1.0)
            underFactor = 1.0f;
            underGamma = 1.2f;  // Fixed, always darker to preserve highlights
            
            // Normal exposure frame: Center frame - actual exposure
            // Slightly pushed mid-tones (gamma < 1.0 brightens mid-tones)
            normalFactor = 1.0f;
            normalGamma = 1.0f;  // Slightly brighter mid-tones (pushed)
            
            // Overexposed frame: Gamma adjusted based on light value
            // lightValue controls how much shadows get brightened
            // Compensation for Reinhard compression contrast loss
            overFactor = 1.0f;
            
            if (sensor.lightValue >= 0.0f) {
                // Positive light value: brighten shadows
                // Map lightValue [0, 10] to gamma [1.0, 0.6]
                // Higher light value = more shadow brightening = lower gamma
                
                // Calculate Reinhard compression factor at mid-tone
                // Reinhard: y = (x * E) / (1 + x * E)
                // At mid-tone (x=0.5): compression factor = 1 / (1 + 0.5*E)
                // This represents how much contrast is lost due to compression
                // Higher baseline exposure → more compression → less shadow brightening needed
                float reinhardCompressionFactor = 1.0f / (1.0f + 0.5f * baselineMult);
                
                // Scale the gamma adjustment by the compression factor
                // Higher compression → less shadow brightening needed to maintain contrast
                float gammaAdjustment = (sensor.lightValue / 10.0f) * reinhardCompressionFactor * 0.4f;
                overGamma = 1.0f - gammaAdjustment;
                overGamma = Math.max(0.6f, Math.min(1.0f, overGamma));
            } else if (sensor.lightValue < 0.0f) {
                // Negative light value: less shadow brightening (more conservative)
                // Map lightValue [-5, 0] to gamma [1.0, 0.8]
                // More negative = less brightening = higher gamma (closer to normal)
                float lightValueMagnitude = Math.abs(sensor.lightValue);
                overGamma = 1.0f - (lightValueMagnitude / 5.0f) * 0.2f;
                overGamma = Math.max(0.8f, Math.min(1.0f, overGamma));
            } else {
                // Light value = 0: slight brightening for shadows
                overGamma = 1.0f;
            }
            
            Log.d(TAG, String.format("3-frame fusion mode: LV=%.1f, " +
                    "under=(%.2f, γ=%.2f, fixed), normal=(%.2f, γ=%.2f, center), over=(%.2f, γ=%.2f, adjusted)%s",
                    sensor.lightValue,
                    underFactor, underGamma,
                    normalFactor, normalGamma,
                    overFactor, overGamma,
                    isNightMode ? " [NIGHT MODE]" : ""));
        }
        
        // Create underexposed frame (Highlights - darker, preserves highlights)
        mUnderexposed = TexturePool.get(normalExposure.getWidth(), normalExposure.getHeight(), 1,
                Texture.Format.Float16);
        converter.setTexture("buf", normalExposure);
        converter.setf("factor", underFactor);
        converter.setf("gamma", underGamma);
        converter.seti("isHDR", isHDR ? 1 : 0);
        converter.drawBlocks(mUnderexposed);

        // Create normal exposure frame (Center - actual exposure, gamma = 1.0)
        mNormalExposure = TexturePool.get(mUnderexposed);
        converter.setTexture("buf", normalExposure);
        converter.setf("factor", normalFactor);
        converter.setf("gamma", normalGamma);
        converter.seti("isHDR", isHDR ? 1 : 0);
        converter.drawBlocks(mNormalExposure);

        // Create overexposed frame (Shadows - brighter, reveals shadows)
        mOverexposed = TexturePool.get(mUnderexposed);
        converter.setTexture("buf", normalExposure);
        converter.setf("factor", overFactor);
        converter.setf("gamma", overGamma);
        converter.seti("isHDR", isHDR ? 1 : 0);
        converter.drawBlocks(mOverexposed);
        
        // Save exposure frames to files if enabled in preferences
        if (shouldSaveExposureFusionFrames()) {
            saveExposureFusionFrames();
        }
    }
    
    private boolean shouldSaveExposureFusionFrames() {
        // Check if preference is enabled and we have an output base name
        ProcessParams process = getProcessParams();
        return process != null && process.outputBaseName != null && 
               Preferences.global().saveExposureFusionFrames.get();
    }
    
    private void saveExposureFusionFrames() {
        ProcessParams process = getProcessParams();
        if (process == null || process.outputBaseName == null) {
            Log.w(TAG, "Cannot save exposure fusion frames: output base name not set");
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
            saveTextureToFile(mUnderexposed, new File(outputDir, baseName + "_exposure_under.png"), "Underexposed");
            saveTextureToFile(mNormalExposure, new File(outputDir, baseName + "_exposure_normal.png"), "NormalExposure");
            saveTextureToFile(mOverexposed, new File(outputDir, baseName + "_exposure_over.png"), "Overexposed");
            
            Log.i(TAG, "Saved exposure fusion frames to: " + outputDir.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save exposure fusion frames", e);
        }
    }
    
    private void saveTextureToFile(Texture texture, File file, String name) {
        try {
            int width = texture.getWidth();
            int height = texture.getHeight();
            
            // Use texture's framebuffer method
            texture.setFrameBuffer();
            
            // Read pixels as float (texture is Float16, single channel)
            // Read as GL_RGBA for compatibility (single channel will be in R, G=B=A=0)
            FloatBuffer floatBuffer = ByteBuffer.allocateDirect(width * height * 4 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, floatBuffer);
            
            // Convert to ARGB bitmap
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            floatBuffer.rewind();
            int[] pixels = new int[width * height];
            for (int i = 0; i < pixels.length; i++) {
                // Read Y channel (luminance) from R channel - texture is single channel, linear
                float y = floatBuffer.get(i * 4);  // R channel (linear)
                
                // Apply sRGB gamma encoding for proper display
                // sRGB gamma: linear -> sRGB
                // For values <= 0.0031308: linear * 12.92
                // For values > 0.0031308: 1.055 * pow(linear, 1/2.4) - 0.055
                float srgbY = y <= 0.0031308f ? y * 12.92f : (float)(1.055 * Math.pow(y, 1.0/2.4) - 0.055);
                
                // Convert from [0,1] to [0,255] and clamp
                int gray = Math.max(0, Math.min(255, (int)(srgbY * 255.0f)));
                pixels[i] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            
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

    @Override
    public int getShader() {
        return R.raw.stage4_1_doubleexpose;
    }

    @Override
    public boolean isEnabled() {
        ProcessParams process = getProcessParams();
        // Run if exposeFuse toggle is enabled (independent of other stages)
        // Note: This stage is not added to pipeline if EarlyExposureFusion is added
        return process != null && process.exposeFuse;
    }
}
