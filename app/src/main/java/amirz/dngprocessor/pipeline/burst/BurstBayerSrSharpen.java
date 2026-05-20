package amirz.dngprocessor.pipeline.burst;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.BayerProvider;

/**
 * CFA unsharp mask on the 2× merged Bayer from {@link BurstBayerSrMerge}.
 * Recovers acutance lost to multi-frame averaging without re-introducing
 * channel misregistration (legacy 1× merge + 2× anisotropic upsample).
 */
public class BurstBayerSrSharpen extends Stage implements BayerProvider {
    /** Post-merge unsharp strength; tune 0.3–0.8. */
    public static final float SHARPEN_AMOUNT = 0.40f;

    /** Light CFA unsharp after merge_ref; steerable SR supplies most acutance. */
    public static final boolean ENABLE_POST_SHARPEN = BurstWronskiBisect.ENABLE_POST_SHARPEN;

    private final BurstBayerSrMerge mMerge;
    private Texture mOutTex;

    public BurstBayerSrSharpen(BurstBayerSrMerge merge) {
        mMerge = merge;
    }

    @Override
    public boolean isEnabled() {
        return ENABLE_POST_SHARPEN;
    }

    @Override
    public Texture getSensorTex() {
        return mOutTex != null ? mOutTex : mMerge.getSensorTex();
    }

    @Override
    public int getInWidth() {
        return mMerge.getInWidth();
    }

    @Override
    public int getInHeight() {
        return mMerge.getInHeight();
    }

    @Override
    public int getCfaPattern() {
        return mMerge.getCfaPattern();
    }

    @Override
    public Texture getGainMapTex() {
        return null;
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        Texture in = mMerge.getSensorTex();
        if (in == null) {
            return;
        }
        int w = in.getWidth();
        int h = in.getHeight();

        mOutTex = TexturePool.get(w, h, 1, Texture.Format.Float16);
        GLPrograms converter = getConverter();
        converter.useProgram(R.raw.burst_bayer_sharpen);
        converter.setTexture("bayerTex", in);
        converter.seti("inWidth", w);
        converter.seti("inHeight", h);
        converter.setf("amount", SHARPEN_AMOUNT);
        converter.drawBlocks(mOutTex, false);
        in.close();
    }

    @Override
    public int getShader() {
        return R.raw.burst_bayer_sharpen;
    }

    @Override
    public void close() {
        // Output closed by ToIntermediate.
    }
}
