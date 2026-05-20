package amirz.dngprocessor.params;

import amirz.dngprocessor.Preferences;

public class ProcessParams {
    public static ProcessParams getPreset(Preferences.PostProcessMode mode) {
        ProcessParams process = new ProcessParams();
        switch (mode) {
            case Disabled:
                process.sharpenFactor = 0f;
                process.denoiseFactor = 0;
                process.histFactor = 0f;
                process.adaptiveSaturation = new float[] { 0f, 1f };
                process.lceMultiScale = false;
                process.varianceLimiting = false;  // No variance-based limiting in Disabled mode
                break;
            case Classic:
                // Classic mode: Original amirz LCE without variance limiting
                // Keeps modern Reinhard HDR tonemapping for better highlight handling
                process.sharpenFactor = 0f;  // Sharpening is now additive via slider, default 0
                process.denoiseFactor = 0;  // Noise reduction is now additive via slider, default 0
                process.histFactor = 0.6f;
                process.adaptiveSaturation = new float[] { 3.0f, 4f };  // Strengthened from 2.5f
                process.lceMultiScale = false;
                process.varianceLimiting = false;  // Original didn't have variance limiting
                break;
            case Natural:
                process.sharpenFactor = 0f;  // Sharpening is now additive via slider, default 0
                process.denoiseFactor = 0;  // Noise reduction is now additive via slider, default 0
                process.histFactor = 0.6f;
                process.adaptiveSaturation = new float[] { 3.0f, 4f };  // Strengthened from 2.5f
                process.lceMultiScale = false;
                process.varianceLimiting = true;   // Enable variance-based limiting
                break;
            case Boosted:
                // Boosted mode: Multi-scale LCE inspired by convert_uraw script
                // Uses 6 blur scales instead of 3 for better shadow/highlight handling
                process.sharpenFactor = 0f;  // Sharpening is now additive via slider, default 0
                process.denoiseFactor = 0;  // Noise reduction is now additive via slider, default 0
                process.histFactor = 0.8f;
                process.adaptiveSaturation = new float[] { 3.0f, 4f };  // Strengthened from 2.5f
                process.lceMultiScale = true;  // Enable 6-scale LCE
                process.varianceLimiting = true;  // Enable variance-based limiting
                break;
            case MAT:
                // MAT mode: Subtle sigmoidal curve + hue shifts for natural look
                // - Sigmoidal tone curve for gentle contrast
                // - Green tones near yellow shift towards yellow (keeping saturated greens)
                // - Yellow tones near red shift slightly toward magenta
                // - Multi-scale LCE (like Boosted mode) for better shadow/highlight handling
                process.sharpenFactor = 0f;  // Sharpening is now additive via slider, default 0
                process.denoiseFactor = 0;  // Noise reduction is now additive via slider, default 0
                process.histFactor = 0.5f;
                process.adaptiveSaturation = new float[] { 2.5f, 3.5f };  // Strengthened from 2.0f
                process.lceMultiScale = true;  // Enable 6-scale LCE (like Boosted mode)
                process.varianceLimiting = true;
                process.matMode = true;
                break;
            case LeicaM9:
                // Leica M9 mode: Emulates the Kodak KAF-18500 CCD sensor characteristics
                // - Smooth highlight rolloff (CCD signature)
                // - Warm, slightly magenta-shifted reds ("Leica red")
                // - Film-like tonal transitions
                // - Natural saturation without over-processing
                // - Classic rendering without heavy local contrast enhancement
                process.sharpenFactor = 0f;  // Sharpening via slider, default 0
                process.denoiseFactor = 0;  // Noise reduction via slider, default 0
                process.histFactor = 0.4f;  // Subtle LCE for classic look
                process.adaptiveSaturation = new float[] { 2.0f, 3.0f };  // Natural saturation
                process.lceMultiScale = false;  // Classic single-scale LCE
                process.varianceLimiting = true;  // Smooth limiting for film-like look
                process.leicaM9Mode = true;  // Enable Leica M9 specific processing
                break;
        }
        return process;
    }

    public float sharpenFactor;
    public float histFactor;
    public float histCurve;

    public int denoiseFactor;
    public float[] saturationMap;
    public float satLimit;
    public float[] adaptiveSaturation;
    public boolean exposeFuse;
    public String exposeFusionMethod = "mertens";  // "mertens", "fullmertens" or "laplacian"
    public boolean lce;
    public String lceMethod = "lce";  // "lce", "clahe", or "fastclahe" - applies to all scales
    
