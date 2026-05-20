package amirz.dngprocessor.pipeline.convert;

import android.util.Log;

import java.io.File;
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

public class PreProcess extends Stage implements BayerProvider {
    private static final String TAG = "PreProcess";
    
    private final byte[] mRaw;

    private Texture mSensorTex, mGainMapTex, mHistMatchLutTex;

    public PreProcess(byte[] raw) {
        mRaw = raw;
    }

    public Texture getSensorTex() {
        return mSensorTex;
    }

    public int getInWidth() {
        return getSensorParams().inputWidth;
    }

    public int getInHeight() {
        return getSensorParams().inputHeight;
    }

    public int getCfaPattern() {
        return getSensorParams().cfa;
    }

    public Texture getGainMapTex() {
        return mGainMapTex;
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();
        SensorParams sensor = getSensorParams();

        // First texture is just for normalization
        mSensorTex = TexturePool.get(getInWidth(), getInHeight(), 1,
                Texture.Format.Float16);

        try (Texture sensorUITex = TexturePool.get(getInWidth(), getInHeight(), 1,
                Texture.Format.UInt16)) {
            sensorUITex.setPixels(mRaw);

            converter.setTexture("rawBuffer", sensorUITex);
            converter.seti("rawWidth", getInWidth());
            converter.seti("rawHeight", getInHeight());
            converter.seti("cfaPattern", sensor.cfa);

            float[] gainMap = sensor.gainMap;
            int[] gainMapSize = sensor.gainMapSize;
            if (gainMap == null) {
                gainMap = new float[] { 1f, 1f, 1f, 1f };
                gainMapSize = new int[] { 1, 1 };
            }

            // Must use direct buffer for OpenGL ES
            FloatBuffer gainMapBuffer = ByteBuffer.allocateDirect(gainMap.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            gainMapBuffer.put(gainMap);
            gainMapBuffer.rewind();
            
            mGainMapTex = new Texture(gainMapSize[0], gainMapSize[1], 4, Texture.Format.Float16,
                    gainMapBuffer, GL_LINEAR);
            converter.setTexture("gainMap", mGainMapTex);

            int[] blackLevel = sensor.blackLevelPattern;
            converter.setf("blackLevel", blackLevel[0], blackLevel[1], blackLevel[2], blackLevel[3]);
            converter.setf("whiteLevel", sensor.whiteLevel);
            converter.seti("cfaPattern", getCfaPattern());
            converter.seti("hotPixelsSize", sensor.hotPixelsSize);

            // DNG tone mapping hints
            converter.setf("linearResponseLimit", sensor.linearResponseLimit);
            float exposureMultiplier = (float) Math.pow(2.0, sensor.baselineExposure);
            converter.setf("baselineExposure", exposureMultiplier);
            converter.seti("baselineExposureCompression", getProcessParams().baselineExposureCompression);
            String compressionName = getCompressionMethodName(getProcessParams().baselineExposureCompression);
            Log.d("PreProcess", "LinearResponseLimit: " + sensor.linearResponseLimit +
                       ", BaselineExposure: " + sensor.baselineExposure + " EV" +
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

            int[] hotPixelsSize = sensor.hotPixelsSize;
            try (Texture hotPx = new Texture(hotPixelsSize[0], hotPixelsSize[1], 1, Texture.Format.UInt16,
                    ShortBuffer.wrap(sensor.hotPixels), GL_NEAREST, GL_REPEAT)) {
                converter.setTexture("hotPixels", hotPx);
                converter.drawBlocks(mSensorTex);
            }
            
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
            Log.w("PreProcess", "Cannot save fusion debug frames: output base name not set");
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
            
            // Create temporary textures for each compression method (single channel)
            Texture reinhardTex = TexturePool.get(width, height, 1, Texture.Format.Float16);
            Texture acesTex = TexturePool.get(width, height, 1, Texture.Format.Float16);
            Texture gammaTex = TexturePool.get(width, height, 1, Texture.Format.Float16);
            
            try {
                // Re-setup common parameters (need to re-setup textures and parameters)
                // Note: This is a simplified version - we'd need to re-run the full preprocessing
                // For now, we'll use the existing mSensorTex as input and apply different compression methods
                // This requires the shader to support reading from a texture input
                // For simplicity, we'll just log a warning that this needs the full pipeline
                Log.w("PreProcess", "Debug frame saving for PreProcess requires full pipeline re-run - skipping");
                // TODO: Implement full pipeline re-run for debug frames in PreProcess
            } finally {
                reinhardTex.close();
                acesTex.close();
                gammaTex.close();
            }
        } catch (Exception e) {
            Log.e("PreProcess", "Failed to save fusion debug frames", e);
        }
    }

    @Override
    public int getShader() {
        return R.raw.stage1_1_fs;
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
    public void close() {
        if (mHistMatchLutTex != null) {
            mHistMatchLutTex.close();
        }
    }
}
