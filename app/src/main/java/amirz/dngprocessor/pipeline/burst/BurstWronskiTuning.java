package amirz.dngprocessor.pipeline.burst;

/**
 * Wronski handheld MF-SR tuning (defaults from reference configs/default.yaml + example scale=2).
 */
public final class BurstWronskiTuning {

    /** Robustness threshold t (reference default.yaml uses 0.12; 0.02 is less patchy on mobile). */
    public static final float ROBUST_T = 0.02f;
    /** Width of smoothstep above t (anti-patch; do not go below ~0.03). */
    public static final float ROBUST_SOFT_WIDTH = 0.035f;
    /** Minimum alt weight in accumulate (anti-patch). */
    public static final float ROBUST_R_FLOOR = 0.04f;

    public static final float FLOW_S1 = 2f;
    public static final float FLOW_S2 = 12f;
    public static final float FLOW_Mt = 0.8f;
    public static final float FLOW_Mt_SQUARED = FLOW_Mt * FLOW_Mt;

    public static final float K_DETAIL = 0.35f;
    public static final float K_DENOISE = 1.5f;
    public static final float D_TH = 0.001f;
    public static final float D_TR = 0.05f;
    public static final float K_STRETCH = 4f;
    public static final float K_SHRINK = 2f;

    public static final int ACC_RAD_MAX = 2;
    public static final float ACC_MAX_MULTIPLIER = 8f;
    public static final int ACC_MAX_FRAME_COUNT = 2;
    public static final boolean ENABLE_GAT = false;

    public static final int NOISE_LUT_SIZE = 1001;

    private BurstWronskiTuning() {}
}
