package amirz.dngprocessor.pipeline.convert;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;

public class EdgeMirror extends Stage implements IntermediateProvider {
    private Texture mIntermediate;
    private int mOffsetScale = 1;

    /** Use when the intermediate is at {@code scale}× the sensor's native resolution. */
    public void setOffsetScale(int scale) {
        mOffsetScale = scale;
    }

    public Texture getIntermediate() {
        return mIntermediate;
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();

        // Get the most recent intermediate (could be from EarlyExposureFusion or ToIntermediate)
        IntermediateProvider provider = previousStages.getStageByInterface(IntermediateProvider.class);
        if (provider == null) {
            // Fallback to ToIntermediate if no provider found
            ToIntermediate toIntermediate = previousStages.getStage(ToIntermediate.class);
            if (toIntermediate == null) {
                throw new IllegalStateException("No intermediate provider found");
            }
            provider = toIntermediate;
        }
        Texture inputIntermediate = provider.getIntermediate();
        int w = inputIntermediate.getWidth();
        int h = inputIntermediate.getHeight();

        converter.setTexture("intermediateBuffer", inputIntermediate);

        int offsetX = mOffsetScale * getSensorParams().outputOffsetX;
        int offsetY = mOffsetScale * getSensorParams().outputOffsetY;
        converter.seti("minxy", offsetX, offsetY);
        converter.seti("maxxy", w - offsetX - 1, h - offsetY - 1);

        // Create a separate output texture to avoid reading/writing the same texture
        // (which is undefined behavior in OpenGL)
        mIntermediate = TexturePool.get(w, h, inputIntermediate.getChannels(), 
                inputIntermediate.getFormat());

        converter.drawBlocks(mIntermediate, false);
    }

    @Override
    public int getShader() {
        return R.raw.stage1_4_edge_mirror_fs;
    }

    @Override
    public void close() {
        if (mIntermediate != null) {
            mIntermediate.close();
            mIntermediate = null;
        }
    }
}
