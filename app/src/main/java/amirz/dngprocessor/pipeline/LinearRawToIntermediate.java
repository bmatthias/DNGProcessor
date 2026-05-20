package amirz.dngprocessor.pipeline;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;
import amirz.dngprocessor.pipeline.convert.RgbProvider;

import static android.opengl.GLES20.GL_LINEAR;

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
    public void execute(StagePipeline.StageMap previousStages) {
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
        
        converter.seti("rawWidth", rgbProvider.getInWidth());
        converter.seti("rawHeight", rgbProvider.getInHeight());

        // Output texture for intermediate xyY format
        // Note: Must use 4 channels (RGBA16F) because RGB16F is not color-renderable in GLES 3.0
        mIntermediate = TexturePool.get(rgbProvider.getInWidth(), rgbProvider.getInHeight(), 4,
                Texture.Format.Float16);

        // Convert RGB to xyY (don't close rgbTex - it's owned by the provider)
        converter.setTexture("rgbBuffer", rgbTex);

        float[] neutralPoint = getSensorParams().neutralColorPoint;
        converter.setf("neutralPoint", neutralPoint);
        converter.setf("sensorToXYZ", mSensorToXYZ_D50);

        Texture gainMapTex = rgbProvider.getGainMapTex();
        boolean ownGainMap = false;
        if (gainMapTex == null) {
            // No gain map (already applied upstream) – use a 1×1 identity (all ones)
            FloatBuffer ones = ByteBuffer.allocateDirect(4 * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            ones.put(new float[]{1f, 1f, 1f, 1f}).rewind();
            gainMapTex = new Texture(1, 1, 4, Texture.Format.Float16, ones, GL_LINEAR);
            ownGainMap = true;
        }
        converter.setTexture("gainMap", gainMapTex);
        converter.drawBlocks(mIntermediate);
        if (ownGainMap) gainMapTex.close();
    }

    @Override
    public int getShader() {
        return R.raw.stage1_linear_to_intermediate_fs;
    }
}

