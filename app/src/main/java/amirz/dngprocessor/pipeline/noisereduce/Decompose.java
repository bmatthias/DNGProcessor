package amirz.dngprocessor.pipeline.noisereduce;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;

public class Decompose extends Stage {
    private Texture mHighRes, mMediumRes, mLowRes;

    public Texture[] getLayers() {
        return new Texture[] {
            mHighRes, mMediumRes, mLowRes
        };
    }

    @Override
    protected void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();

        mHighRes = previousStages.getStageByInterface(IntermediateProvider.class).getIntermediate();
        int w = mHighRes.getWidth();
        int h = mHighRes.getHeight();

        converter.seti("bufSize", w, h);

        // Note: Must use 4 channels (RGBA16F) because RGB16F is not color-renderable in GLES 3.0
        try (Texture tmp = new Texture(w, h, 4,
                Texture.Format.Float16, null)) {
            converter.setf("sigma", 3f);
            converter.seti("radius", 6);

            // First render to the tmp buffer.
            converter.setTexture("buf", mHighRes);
            converter.seti("dir", 0, 1); // Vertical
            converter.drawBlocks(tmp, false);

            // Now render from tmp to the real buffer.
            converter.setTexture("buf", tmp);
            converter.seti("dir", 1, 0); // Horizontal

            mMediumRes = new Texture(w, h, 4,
                    Texture.Format.Float16, null);
            converter.drawBlocks(mMediumRes);

            converter.setf("sigma", 9f);
            converter.seti("radius", 18);

            // First render to the tmp buffer.
            converter.setTexture("buf", mMediumRes);
            converter.seti("dir", 0, 1); // Vertical
            converter.drawBlocks(tmp, false);

            // Now render from tmp to the real buffer.
            converter.setTexture("buf", tmp);
            converter.seti("dir", 1, 0); // Horizontal

            mLowRes = new Texture(w, h, 4,
                    Texture.Format.Float16, null);
            converter.drawBlocks(mLowRes);
        }
    }

    @Override
    public int getShader() {
        return R.raw.stage2_0_blur_3ch_fs;
    }

    @Override
    protected boolean isEnabled() {
        return getProcessParams().noiseReduce;
    }
}
