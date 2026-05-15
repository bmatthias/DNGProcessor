package amirz.dngprocessor.pipeline;

import android.util.Log;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.pipeline.convert.LinearRawPreProcess;
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;
import amirz.dngprocessor.pipeline.convert.RgbProvider;

/**
 * Converts Linear Raw RGB data to intermediate XYZ/xyY colorspace.
 * Unlike ToIntermediate, this handles already-demosaiced RGB data.
 * Gets RGB from RgbProvider (LinearRawPreProcess or EarlyExposureFusion if enabled).
 */
public class LinearRawToIntermediate extends Stage implements IntermediateProvider {
    private static final String TAG = "LinearRawToIntermediate";
    private final float[] mSensorToXYZ_D50;

    private Texture mIntermediate;

    public LinearRawToIntermediate(float[] sensorToXYZ_D50) {
        mSensorToXYZ_D50 = sensorToXYZ_D50;
    }

    public Texture getIntermediate() {
        return mIntermediate;
    }

    @Override
    protected void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();

        // Get RGB texture from RgbProvider (could be EarlyExposureFusion or LinearRawPreProcess)
        RgbProvider rgbProvider = previousStages.getStageByInterface(RgbProvider.class);
        if (rgbProvider == null) {
            Log.e(TAG, "No RgbProvider found");
            return;
        }
        
        Texture rgbTex = rgbProvider.getRgbTex();
        if (rgbTex == null) {
            Log.e(TAG, "No RGB texture available from " + rgbProvider.getClass().getSimpleName());
            return;
        }
        
        Log.d(TAG, "Got RGB from " + rgbProvider.getClass().getSimpleName());
        
        // Get dimensions and gain map from LinearRawPreProcess
        LinearRawPreProcess preProcess = previousStages.getStage(LinearRawPreProcess.class);

        converter.seti("rawWidth", preProcess.getInWidth());
        converter.seti("rawHeight", preProcess.getInHeight());

        // Output texture for intermediate xyY format
        // Note: Must use 4 channels (RGBA16F) because RGB16F is not color-renderable in GLES 3.0
        mIntermediate = TexturePool.get(preProcess.getInWidth(), preProcess.getInHeight(), 4,
                Texture.Format.Float16);

        // Convert RGB to xyY (don't close rgbTex - it's owned by the provider)
        converter.setTexture("rgbBuffer", rgbTex);

        float[] neutralPoint = getSensorParams().neutralColorPoint;
        converter.setf("neutralPoint", neutralPoint);
        converter.setf("sensorToXYZ", mSensorToXYZ_D50);

        try (Texture gainMapTex = preProcess.getGainMapTex()) {
            converter.setTexture("gainMap", gainMapTex);
            converter.drawBlocks(mIntermediate);
        }
    }

    @Override
    public int getShader() {
        return R.raw.stage1_linear_to_intermediate_fs;
    }
}

