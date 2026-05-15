package amirz.dngprocessor.pipeline.intermediate;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;

public class BilateralFilter extends Stage {
    private final ProcessParams mProcess;
    private Texture mBilateral;

    public BilateralFilter(ProcessParams process) {
        mProcess = process;
    }

    public Texture getBilateral() {
        return mBilateral;
    }

    @Override
    protected void execute(StagePipeline.StageMap previousStages) {
        if (mProcess.histFactor == 0f) {
            return;
        }

        GLPrograms converter = getConverter();

        // Get intermediate texture from most recent IntermediateProvider (could be Merge, LocalLaplacian, ToneEqualizer, etc.)
        Texture intermediate = previousStages.getStageByInterface(IntermediateProvider.class).getIntermediate();
        int w = intermediate.getWidth();
        int h = intermediate.getHeight();

        // Note: Must use 4 channels (RGBA16F) because RGB16F is not color-renderable in GLES 3.0
        mBilateral = TexturePool.get(w, h, 4, Texture.Format.Float16);
        try (Texture bilateralTmp = TexturePool.get(w, h, 4, Texture.Format.Float16)) {
            // Pre-bilateral median filter.
            converter.setTexture("buf", intermediate);
            converter.drawBlocks(bilateralTmp, false);

            // 3-step bilateral filter setup.
            converter.useProgram(R.raw.stage2_3_bilateral);
            converter.seti("bufSize", w, h);

            // 1) Small area, strong blur.
            converter.setTexture("buf", bilateralTmp);
            converter.setf("sigma", 0.03f, 0.5f);
            converter.seti("radius", 3, 1);
            converter.drawBlocks(mBilateral, false);

            // 2) Medium area, medium blur.
            converter.setTexture("buf", mBilateral);
            converter.setf("sigma", 0.02f, 3f);
            converter.seti("radius", 6, 2);
            converter.drawBlocks(bilateralTmp, false);

            // 3) Large area, weak blur.
            converter.setTexture("buf", bilateralTmp);
            converter.setf("sigma", 0.01f, 9f);
            converter.seti("radius", 9, 3);
            converter.drawBlocks(mBilateral);
        }
    }

    @Override
    public int getShader() {
        return R.raw.stage2_3_median;
    }

    @Override
    protected boolean isEnabled() {
        // Enable bilateral filter when edge-aware histogram equalization is active
        return mProcess.edgeAwareHistEq && mProcess.histFactor > 0f;
    }
}
