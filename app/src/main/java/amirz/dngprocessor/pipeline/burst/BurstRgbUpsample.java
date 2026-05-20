package amirz.dngprocessor.pipeline.burst;

import android.util.Log;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;
import amirz.dngprocessor.pipeline.convert.ToIntermediate;

/**
 * 1× → 2× bilinear upscale of the demosaiced intermediate after
 * {@link ToIntermediate}. Replaces Bayer-domain {@link BurstSrUpsample} when
 * {@link BurstSrUpsample#USE_RGB_UPSAMPLE} is enabled so merge colour offsets
 * are not amplified by per-CFA resampling.
 */
public class BurstRgbUpsample extends Stage implements IntermediateProvider {
    private static final String TAG = "BurstRgbUpsample";

    private Texture mUpsampled;

    @Override
    public Texture getIntermediate() {
        return mUpsampled;
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        ToIntermediate toIntermediate = previousStages.getStage(ToIntermediate.class);
        if (toIntermediate == null) {
            throw new IllegalStateException("BurstRgbUpsample requires ToIntermediate");
        }
        Texture src = toIntermediate.getIntermediate();
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        int outW = 2 * srcW;
        int outH = 2 * srcH;

        mUpsampled = TexturePool.get(outW, outH, src.getChannels(), src.getFormat());

        getConverter().useProgram(R.raw.burst_rgb_upsample);
        getConverter().setTexture("srcTex", src);
        getConverter().seti("srcSize", srcW, srcH);
        getConverter().drawBlocks(mUpsampled);

        Log.i(TAG, "RGB upsample " + srcW + "x" + srcH + " -> " + outW + "x" + outH);
    }

    @Override
    public int getShader() {
        return R.raw.burst_rgb_upsample;
    }

    @Override
    public void close() {
        if (mUpsampled != null) {
            mUpsampled.close();
            mUpsampled = null;
        }
    }
}
