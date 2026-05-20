package amirz.dngprocessor.pipeline.burst;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.SensorParams;

/**
 * Wronski Alg. 6–9: colour-guide local stats + robustness mask for 2× merge.
 */
final class BurstWronskiRobustness {

    static final class LocalStats {
        final Texture mean;
        final Texture variance;

        LocalStats(Texture mean, Texture variance) {
            this.mean = mean;
            this.variance = variance;
        }

        void close() {
            mean.close();
            variance.close();
        }
    }

    private BurstWronskiRobustness() {}

    static float[] wbGain(SensorParams sensor) {
        float[] np = sensor.neutralColorPoint;
        float g = (np.length > 1 && np[1] > 1e-6f) ? np[1] : 1f;
        return new float[]{
                (np.length > 0 && np[0] > 0f) ? np[0] / g : 1f,
                1f,
                (np.length > 2 && np[2] > 0f) ? np[2] / g : 1f
        };
    }

    static void bindFlow(GLPrograms gl, BayerAlignment.TileShiftResult flow,
                         float[] medianShift, int useTileFlow) {
        gl.seti("useTileFlow", useTileFlow);
        gl.setf("tileFlowOriginSrc", flow.tileOriginSrcX, flow.tileOriginSrcY);
        gl.setf("tileFlowStrideSrc", flow.tileStrideSrcX, flow.tileStrideSrcY);
        gl.seti("tileFlowSize", flow.nTilesX, flow.nTilesY);
        gl.setf("tileFlowConfGamma", 2.0f);
        if (medianShift != null) {
            gl.setf("globalShift", medianShift[0], medianShift[1]);
        } else {
            gl.setf("globalShift", 0f, 0f);
        }
    }

    static Texture buildGuide(GLPrograms gl, Texture bayer, Texture tileFlowTex,
                              int srcW, int srcH, int cfa, float[] wb,
                              BayerAlignment.TileShiftResult flow,
                              float[] medianShift, int useTileFlow) {
        int gw = srcW / 2;
        int gh = srcH / 2;
        Texture guide = TexturePool.get(gw, gh, 4, Texture.Format.Float16);
        gl.useProgram(R.raw.burst_wronski_guide);
        gl.setTexture("bayerTex", bayer);
        gl.seti("inWidth", srcW);
        gl.seti("inHeight", srcH);
        gl.seti("cfaPattern", cfa);
        gl.setf("wbGain", wb[0], wb[1], wb[2]);
        if (useTileFlow != 0) {
            gl.setTexture("tileFlowTex", tileFlowTex);
        }
        bindFlow(gl, flow, medianShift, useTileFlow);
        gl.drawBlocks(guide, false);
        return guide;
    }

    static LocalStats buildLocalStats(GLPrograms gl, Texture guide) {
        int gw = guide.getWidth();
        int gh = guide.getHeight();
        Texture mean = TexturePool.get(gw, gh, 4, Texture.Format.Float16);
        Texture variance = TexturePool.get(gw, gh, 4, Texture.Format.Float16);
        gl.useProgram(R.raw.burst_wronski_local_stats);
        gl.setTexture("guideTex", guide);
        gl.seti("guideSize", gw, gh);
        gl.drawBlocks(mean, false);
        gl.useProgram(R.raw.burst_wronski_local_stats_var);
        gl.setTexture("guideTex", guide);
        gl.seti("guideSize", gw, gh);
        gl.drawBlocks(variance, false);
        return new LocalStats(mean, variance);
    }

    /**
     * Upscale guide stats to full Bayer resolution. Alt uses flow-warped lookup
     * (reference {@code upscale_warp_stats}); ref uses {@code useFlow = 0}.
     */
    static LocalStats upscaleStatsToBayer(GLPrograms gl, LocalStats guideStats,
                                          int srcW, int srcH,
                                          BayerAlignment.TileShiftResult flow,
                                          Texture tileFlowTex, float[] medianShift,
                                          int useFlow) {
        Texture mean = upscaleStatMap(gl, guideStats.mean, srcW, srcH, flow, tileFlowTex,
                medianShift, useFlow);
        Texture variance = upscaleStatMap(gl, guideStats.variance, srcW, srcH, flow, tileFlowTex,
                medianShift, useFlow);
        return new LocalStats(mean, variance);
    }

    private static Texture upscaleStatMap(GLPrograms gl, Texture guideMap,
                                            int srcW, int srcH,
                                            BayerAlignment.TileShiftResult flow,
                                            Texture tileFlowTex, float[] medianShift,
                                            int useFlow) {
        int gw = guideMap.getWidth();
        int gh = guideMap.getHeight();
        Texture full = TexturePool.get(srcW, srcH, 4, Texture.Format.Float16);
        gl.useProgram(R.raw.burst_wronski_upscale_stats);
        gl.setTexture("srcTex", guideMap);
        gl.seti("srcSize", gw, gh);
        gl.seti("useFlow", useFlow);
        if (useFlow != 0) {
            gl.setTexture("tileFlowTex", tileFlowTex);
        }
        bindFlow(gl, flow, medianShift, useFlow);
        gl.drawBlocks(full, false);
        return full;
    }

