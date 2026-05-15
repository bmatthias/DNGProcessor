package amirz.dngprocessor.pipeline.convert;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import amirz.dngprocessor.Preferences;
import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.params.SensorParams;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.util.HistogramMatchingUtil;

import static android.opengl.GLES20.*;

/**
 * Preprocessing stage for Linear Raw (already demosaiced RGB) data.
 * This loads 16-bit RGB data and normalizes it.
 */
public class LinearRawPreProcess extends Stage implements RgbProvider {
    private static final String TAG = "LinearRawPreProcess";
    
    private final byte[] mRaw;

    private Texture mRgbTex;
    private Texture mGainMapTex;
    private Texture mHistMatchLutTex;

    public LinearRawPreProcess(byte[] raw) {
        mRaw = raw;
    }

    public Texture getRgbTex() {
        return mRgbTex;
    }

    public int getInWidth() {
        return getSensorParams().inputWidth;
    }

    public int getInHeight() {
        return getSensorParams().inputHeight;
    }

    public Texture getGainMapTex() {
        return mGainMapTex;
    }

    @Override
    protected void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();
        SensorParams sensor = getSensorParams();

        if (mRaw == null || mRaw.length == 0) {
            throw new IllegalStateException("Raw data is null or empty");
        }

        int width = getInWidth();
        int height = getInHeight();
        int expectedSize = width * height * 3 * 2; // 3 channels, 2 bytes per channel
        
        Log.d(TAG, "Processing Linear Raw: " + width + "x" + height + 
                   ", raw data size: " + mRaw.length + ", expected: " + expectedSize);

        // Output texture for normalized RGB
        // Note: Must use 4 channels (RGBA16F) because RGB16F is not color-renderable in GLES 3.0
        mRgbTex = TexturePool.get(width, height, 4, Texture.Format.Float16);