    // LCE parameters (from preferences)
    public float lceRadiusWeak = 10.0f;
    public float lceRadiusMedium = 20.0f;
    public float lceRadiusStrong = 33.0f;
    public float lceRadiusXfine = 0.22f;
    public float lceRadiusFine = 0.5f;
    public float lceRadiusXstrong = 100.0f;
    
    public float lceStrengthWeak = 0.15f;
    public float lceStrengthMedium = 0.25f;
    public float lceStrengthStrong = 0.5f;
    public float lceStrengthXfine = 0.1f;
    public float lceStrengthFine = 0.1f;
    public float lceStrengthXstrong = 0.25f;
    
    public float lceLimitWeak = 0.7f;
    public float lceLimitMedium = 0.6f;
    public float lceLimitStrong = 0.5f;
    public float lceLimitXfine = 0.8f;
    public float lceLimitFine = 0.75f;
    public float lceLimitXstrong = 0.5f;
    
    public boolean ahe;
    public boolean edgeAwareHistEq;  // Enable edge-aware histogram equalization using bilateral filter
    public boolean noiseReduce;  // Enable pyramid denoising
    public boolean forceNoiseReduction;  // Force noise reduction at full strength regardless of EXIF metadata
    public boolean waveletNoiseReduction;  // Use edge-aware wavelet denoising (darktable-style) instead of bilateral filtering
    public boolean lceMultiScale;  // Enable 6-scale LCE for Boosted and MAT modes
    public boolean varianceLimiting;  // Enable variance-based LCE limiting (CLAHE-like)
    public boolean matMode;  // Enable MAT mode color processing
    public boolean matGreenToYellowShift;  // Enable green to yellow hue shift in MAT mode
    public boolean matYellowToWarmShift;  // Enable yellow to warm hue shift in MAT mode
    public boolean leicaM9Mode;  // Enable Leica M9 CCD sensor emulation
    public boolean useReferencePreview;  // Use embedded JPEG as tone matching reference
    public float histMatchStrength = 0.7f;  // Histogram matching strength (0.0 = no matching, 1.0 = full matching)
    public String demosaicingMethod = "bilinear";  // Demosaicing method: "bilinear", "dht", or "aahd"
    public int baselineExposureCompression;  // Compression method: 0=None, 1=Reinhard, 2=ACES Filmic, 3=Uncharted 2, 4=Improved Rational, 5=Gradient Domain, 6=Hejl-Dawson, 7=Modified ACES, 8=Reinhard-Jodie, 9=Lottes, 10=Gamma-Based, 11=Gamma+ACES Fusion, 12=Exposure Slider, 13=Sigmoidal, 14=Piecewise, 15=Histogram Match, 16=LibRaw exp_bef, 17=Exposure Fusion
    public int hdrCompressionMethod;  // HDR compression method: 0=Reinhard, 1=ACES Filmic, 2=Uncharted 2, 3=Improved Rational, 4=Gamma+ACES Fusion, 5=Late Exposure Fusion
    
    // CLAHE (Contrast Limited Adaptive Histogram Equalization) parameters
    public int claheTileSize = 8;      // Tile size for CLAHE (default 8x8)
    public float claheClipLimit = 3.0f; // Contrast clip limit (default 3.0 = 3x average)
    public float claheStrength = 0.0f;   // CLAHE strength (0.0 = disabled, 1.0 = full)

    // Tone adjustments (Lightroom-style)
    public float toneExposure;      // -2.0 to +2.0 EV
    public float toneHighlights;    // -100 to +100
    public float toneShadows;       // -100 to +100
    public float toneWhites;        // -100 to +100
    public float toneContrast;      // -100 to +100
    public float toneBlacks;        // -100 to +100
    public float toneTexture;       // -100 to +100
    public float toneClarity;       // -100 to +100
    public float toneDehaze;        // -100 to +100
    public float toneVibrance;      // -100 to +100
    public float toneSaturation;    // -100 to +100
    
    // User-defined tone curve (array of [x0, y0, x1, y1, ...] control points)
    // null means no custom curve, use default processing
    public float[] userToneCurve;
    
    // Whether tone curves should be applied (controlled by user toggle)
    // Default matches preference default (false)
    public boolean toneCurveEnabled = false;
    
    // Output base name for saving debug frames (e.g., "IMG_1234" without extension)
    // Set by DngParser before pipeline execution
    public String outputBaseName = null;
    
    // Whether Ultra HDR JPEG output is enabled
    // Used to enable synthetic HDR expansion for scenes without natural HDR content
    public boolean ultraHdrEnabled = false;
    
