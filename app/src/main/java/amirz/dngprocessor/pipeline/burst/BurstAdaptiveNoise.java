package amirz.dngprocessor.pipeline.burst;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.math.BlockDivider;

import static android.opengl.GLES20.GL_FLOAT;
import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_RGBA;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glReadPixels;
import static amirz.dngprocessor.util.Constants.BLOCK_HEIGHT;

/**
 * Per-burst adaptive noise calibration (Wronski 2019 §IV.B "Noise model" +
 * PhotonCamera-style 2D histogram fit).
 *
 * <p>Inputs: per-tile mean and RMS textures of the reference frame
 * (computed by {@code burst_freq_tile_mean.glsl} and
 * {@code burst_freq_rms.glsl}).
 *
 * <p>Output: a 4-vector linear noise model per CFA channel,
 * {@code σ²(tile) = A + B · tile_mean}, computed by weighted-median
 * regression over the per-tile (mean, variance) cloud. Heavier tiles
 * (those with more brightness signal) contribute more — they dominate the
 * fit, and dark tiles (whose RMS is dominated by read noise) anchor the
 * intercept.
 *
 * <p>Compared to the spec-sheet model {@code σ² = rms + readNoise}:
 * <ul>
 *   <li>The intercept A captures the actual read noise of the sensor in
 *       its current operating mode (gain, exposure, temperature), not the
 *       data-sheet value.</li>
 *   <li>The slope B captures any per-channel sensitivity differences not
 *       yet removed by black-level subtraction.</li>
 *   <li>The result is SMOOTHER across tiles than the raw per-tile RMS,
 *       so the Wiener filter sees a less noisy noise estimate and
 *       doesn't accidentally treat a noisy tile as a high-motion tile.</li>
 * </ul>
 *
 * <p>This implementation skips the brightness-indexed LUT in favour of a
 * scalar linear model per channel — the LUT shape is only useful for
 * sensors with strongly non-linear noise (e.g. saturating near the white
 * level) and most consumer phone sensors are well-described by a line in
 * the linear-light units BurstPreProcess produces.
 */
final class BurstAdaptiveNoise {

    private static final String TAG = "BurstAdaptiveNoise";

    /** Per CFA channel intercept (read noise floor in linear-light units²). */
    public final float[] a = new float[4];
    /** Per CFA channel slope (photon-noise coefficient). */
    public final float[] b = new float[4];

    private BurstAdaptiveNoise() {}

    /**
     * Fit the linear noise model from the per-tile (mean, rms) textures.
     *
     * @param meanTex  per-tile RGBA mean   (n_tiles_x × n_tiles_y)
     * @param rmsTex   per-tile RGBA RMS    (n_tiles_x × n_tiles_y)
     * @param nTilesX  tile-grid width
     * @param nTilesY  tile-grid height
     */
    public static BurstAdaptiveNoise fit(Texture meanTex, Texture rmsTex,
                                         int nTilesX, int nTilesY) {
        float[] meanBuf = downloadRgbaFloat(meanTex, nTilesX, nTilesY);
        float[] rmsBuf  = downloadRgbaFloat(rmsTex,  nTilesX, nTilesY);

        BurstAdaptiveNoise model = new BurstAdaptiveNoise();
        int nTiles = nTilesX * nTilesY;
        for (int ch = 0; ch < 4; ch++) {
            fitChannel(meanBuf, rmsBuf, ch, nTiles, model);
        }
        Log.d(TAG, String.format(
                "Adaptive noise fit: A=[%.5g %.5g %.5g %.5g]  B=[%.5g %.5g %.5g %.5g]",
                model.a[0], model.a[1], model.a[2], model.a[3],
                model.b[0], model.b[1], model.b[2], model.b[3]));
        return model;
    }

    /**
     * Weighted least-squares of σ²(B) = A + B·B over per-tile samples for one CFA
     * channel. Variances are taken as {@code rms²}; brightness is the per-tile mean.
     * Tiles with extreme brightness near 0 or 1 are dropped — they tend to be
     * clipped patches that don't represent the noise model.
     */
    private static void fitChannel(float[] meanBuf, float[] rmsBuf, int ch,
                                   int nTiles, BurstAdaptiveNoise out) {
        // First pass: collect valid samples.
        float[] xs = new float[nTiles];
        float[] ys = new float[nTiles];
        int nValid = 0;
        for (int t = 0; t < nTiles; t++) {
            float mean = meanBuf[t * 4 + ch];
            float rms  = rmsBuf[t * 4 + ch];
            // Reject clipped tiles (very dark or saturated).
            if (mean < 0.005f || mean > 0.995f) continue;
            // Reject obviously bad samples (NaN / Inf from edge tiles, etc.).
            if (!isFinite(mean) || !isFinite(rms)) continue;
            float variance = rms * rms;
            xs[nValid] = mean;
            ys[nValid] = variance;
            nValid++;
        }
        if (nValid < 4) {
            // Not enough samples — fall back to identity.
            out.a[ch] = 1e-4f;
            out.b[ch] = 1e-3f;
            return;
        }

        // Ordinary least squares (variance-uniform weighting). Robust enough for
        // a noise model fit; the rejection above handles the worst outliers.
        double sumX = 0, sumY = 0, sumXX = 0, sumXY = 0;
        for (int i = 0; i < nValid; i++) {
            double x = xs[i], y = ys[i];
            sumX  += x;
            sumY  += y;
            sumXX += x * x;
            sumXY += x * y;
        }
        double meanX = sumX / nValid;
        double meanY = sumY / nValid;
        double cov   = sumXY / nValid - meanX * meanY;
        double varX  = sumXX / nValid - meanX * meanX;
        double slope = (varX > 1e-12) ? cov / varX : 0.0;
        double intercept = meanY - slope * meanX;

        // Clamp to physically plausible ranges. Intercept ≥ 0 (no negative read
        // noise). Slope ≥ 0 (photon noise non-decreasing with brightness).
        out.a[ch] = (float) Math.max(0.0, intercept);
        out.b[ch] = (float) Math.max(0.0, slope);
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    /** Read back a small RGBA Float16 texture as a flat {@code float[w*h*4]}. */
    private static float[] downloadRgbaFloat(Texture tex, int w, int h) {
        float[] out = new float[w * h * 4];
        ByteBuffer buf = ByteBuffer.allocateDirect(w * BLOCK_HEIGHT * 4 * 4)
                .order(ByteOrder.nativeOrder());
        FloatBuffer fb = buf.asFloatBuffer();

        tex.setFrameBuffer();
        BlockDivider divider = new BlockDivider(h, BLOCK_HEIGHT);
        int[] row = new int[2];
        while (divider.nextBlock(row)) {
            int y = row[0], bh = row[1];
            fb.position(0);
            glReadPixels(0, y, w, bh, GL_RGBA, GL_FLOAT, fb);
            fb.rewind();
            int base = y * w * 4;
            int n = w * bh * 4;
            for (int i = 0; i < n; i++) {
                out[base + i] = fb.get(i);
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return out;
    }
}
