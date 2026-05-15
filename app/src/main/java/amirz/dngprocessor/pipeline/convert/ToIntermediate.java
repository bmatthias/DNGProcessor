package amirz.dngprocessor.pipeline.convert;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;

import android.util.Log;

public class ToIntermediate extends Stage implements IntermediateProvider {
    private final float[] mSensorToXYZ_D50;
    private final float[] mYuvCamMatrix;

    private Texture mIntermediate;

    public ToIntermediate(float[] sensorToXYZ_D50, float[] yuvCamMatrix) {
        mSensorToXYZ_D50 = sensorToXYZ_D50;
        mYuvCamMatrix = yuvCamMatrix;
    }

    public Texture getIntermediate() {
        return mIntermediate;
    }

    @Override
    protected void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();

        PreProcess preProcess = previousStages.getStage(PreProcess.class);

        converter.seti("rawWidth", preProcess.getInWidth());
        converter.seti("rawHeight", preProcess.getInHeight());

        // Second texture for per-CFA pixel data
        // Note: Must use 4 channels (RGBA16F) because RGB16F is not color-renderable in GLES 3.0
        mIntermediate = TexturePool.get(preProcess.getInWidth(), preProcess.getInHeight(), 4,
                Texture.Format.Float16);

        // Load mosaic and green raw texture
        try (Texture sensorGTex = previousStages.getStage(GreenDemosaic.class).getSensorGTex()) {
            try (Texture sensorTex = preProcess.getSensorTex()) {
                converter.setTexture("rawBuffer", sensorTex);
                converter.setTexture("greenBuffer", sensorGTex);

                float[] neutralPoint = getSensorParams().neutralColorPoint;
                byte[] cfaVal = getSensorParams().cfaVal;
                
                // Validate and fix any invalid values in neutralPoint
                for (int i = 0; i < neutralPoint.length; i++) {
                    if (Float.isNaN(neutralPoint[i]) || Float.isInfinite(neutralPoint[i]) || neutralPoint[i] <= 0) {
                        Log.w("ToIntermediate", "Invalid neutralPoint[" + i + "]=" + neutralPoint[i] + ", using 0.5");
                        neutralPoint[i] = 0.5f;
                    }
                }
                
                converter.setf("neutralLevel",
                        neutralPoint[cfaVal[0]],
                        neutralPoint[cfaVal[1]],
                        neutralPoint[cfaVal[2]],
                        neutralPoint[cfaVal[3]]);

                converter.setf("neutralPoint", neutralPoint);
                converter.setf("sensorToXYZ", mSensorToXYZ_D50);
                converter.seti("cfaPattern", preProcess.getCfaPattern());
                
                // Set demosaicing method: 0 = bilinear, 1 = DHT, 2 = AAHD
                String demosaicingMethod = getProcessParams().demosaicingMethod;
                int methodId = 0; // Default to bilinear
                if ("bilinear".equals(demosaicingMethod)) {
                    methodId = 0;
                } else if ("dht".equals(demosaicingMethod)) {
                    methodId = 1;
                } else if ("aahd".equals(demosaicingMethod)) {
                    methodId = 2;
                }
                converter.seti("demosaicingMethod", methodId);
                
                // Pass YUV conversion matrix for AAHD demosaicing
                converter.setf("yuvCamMatrix", mYuvCamMatrix);

                try (Texture gainMapTex = preProcess.getGainMapTex()) {
                    converter.setTexture("gainMap", gainMapTex);
                    converter.drawBlocks(mIntermediate);
                }
            }
        }
    }

    @Override
    public int getShader() {
        return R.raw.stage1_3_fs;
    }
}