    // Color Transform matrix (3x3, row-major: RR, RG, RB, GR, GG, GB, BR, BG, BB)
    // Identity matrix by default
    public float[] colorTransform = new float[] {
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    };
    
    // External LUT file path (e.g., .cube file) for creative color grading
    // Applied after DNG Profile LookTable (if present) and before tone adjustments
    // null means no external LUT is applied
    // Can be a file path (String) or URI (String starting with content:// or file://)
    public String externalLutPath = null;
    
    // Android Context for accessing ContentResolver (needed for URI-based LUT files)
    // Set by DngParser before pipeline execution
    public android.content.Context context = null;
    
    // Local Laplacian Filter parameters (replaces exposure fusion + LCE for better results)
    // Ported from darktable's local_laplacian mode in bilat.c
    public boolean localLaplacianEnabled = false;  // Enable Local Laplacian filter
    public float localLaplacianShadows = 1.0f;     // Shadow boost (1.0 = no change, >1 = lift shadows)
    public float localLaplacianHighlights = 1.0f;  // Highlight compression (1.0 = no change, <1 = compress)
    public float localLaplacianClarity = 0.1f;    // Local contrast/midtone detail (0.0 = none, 1.0 = strong) - reduced from 0.25 for less contrasty results
    public float localLaplacianSigma = 0.2f;       // Transition width shadows/midtones/highlights
    public boolean localLaplacianAutoTune = true; // Auto-tune parameters based on histogram
    
    // Tone Equalizer parameters (EV-band based exposure adjustment)
    // Ported from darktable's toneequal.c
    public boolean toneEqualizerEnabled = false;   // Enable Tone Equalizer
    public float toneEqBlacks = 0.0f;              // -8 EV band adjustment (-2.0 to +2.0 stops)
    public float toneEqDeepShadows = 0.0f;         // -7 EV band adjustment
    public float toneEqShadows = 0.0f;             // -6 EV band adjustment
    public float toneEqLightShadows = 0.0f;        // -5 EV band adjustment
    public float toneEqMidtones = 0.0f;            // -4 EV band adjustment
    public float toneEqDarkHighlights = 0.0f;      // -3 EV band adjustment
    public float toneEqHighlights = 0.0f;          // -2 EV band adjustment
    public float toneEqWhites = 0.0f;              // -1 EV band adjustment
    public float toneEqSpeculars = 0.0f;           // 0 EV band adjustment
    public float toneEqSmoothing = 5.0f;           // Smoothing diameter (%)
    public float toneEqFeathering = 1.0f;          // Edge feathering strength
    public boolean toneEqualizerAutoTune = true;  // Auto-tune EV bands based on histogram

    // Burst / multi-frame HDR+SR processing
    public boolean burstEnabled = false;
    public int burstScaleFactor = 2;         // SR upscale factor (1 = HDR merge only, 2 = 2x SR)

    /**
     * Burst merge mode:
     * <ul>
     *   <li>{@code "auto"}        — pick {@code hdr_bracket} when frames differ in EV ≥ 0.25,
     *       otherwise {@code sr_equal}.</li>
     *   <li>{@code "sr_equal"}    — equal-weight Wronski SR (uniform-exposure handheld stack).</li>
     *   <li>{@code "hdr_bracket"} — joint HDR + SR for bracketed exposures: merge into the darkest
     *       frame's EV, exposure-aware weights with highlight protection
     *       (hdr-plus-swift {@code add_texture_exposure} style).</li>
     * </ul>
     */
    public String burstMode = "auto";

    /**
     * Per-frame relative EV vs the reference frame (frame 0 after burst reordering).
     * Length = number of frames. Populated by {@link amirz.dngprocessor.parser.BurstParser}
     * after bracket detection. Always &ge; 0; reference frame is 0; brighter frames are positive.
     * {@code null} (or all-zero) when the burst is uniform-exposure.
     */
    public float[] burstRelativeEv = null;

    /** True when the burst is bracketed (frames span ≥ {@code BURST_BRACKET_EV_THRESHOLD} EV). */
    public boolean burstBracketed = false;

    /**
     * Post-merge linear exposure boost (in EV stops) applied by the burst
     * merge stages to lift the merged darkest-frame-space output back toward a
     * "normal-exposure" look. For bracketed bursts this defaults to the median
     * relative EV of the burst (clipped to 2 stops so we don't blow out the
     * merged highlights again).
     */
    public float burstPostExposureBoostEv = 0f;

    /** Bracket-detection threshold (EV). Frames spanning at least this differ → bracketed burst. */
    public static final float BURST_BRACKET_EV_THRESHOLD = 0.25f;

    private ProcessParams() {
    }
}
