package amirz.dngprocessor.pipeline.convert;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;

public class GreenDemosaic extends Stage {
    private Texture mSensorG;

    public Texture getSensorGTex() {
        return mSensorG;
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();

        BayerProvider bayerProvider = previousStages.getStageByInterface(BayerProvider.class);

        Texture sensorTex = bayerProvider.getSensorTex();
        converter.setTexture("rawBuffer", sensorTex);
        converter.seti("rawWidth", sensorTex.getWidth());
        converter.seti("rawHeight", sensorTex.getHeight());

        mSensorG = new Texture(sensorTex.getWidth(), sensorTex.getHeight(), 1,
                Texture.Format.Float16, null);

        converter.seti("cfaPattern", bayerProvider.getCfaPattern());
        converter.drawBlocks(mSensorG);
    }

    @Override
    public int getShader() {
        return R.raw.stage1_2_fs;
    }
}
