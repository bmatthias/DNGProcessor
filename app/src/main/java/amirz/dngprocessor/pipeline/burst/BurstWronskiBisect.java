package amirz.dngprocessor.pipeline.burst;

/**
 * Bisection flags for the Wronski 40% → 100% plan ({@code full_wronski_mf-sr}).
 * <p>
 * Workflow: Wave 3 stable (robustness fix + Hann). Avoid wave-4 halo experiments.
 * <p>
 * Inspect logcat tag {@code BurstStagePipeline} for the active flag summary.
 */
public final class BurstWronskiBisect {

    /** When true, enables every feature below (production target). */
    public static final boolean ENABLE_PLAN_IMPROVEMENTS = false;

    // -------------------------------------------------------------------------
    // Wave 3: full plan except master switch (robustness uses fixed warp upscale).
    // -------------------------------------------------------------------------

    private static final boolean BISECT_HANN_TILE_FLOW = true;
    private static final boolean BISECT_PER_FRAME_COV = true;
    private static final boolean BISECT_STEERABLE_ACCUM = true;
    private static final boolean BISECT_ROBUSTNESS = true;
    private static final boolean BISECT_ROBUSTNESS_FULLRES = true;
    private static final boolean BISECT_MERGE_REF = true;
    private static final boolean BISECT_ACC_ROB_DENOISE = true;
    private static final boolean BISECT_POST_SHARPEN = true;

    /** Hann 4-corner tile flow in accumulate (vs single-tile floor shift). */
    public static final boolean ENABLE_HANN_TILE_FLOW =
            ENABLE_PLAN_IMPROVEMENTS || BISECT_HANN_TILE_FLOW;

    /** Per-frame {@code estimate_kernels} Ω textures (Alg. 5). */
    public static final boolean ENABLE_PER_FRAME_COV =
            ENABLE_PLAN_IMPROVEMENTS || BISECT_PER_FRAME_COV;

    /** Steerable Ω splat in accumulate / merge_ref (requires per-frame cov). */
    public static final boolean ENABLE_STEERABLE_ACCUM =
            (ENABLE_PLAN_IMPROVEMENTS || BISECT_STEERABLE_ACCUM) && ENABLE_PER_FRAME_COV;

    /** Full robustness path (guide, stats, noise LUT, pool). */
    public static final boolean ENABLE_ROBUSTNESS =
            ENABLE_PLAN_IMPROVEMENTS || BISECT_ROBUSTNESS;

    /** Upscale robustness + accRob to full Bayer res (vs half-res blocks). */
    public static final boolean ENABLE_ROBUSTNESS_FULLRES =
            (ENABLE_PLAN_IMPROVEMENTS || BISECT_ROBUSTNESS_FULLRES) && ENABLE_ROBUSTNESS;

    /** Separate Alg. 11 merge_ref pass (vs accumulating ref in the main loop). */
    public static final boolean ENABLE_MERGE_REF =
            ENABLE_PLAN_IMPROVEMENTS || BISECT_MERGE_REF;

    /** acc_r adaptive ref splat in merge_ref. */
    public static final boolean ENABLE_ACC_ROB_DENOISE =
            (ENABLE_PLAN_IMPROVEMENTS || BISECT_ACC_ROB_DENOISE)
                    && ENABLE_ROBUSTNESS && ENABLE_MERGE_REF;

    /** CFA unsharp after normalize ({@link BurstBayerSrSharpen}). */
    public static final boolean ENABLE_POST_SHARPEN =
            ENABLE_PLAN_IMPROVEMENTS || BISECT_POST_SHARPEN;

    private BurstWronskiBisect() {}

    /** One-line summary for logcat. */
    public static String flagsSummary() {
        return String.format(
                "WronskiBisect planAll=%s hann=%s cov=%s steer=%s robust=%s robustFull=%s "
                        + "mergeRef=%s accR=%s sharpen=%s",
                ENABLE_PLAN_IMPROVEMENTS,
                ENABLE_HANN_TILE_FLOW,
                ENABLE_PER_FRAME_COV,
                ENABLE_STEERABLE_ACCUM,
                ENABLE_ROBUSTNESS,
                ENABLE_ROBUSTNESS_FULLRES,
                ENABLE_MERGE_REF,
                ENABLE_ACC_ROB_DENOISE,
                ENABLE_POST_SHARPEN);
    }
}