    /** Per-tile S map for robustness_threshold (Alg. 6). */
    static Texture buildFlowSTex(GLPrograms gl, Texture tileFlowTex,
                                 BayerAlignment.TileShiftResult flow) {
        Texture sTex = TexturePool.get(flow.nTilesX, flow.nTilesY, 1, Texture.Format.Float16);
        gl.useProgram(R.raw.burst_wronski_flow_s);
        gl.setTexture("tileFlowTex", tileFlowTex);
        gl.drawBlocks(sTex, false);
        return sTex;
    }

    static float flowSForFrame(BayerAlignment.TileShiftResult flow, int k) {
        if (k == 0) return BurstWronskiTuning.FLOW_S2;
        int fW = flow.nTilesX;
        int fH = flow.nTilesY;
        int tilesPerFrame = flow.tilesPerFrame();
        int base = k * tilesPerFrame * 2;
        float minS = BurstWronskiTuning.FLOW_S2;
        for (int ty = 0; ty < fH; ty++) {
            for (int tx = 0; tx < fW; tx++) {
                float maxMag = -1f;
                float minMag = Float.POSITIVE_INFINITY;
                int y0 = Math.max(0, ty - 1);
                int y1 = Math.min(fH - 1, ty + 1);
                int x0 = Math.max(0, tx - 1);
                int x1 = Math.min(fW - 1, tx + 1);
                for (int ny = y0; ny <= y1; ny++) {
                    for (int nx = x0; nx <= x1; nx++) {
                        int idx = base + (ny * fW + nx) * 2;
                        float dx = flow.shifts[idx];
                        float dy = flow.shifts[idx + 1];
                        float mag = (float) Math.sqrt(dx * dx + dy * dy);
                        if (mag > maxMag) maxMag = mag;
                        if (mag < minMag) minMag = mag;
                    }
                }
                float spread = maxMag - minMag;
                float s = (spread * spread > BurstWronskiTuning.FLOW_Mt_SQUARED)
                        ? BurstWronskiTuning.FLOW_S1 : BurstWronskiTuning.FLOW_S2;
                minS = Math.min(minS, s);
            }
        }
        return minS;
    }

    /** Requires {@link #upscaleStatsToBayer} on ref/alt stats (full Bayer resolution). */
    static Texture buildRobustness(GLPrograms gl, LocalStats ref, LocalStats alt,
                                   Texture flowSTex, BayerAlignment.TileShiftResult flow,
                                   BurstWronskiNoiseLut noiseLut) {
        int w = ref.mean.getWidth();
        int h = ref.mean.getHeight();
        Texture r = TexturePool.get(w, h, 1, Texture.Format.Float16);
        gl.useProgram(R.raw.burst_wronski_robustness);
        gl.setTexture("refMeanTex", ref.mean);
        gl.setTexture("refVarTex", ref.variance);
        gl.setTexture("altMeanTex", alt.mean);
        gl.setTexture("altVarTex", alt.variance);
        gl.setTexture("stdCurveTex", noiseLut.stdCurveTex);
        gl.setTexture("diffCurveTex", noiseLut.diffCurveTex);
        gl.setTexture("flowSTex", flowSTex);
        gl.setf("tileFlowOriginSrc", flow.tileOriginSrcX, flow.tileOriginSrcY);
        gl.setf("tileFlowStrideSrc", flow.tileStrideSrcX, flow.tileStrideSrcY);
        gl.seti("tileFlowSize", flow.nTilesX, flow.nTilesY);
        gl.setf("robustT", BurstWronskiTuning.ROBUST_T);
        gl.setf("robustSoftWidth", BurstWronskiTuning.ROBUST_SOFT_WIDTH);
        gl.drawBlocks(r, false);

        Texture pooled = TexturePool.get(w, h, 1, Texture.Format.Float16);
        gl.useProgram(R.raw.burst_wronski_robustness_pool);
        gl.setTexture("robustTex", r);
        gl.seti("robustSize", w, h);
        gl.drawBlocks(pooled, false);
        r.close();
        return pooled;
    }

    /** accRobOut = accRobIn + robust (ping-pong safe). */
    static Texture accumulateRobustness(GLPrograms gl, Texture accRobIn, Texture robust) {
        Texture accRobOut = TexturePool.get(accRobIn.getWidth(), accRobIn.getHeight(), 1,
                Texture.Format.Float16);
        gl.useProgram(R.raw.burst_wronski_accum_rob);
        gl.setTexture("accRobTex", accRobIn);
        gl.setTexture("robustTex", robust);
        gl.drawBlocks(accRobOut, false);
        return accRobOut;
    }
}
