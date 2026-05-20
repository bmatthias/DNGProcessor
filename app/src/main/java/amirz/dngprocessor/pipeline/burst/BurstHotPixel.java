package amirz.dngprocessor.pipeline.burst;

import android.util.Log;

import java.util.List;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.parser.RawFrame;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;

/**
 * Pre-merge hot-pixel correction stage. PhotonCamera-borrow
 * (PyramidMerging.java:76-250 + merge/hotpixeldetect.glsl + hotpixelcorrect.glsl),
 * simplified to a single GPU pass that detects and corrects in one shot.
 *
 * <p>Operates AFTER {@link BurstPreProcess} (frames are already black-level
 * subtracted and normalised) and BEFORE {@link BurstFrequencyMerge} (so the
 * FFT never sees the hot pixels and the Wiener doesn't have to treat them
 * as motion).
 *
 * <p>Replaces the per-frame textures in
 * {@link BurstPreProcess#getSensorTextures()} with hot-pixel-corrected
 * versions; the original textures are closed and released to the pool.
 * Downstream stages read the same list reference and so see the corrected
 * frames transparently.
 */
public class BurstHotPixel extends Stage {

    private static final String TAG = "BurstHotPixel";

    private static final boolean BISECT_HOT_PIXEL = true;

    /** Plan #10: hot-pixel pre-correction before frequency merge. */
    public static final boolean ENABLE_HOT_PIXEL =
            BurstFrequencyMerge.ENABLE_PLAN_IMPROVEMENTS || BISECT_HOT_PIXEL;

    /**
     * Detection threshold, in standard deviations from the same-colour
     * 4-neighbour mean. Higher = fewer hot pixels detected (more
     * conservative). 5 is the PhotonCamera default; 4 is more aggressive,
     * 6+ rarely triggers.
     */
    public static final float HOT_PIXEL_THRESHOLD_K = 5.0f;

    private final BurstPreProcess mPreProcess;
    private final List<RawFrame> mFrames;

    public BurstHotPixel(BurstPreProcess preProcess, List<RawFrame> frames) {
        mPreProcess = preProcess;
        mFrames = frames;
    }

    @Override
    public int getShader() {
        return R.raw.burst_hotpixel;
    }

    @Override
    public boolean isEnabled() {
        return ENABLE_HOT_PIXEL;
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();
        List<Texture> textures = mPreProcess.getSensorTextures();
        long t0 = System.nanoTime();
        int corrected = 0;
        for (int k = 0; k < textures.size(); k++) {
            Texture in = textures.get(k);
            int w = in.getWidth();
            int h = in.getHeight();
            Texture out = TexturePool.get(w, h, 1, Texture.Format.Float16);
            converter.useProgram(R.raw.burst_hotpixel);
            converter.setTexture("bayerTex", in);
            converter.seti("inWidth", w);
            converter.seti("inHeight", h);
            converter.setf("hotPixelThresholdK", HOT_PIXEL_THRESHOLD_K);
            converter.drawBlocks(out);
            in.close();
            textures.set(k, out);
            corrected++;
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        Log.d(TAG, "Hot-pixel correction: " + corrected + " frames in " + elapsedMs + "ms"
                + " (threshold k=" + HOT_PIXEL_THRESHOLD_K + ")");
    }
}