        // Create a direct ByteBuffer for OpenGL (required for glTexImage2D)
        // glTexImage2D requires a direct buffer, not a view of a heap buffer
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(mRaw.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        directBuffer.put(mRaw);
        directBuffer.rewind();
        ShortBuffer shortBuffer = directBuffer.asShortBuffer();

        Log.d(TAG, "Created direct buffer, capacity: " + shortBuffer.capacity() + 
                   " shorts, expected: " + (width * height * 3));

        // Create 3-channel 16-bit texture from RGB data
        try (Texture rgbUITex = new Texture(width, height, 3,
                Texture.Format.UInt16, shortBuffer, GL_NEAREST)) {

            converter.setTexture("rawBuffer", rgbUITex);
            converter.seti("rawWidth", width);
            converter.seti("rawHeight", height);

            // Gain map for lens shading correction
            float[] gainMap = sensor.gainMap;
            int[] gainMapSize = sensor.gainMapSize;
            if (gainMap == null) {
                gainMap = new float[] { 1f, 1f, 1f, 1f };
                gainMapSize = new int[] { 1, 1 };
            }

            // Create direct buffer for gain map too
            FloatBuffer gainMapBuffer = ByteBuffer.allocateDirect(gainMap.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            gainMapBuffer.put(gainMap);
            gainMapBuffer.rewind();

            mGainMapTex = new Texture(gainMapSize[0], gainMapSize[1], 4, Texture.Format.Float16,
                    gainMapBuffer, GL_LINEAR);
            converter.setTexture("gainMap", mGainMapTex);

            // Black level - for Linear Raw, typically all channels have the same black level
            int[] blackLevel = sensor.blackLevelPattern;
            float bl = (blackLevel[0] + blackLevel[1] + blackLevel[2] + blackLevel[3]) / 4.0f;
            converter.setf("blackLevel", bl, bl, bl, bl);
            converter.setf("whiteLevel", sensor.whiteLevel);

            // DNG tone mapping hints
            converter.setf("linearResponseLimit", sensor.linearResponseLimit);
            // Convert baseline exposure from EV to linear multiplier: 2^EV
            float exposureMultiplier = (float) Math.pow(2.0, sensor.baselineExposure);
            converter.setf("baselineExposure", exposureMultiplier);
            converter.seti("baselineExposureCompression", getProcessParams().baselineExposureCompression);
            String compressionName = getCompressionMethodName(getProcessParams().baselineExposureCompression);
            Log.d(TAG, "LinearResponseLimit: " + sensor.linearResponseLimit +
                       ", BaselineExposure: " + sensor.baselineExposure + " EV (mult=" + exposureMultiplier + ")" +
                       ", Compression: " + compressionName + " (" + getProcessParams().baselineExposureCompression + ")");

            // Reference Preview Image for histogram matching baseline exposure compression
            // This is the embedded JPEG that shows the camera's intended rendering
            // We use it to guide baseline exposure compression to match the camera's tone mapping
            ProcessParams process = getProcessParams();
            Texture histMatchLut = HistogramMatchingUtil.buildHistogramMatchingLUT(
                    sensor.hasPreview() ? sensor.previewImage : null, process, sensor);
            
            if (histMatchLut != null) {
                mHistMatchLutTex = histMatchLut;
                converter.setTexture("histMatchLut", mHistMatchLutTex);
                converter.seti("hasReferencePreview", 1);
                Log.d(TAG, "Using histogram matching for baseline exposure compression (method 15)");
            } else {
                // Create a dummy identity texture (input = output)
                // OpenGL ES requires all declared sampler uniforms to have valid textures bound
                mHistMatchLutTex = HistogramMatchingUtil.createDummyIdentityLUT();
                converter.setTexture("histMatchLut", mHistMatchLutTex);
                converter.seti("hasReferencePreview", 0);
                if (process.useReferencePreview && !sensor.hasPreview()) {
                    Log.w(TAG, "Histogram matching requested but no preview found in DNG");
                }
            }

            converter.drawBlocks(mRgbTex);
            
            // Save debug frames if fusion method is enabled and debug saving is enabled
            if (getProcessParams().baselineExposureCompression == 11 && shouldSaveFusionDebugFrames()) {
                saveFusionDebugFrames(converter, sensor, exposureMultiplier);
            }
        }
    }
    
    private boolean shouldSaveFusionDebugFrames() {
        ProcessParams process = getProcessParams();
        return process != null && process.outputBaseName != null && 
               Preferences.global().saveExposureFusionFrames.get();
    }
    
    private void saveFusionDebugFrames(GLPrograms converter, SensorParams sensor, float exposureMultiplier) {
        ProcessParams process = getProcessParams();
        if (process == null || process.outputBaseName == null) {
            Log.w(TAG, "Cannot save fusion debug frames: output base name not set");
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
            int width = getInWidth();
            int height = getInHeight();
            
            // Recreate input texture from raw data
            ByteBuffer directBuffer = ByteBuffer.allocateDirect(mRaw.length)
                    .order(ByteOrder.LITTLE_ENDIAN);
            directBuffer.put(mRaw);
            directBuffer.rewind();
            ShortBuffer shortBuffer = directBuffer.asShortBuffer();
            
            // Create temporary textures for each compression method
            Texture reinhardTex = TexturePool.get(width, height, 4, Texture.Format.Float16);
            Texture acesTex = TexturePool.get(width, height, 4, Texture.Format.Float16);
            Texture gammaTex = TexturePool.get(width, height, 4, Texture.Format.Float16);
            
            try (Texture rgbUITex = new Texture(width, height, 3,
                    Texture.Format.UInt16, shortBuffer, GL_NEAREST)) {
                
                // Re-setup all parameters
                converter.setTexture("rawBuffer", rgbUITex);
                converter.seti("rawWidth", width);
                converter.seti("rawHeight", height);
                converter.setTexture("gainMap", mGainMapTex);
                
                int[] blackLevel = sensor.blackLevelPattern;
                float bl = (blackLevel[0] + blackLevel[1] + blackLevel[2] + blackLevel[3]) / 4.0f;
                converter.setf("blackLevel", bl, bl, bl, bl);
                converter.setf("whiteLevel", sensor.whiteLevel);
                converter.setf("linearResponseLimit", sensor.linearResponseLimit);
                converter.setf("baselineExposure", exposureMultiplier);
                
                // Apply Reinhard compression (method 1)
                converter.seti("baselineExposureCompression", 1);
                converter.drawBlocks(reinhardTex);
                
                // Apply ACES Filmic compression (method 2)
                converter.seti("baselineExposureCompression", 2);
                converter.drawBlocks(acesTex);
                
                // Apply Gamma-Based compression (method 10)
                converter.seti("baselineExposureCompression", 10);
                converter.drawBlocks(gammaTex);
                
                // Restore original compression method
                converter.seti("baselineExposureCompression", process.baselineExposureCompression);
                
                // Save the three frames
                saveRgbTextureToFile(reinhardTex, new File(outputDir, baseName + "_fusion_reinhard.png"), "Reinhard");
                saveRgbTextureToFile(acesTex, new File(outputDir, baseName + "_fusion_aces.png"), "ACES");
                saveRgbTextureToFile(gammaTex, new File(outputDir, baseName + "_fusion_gamma.png"), "Gamma");
                
                Log.i(TAG, "Saved fusion debug frames to: " + outputDir.getAbsolutePath());
            } finally {
                reinhardTex.close();
                acesTex.close();
                gammaTex.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save fusion debug frames", e);
        }
    }
    
    private void saveRgbTextureToFile(Texture texture, File file, String name) {
        try {
            int width = texture.getWidth();
            int height = texture.getHeight();
            
            // Use texture's framebuffer method
            texture.setFrameBuffer();
            
            // Read pixels row by row to avoid large memory allocation
            // Allocate buffer for one row at a time (much smaller memory footprint)
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
                
                // Convert row to ARGB
                for (int x = 0; x < width; x++) {
                    float r = rowBuffer.get(x * 4);     // R channel (linear)
                    float g = rowBuffer.get(x * 4 + 1); // G channel (linear)
                    float b = rowBuffer.get(x * 4 + 2); // B channel (linear)
                    
                    // Apply sRGB gamma encoding for proper display
                    // sRGB gamma: linear -> sRGB
                    // For values <= 0.0031308: linear * 12.92
                    // For values > 0.0031308: 1.055 * pow(linear, 1/2.4) - 0.055
                    float srgbR = r <= 0.0031308f ? r * 12.92f : (float)(1.055 * Math.pow(r, 1.0/2.4) - 0.055);
                    float srgbG = g <= 0.0031308f ? g * 12.92f : (float)(1.055 * Math.pow(g, 1.0/2.4) - 0.055);
                    float srgbB = b <= 0.0031308f ? b * 12.92f : (float)(1.055 * Math.pow(b, 1.0/2.4) - 0.055);
                    
                    // Convert from [0,1] to [0,255] and clamp
                    int ir = Math.max(0, Math.min(255, (int)(srgbR * 255.0f)));
                    int ig = Math.max(0, Math.min(255, (int)(srgbG * 255.0f)));
                    int ib = Math.max(0, Math.min(255, (int)(srgbB * 255.0f)));
                    
                    rowPixels[x] = 0xFF000000 | (ir << 16) | (ig << 8) | ib;
                }
                
                // Set row pixels in bitmap (note: glReadPixels reads bottom-to-top, so we flip)
                bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1);
            }
            
            // Save as PNG
            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            bitmap.recycle();
            
            // Cleanup - restore default framebuffer
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            
            Log.d(TAG, "Saved " + name + " fusion frame to: " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save " + name + " fusion frame", e);
        }
    }
    
    private String getCompressionMethodName(int method) {
        switch (method) {
            case 0: return "None";
            case 1: return "Reinhard";
            case 2: return "ACES Filmic";
            case 3: return "Uncharted 2";
            case 4: return "Improved Rational";
            case 5: return "Gradient Domain";
            case 6: return "Hejl-Dawson";
            case 7: return "Modified ACES";
            case 8: return "Reinhard-Jodie";
            case 9: return "Lottes";
            case 10: return "Gamma-Based";
            case 11: return "Gamma+ACES Fusion";
            case 12: return "Exposure Slider";
            case 13: return "Sigmoidal";
            case 14: return "Piecewise";
            case 15: return "Histogram Match";
            default: return "Unknown (" + method + ")";
        }
    }

    @Override
    public int getShader() {
        return R.raw.stage1_linear_preprocess_fs;
    }

    @Override
    public void close() {
        if (mHistMatchLutTex != null) {
            mHistMatchLutTex.close();
        }
    }
}

