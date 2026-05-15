package amirz.dngprocessor.pipeline.convert;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.pipeline.LinearRawToIntermediate;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;

/**
 * Edge mirroring stage for Linear Raw pipeline.
 * Same as EdgeMirror but works with LinearRawToIntermediate.
 */
public class LinearRawEdgeMirror extends Stage implements IntermediateProvider {
    private Texture mIntermediate;

    public Texture getIntermediate() {
        return mIntermediate;
    }

    @Override
    protected void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();

        // Get the most recent intermediate (could be from EarlyExposureFusion or LinearRawToIntermediate)
        IntermediateProvider provider = previousStages.getStageByInterface(IntermediateProvider.class);
        if (provider == null) {
            // Fallback to LinearRawToIntermediate if no provider found
            LinearRawToIntermediate toIntermediate = previousStages.getStage(LinearRawToIntermediate.class);
            if (toIntermediate == null) {
                throw new IllegalStateException("No intermediate provider found");
            }
            provider = toIntermediate;
        }
        Texture inputIntermediate = provider.getIntermediate();
        int w = inputIntermediate.getWidth();
        int h = inputIntermediate.getHeight();

        converter.setTexture("intermediateBuffer", inputIntermediate);

        int offsetX = getSensorParams().outputOffsetX;
        int offsetY = getSensorParams().outputOffsetY;
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

