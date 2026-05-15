package amirz.dngprocessor.pipeline.post;

import android.graphics.Bitmap;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

import amirz.dngprocessor.R;

import amirz.dngprocessor.Preferences;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.params.SensorParams;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;
import amirz.dngprocessor.pipeline.exposefuse.EarlyExposureFusion;
import amirz.dngprocessor.pipeline.exposefuse.Merge;
import amirz.dngprocessor.pipeline.exposefuse.LateExposureFusion;
import amirz.dngprocessor.pipeline.intermediate.Analysis;
import amirz.dngprocessor.pipeline.intermediate.CLAHE;
import amirz.dngprocessor.ui.AdaptiveSaturationCurveActivity;
import amirz.dngprocessor.util.LutParser;

import static amirz.dngprocessor.colorspace.ColorspaceConstants.CUSTOM_ACR3_TONEMAP_CURVE_COEFFS;
import static android.opengl.GLES20.*;

public class ToneMap extends Stage {
    private static final String TAG = "ToneMap";
    
    // Size of the tone curve LUT texture
    private static final int TONE_CURVE_LUT_SIZE = 256;

    private final int[] mFbo = new int[1];
    private final float[] mXYZtoProPhoto, mProPhotoToSRGB;

    private final int ditherSize = 128;
    private final byte[] dither = new byte[ditherSize * ditherSize * 2];

    private Texture mDitherTex;
    private Texture mSatTex;
    private Texture mToneCurveTex;
    private Texture mHueSatMapTex;
    private Texture mLookTableTex;
    private Texture mExternalLutTex;
    private Texture mPreviewTex;

    public ToneMap(float[] XYZtoProPhoto, float[] proPhotoToSRGB) {
        mXYZtoProPhoto = XYZtoProPhoto;
        mProPhotoToSRGB = proPhotoToSRGB;
    }
    
    /**
     * Build a 2D texture from the 3D ProfileLookTable data.
     * The 3D LUT is flattened: width = cols * depth, height = rows
     * Each pixel contains [R, G, B] output values.
     * 
     * The LookTable is a direct 3D RGB->RGB mapping:
     * - Input RGB indexes into the 3D table
     * - Output is the stored RGB at that position
     * 
     * @param dims [depth, rows, cols] (typically [17, 17, 17] or [33, 33, 33])
     * @param data Array of [R, G, B] triplets for each cell
     * @return float array ready for RGB texture (3 channels per pixel)
     */
    private float[] buildLookTableTexture(int[] dims, float[] data) {
        int depth = dims[0];  // Blue axis
        int rows = dims[1];   // Green axis  
        int cols = dims[2];   // Red axis
        
        // Texture layout: width = cols * depth, height = rows
        int texWidth = cols * depth;
        int texHeight = rows;
        
        float[] texData = new float[texWidth * texHeight * 3];
        
        // DNG LUT data is stored as: for each blue, for each green, for each red: [R, G, B]
        int srcIdx = 0;
        for (int b = 0; b < depth; b++) {
            for (int g = 0; g < rows; g++) {
                for (int r = 0; r < cols; r++) {
                    // Destination position in 2D texture
                    int texX = r * depth + b;
                    int texY = g;
                    int dstIdx = (texY * texWidth + texX) * 3;
                    
                    // Copy R, G, B output values
                    texData[dstIdx + 0] = data[srcIdx + 0];
                    texData[dstIdx + 1] = data[srcIdx + 1];
                    texData[dstIdx + 2] = data[srcIdx + 2];
                    
                    srcIdx += 3;
                }
            }
        }
        
        return texData;
    }
    
    /**
     * Build a 2D texture from the 3D ProfileHueSatMap data.
     * The 3D map is flattened: width = hueDivs * valDivs, height = satDivs
     * Each pixel contains [deltaH, deltaS, deltaV] as RGB.
     * 
     * The HueSatMap adjusts colors based on input HSV:
     * - Index by [hue, sat, val] to get [deltaHue, deltaSat, deltaVal]
     * - Output = input + delta
     * 
     * @param dims [hueDivisions, satDivisions, valDivisions]
     * @param data Array of [dH, dS, dV] triplets for each cell
     * @return float array ready for RGB texture (3 channels per pixel)
     */
    private float[] buildHueSatMapTexture(int[] dims, float[] data) {
        int hueDivs = dims[0];
        int satDivs = dims[1];
        int valDivs = dims[2];
        
        // Texture layout: width = hueDivs * valDivs, height = satDivs
        // This allows efficient 2D sampling with manual Z interpolation
        int texWidth = hueDivs * valDivs;
        int texHeight = satDivs;
        
        float[] texData = new float[texWidth * texHeight * 3];
        
        // Reorder data from [h][s][v] indexing to 2D texture layout
        // DNG data is stored as: for each hue, for each sat, for each val: [dH, dS, dV]
        int srcIdx = 0;
        for (int h = 0; h < hueDivs; h++) {
            for (int s = 0; s < satDivs; s++) {
                for (int v = 0; v < valDivs; v++) {
                    // Destination position in 2D texture
                    int texX = h * valDivs + v;
                    int texY = s;
                    int dstIdx = (texY * texWidth + texX) * 3;
                    
                    // Copy deltaH, deltaS, deltaV
                    texData[dstIdx + 0] = data[srcIdx + 0]; // deltaH
                    texData[dstIdx + 1] = data[srcIdx + 1]; // deltaS
                    texData[dstIdx + 2] = data[srcIdx + 2]; // deltaV
                    
                    srcIdx += 3;
                }
            }
        }
        
        return texData;
    }
    
    /**
     * Convert curve control points (array of [x,y] pairs) to a 1D LUT texture.
     * Uses cubic spline interpolation for smooth curves that match the UI editor.
     * 
     * @param curvePoints Array of [x0, y0, x1, y1, ...] where x and y are in [0, 1]
     * @return float array of size TONE_CURVE_LUT_SIZE with interpolated y values
     */
    private float[] buildToneCurveLUT(float[] curvePoints) {
        float[] lut = new float[TONE_CURVE_LUT_SIZE];
        int n = curvePoints.length / 2;
        
        if (n < 2) {
            // Invalid curve, return identity
            for (int i = 0; i < TONE_CURVE_LUT_SIZE; i++) {
                lut[i] = (float) i / (TONE_CURVE_LUT_SIZE - 1);
            }
            return lut;
        }
        
        // Extract x and y arrays from interleaved data
        float[] x = new float[n];
        float[] y = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = curvePoints[i * 2];
            y[i] = curvePoints[i * 2 + 1];
        }
        
        // Compute cubic spline coefficients (natural cubic spline)
        float[] a = y.clone();
        float[] b = new float[n];
        float[] c = new float[n];
        float[] d = new float[n];
        float[] h = new float[n - 1];
        
        for (int i = 0; i < n - 1; i++) {
            h[i] = x[i + 1] - x[i];
            if (h[i] < 0.0001f) h[i] = 0.0001f; // Avoid division by zero
        }
        
        float[] alpha = new float[n - 1];
        for (int i = 1; i < n - 1; i++) {
            alpha[i] = (3f / h[i]) * (a[i + 1] - a[i]) - (3f / h[i - 1]) * (a[i] - a[i - 1]);
        }
        
        float[] l = new float[n];
        float[] mu = new float[n];
        float[] z = new float[n];
        
        l[0] = 1f;
        mu[0] = 0f;
        z[0] = 0f;
        
        for (int i = 1; i < n - 1; i++) {
            l[i] = 2f * (x[i + 1] - x[i - 1]) - h[i - 1] * mu[i - 1];
            if (Math.abs(l[i]) < 0.0001f) l[i] = 0.0001f;
            mu[i] = h[i] / l[i];
            z[i] = (alpha[i] - h[i - 1] * z[i - 1]) / l[i];
        }
        
        l[n - 1] = 1f;
        z[n - 1] = 0f;
        c[n - 1] = 0f;
        
        for (int j = n - 2; j >= 0; j--) {
            c[j] = z[j] - mu[j] * c[j + 1];
            b[j] = (a[j + 1] - a[j]) / h[j] - h[j] * (c[j + 1] + 2f * c[j]) / 3f;
            d[j] = (c[j + 1] - c[j]) / (3f * h[j]);
        }
        
        // Sample the spline at TONE_CURVE_LUT_SIZE points
        int segment = 0;
        for (int i = 0; i < TONE_CURVE_LUT_SIZE; i++) {
            float xi = (float) i / (TONE_CURVE_LUT_SIZE - 1);
            
            // Find the right segment
            while (segment < n - 2 && xi > x[segment + 1]) {
                segment++;
            }
            segment = Math.max(0, Math.min(segment, n - 2));
            
            // Evaluate cubic polynomial
            float dx = xi - x[segment];
            float yi = a[segment] + b[segment] * dx + c[segment] * dx * dx + d[segment] * dx * dx * dx;
            
            // Clamp output to [0, 1]
            lut[i] = Math.max(0f, Math.min(1f, yi));
        }
        
        return lut;
    }

    // Store original baseline exposure multiplier for HDR capture
    private float mOriginalBaselineExposure = 1.0f;
    
    // Track HDR output mode to determine which intermediate to use
    private boolean mOutputHdr = false;

    @Override
    public void init(GLPrograms converter, SensorParams sensor, ProcessParams process) {
        super.init(converter, sensor, process);

        // Save original baseline exposure multiplier (2^EV) for HDR capture
        mOriginalBaselineExposure = (float) Math.pow(2.0, sensor.baselineExposure);

        // Save output FBO.
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, mFbo, 0);
    }

    @Override
    protected void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();
        SensorParams sensor = getSensorParams();
        ProcessParams process = getProcessParams();

        // CRITICAL FIX: Explicitly reset all HDR compensation, contrast-related, and conditionally-set
        // shader uniforms at the start of each run to prevent accumulation between image processing runs.
        // This ensures that even if certain code paths are skipped, the uniforms are always reset.
        converter.setf("hdrSigmoidalContrast", 0.0f);
        converter.setf("hdrSigmoidalMidpoint", 0.5f);
        
        // Reset LCE parameters (only set conditionally, so reset to defaults)
        converter.setf("lceStrengths", 0.0f, 0.0f, 0.0f);
        converter.setf("lceStrengthsMulti", 0.0f, 0.0f, 0.0f);
        converter.setf("lceLimits", 1.0f, 1.0f, 1.0f);
        converter.setf("lceLimitsMulti", 1.0f, 1.0f, 1.0f);
        converter.setf("lceRadii", 0.0f, 0.0f, 0.0f);
        converter.setf("lceRadiiMulti", 0.0f, 0.0f, 0.0f);
        
        // Reset histogram matching arrays (only set conditionally)
        converter.seti("histogramMatchApplied", 0);
        converter.setf("histMatchMaxHdr", 1.0f);
        converter.setf("colorMatchCorrection", 0.0f, 0.0f);
        converter.seti("histMatchNumControlPoints", 0);
        // Reset histogram matching control point arrays (9 elements max)
        for (int i = 0; i < 9; i++) {
            converter.setf("histMatchControlInputs[" + i + "]", 0.0f);
            converter.setf("histMatchControlOutputs[" + i + "]", 0.0f);
            converter.setf("histMatchControlTangents[" + i + "]", 0.0f);
        }

        // Check if LateExposureFusion or HistogramMatch ran (provides RGB output, replaces xyY => RGB conversion)
        LateExposureFusion lateFusion = previousStages.getStage(LateExposureFusion.class);
        HistogramMatch histogramMatch = previousStages.getStage(HistogramMatch.class);
        Texture highRes = null;
        boolean useRgbInput = false;
        
        // When outputHDR > 0, we need uncompressed HDR data, not compressed output from
        // LateExposureFusion, HistogramMatch, or HdrCompress. Get intermediate from before compression stages.
        // Note: getIntermediateBeforeCompression() correctly skips all compression stages to find
        // the truly uncompressed intermediate (e.g., from MergeDetail or earlier stages).
        if (mOutputHdr) {
            // HDR mode: get uncompressed intermediate from before compression stages
            highRes = previousStages.getIntermediateBeforeCompression();
            if (highRes == null) {
                Log.e(TAG, "HDR mode: Could not find uncompressed intermediate before compression stages");
                // This should not happen in normal pipeline - getIntermediateBeforeCompression() should
                // find MergeDetail or another non-compression IntermediateProvider stage
                // Final fallback to most recent (may be compressed, but better than null)
                IntermediateProvider provider = previousStages.getStageByInterface(IntermediateProvider.class);
                highRes = provider != null ? provider.getIntermediate() : null;
                if (highRes != null) {
                    Log.w(TAG, "HDR mode: WARNING - Fallback to most recent intermediate (may be compressed, not ideal for HDR)");
                } else {
                    Log.e(TAG, "HDR mode: No intermediate provider found at all - this should not happen");
                }
            } else {
                Log.d(TAG, "HDR mode: Using uncompressed intermediate from before compression stages");
            }
            useRgbInput = false;  // HDR mode always uses xyY
        } else {
            // Check for RGB providers (in order of precedence: HistogramMatch, then LateExposureFusion)
            if (histogramMatch != null && histogramMatch.getRgbTex() != null) {
                useRgbInput = true;
                highRes = histogramMatch.getRgbTex();
                Log.d(TAG, "Using RGB from HistogramMatch (replaces xyY => RGB conversion)");
            } else if (lateFusion != null && lateFusion.getRgbTex() != null) {
                useRgbInput = true;
                highRes = lateFusion.getRgbTex();
                Log.d(TAG, "Using RGB from LateExposureFusion (replaces xyY => RGB conversion)");
            } else {
                // SDR mode: Get from most recent IntermediateProvider (xyY format)
                // This will be HdrCompress, LateExposureFusion (if skipRgbConversion), or previous stage
                IntermediateProvider provider = previousStages.getStageByInterface(IntermediateProvider.class);
                if (provider != null) {
                    highRes = provider.getIntermediate();
                } else {
                    Log.e(TAG, "No intermediate provider found");
                    return;
                }
            }
        }
        
        // Safety check: ensure highRes is initialized
        if (highRes == null) {
            Log.e(TAG, "Failed to get input texture - highRes is null");
            return;
        }
        
        converter.setTexture("highRes", highRes);
        converter.seti("intermediateWidth", highRes.getWidth());
        converter.seti("intermediateHeight", highRes.getHeight());
        
        // Pass flag to shader: 1 = RGB input (from LateExposureFusion or HistogramMatch), 0 = xyY input
        converter.seti("inputIsRgb", useRgbInput ? 1 : 0);

        // Load blur textures if available (needed for LCE, Texture, or Clarity)
        // Note: When CLAHE is used instead of BlurLCE, blur will be null
        BlurLCE blur = previousStages.getStage(BlurLCE.class);
        if (blur != null && blur.hasBlurs()) {
            converter.setTexture("weakBlur", blur.getWeakBlur());
            converter.setTexture("mediumBlur", blur.getMediumBlur());
            converter.setTexture("strongBlur", blur.getStrongBlur());
            
            // Extended blurs for Boosted and MAT (multi-scale) modes
            boolean boostedMode = blur.isBoostedMode();
            converter.seti("lceMultiScale", boostedMode ? 1 : 0);
            if (boostedMode) {
                converter.setTexture("xfineBlur", blur.getXfineBlur());
                converter.setTexture("fineBlur", blur.getFineBlur());
                converter.setTexture("xstrongBlur", blur.getXstrongBlur());
                Log.d(TAG, "Using 6-scale multi-scale LCE (Boosted/MAT mode)");
            }
        } else {
            converter.seti("lceMultiScale", 0);
        }

        float satLimit = getProcessParams().satLimit;
        Log.d(TAG, "Saturation limit " + satLimit);
        converter.setf("satLimit", satLimit);

        converter.setf("toneMapCoeffs", CUSTOM_ACR3_TONEMAP_CURVE_COEFFS);
        converter.setf("XYZtoProPhoto", mXYZtoProPhoto);
        converter.setf("proPhotoToSRGB", mProPhotoToSRGB);
        converter.seti("outOffset", sensor.outputOffsetX, sensor.outputOffsetY);

        // Check if LCE was actually applied (BlurLCE or CLAHE stage ran)
        // Check if stages have output (if they have output, they ran)
        BlurLCE blurLce = previousStages.getStage(BlurLCE.class);
        CLAHE clahe = previousStages.getStage(CLAHE.class);
        boolean lceActuallyRan = process.lce && ((blurLce != null && blurLce.hasBlurs()) || (clahe != null && clahe.getIntermediate() != null));
        converter.seti("lce", lceActuallyRan ? 1 : 0);
        // Set LCE method: 0 = lce (multiplicative), 1 = clahe (full, separate stage), 2 = gpu clahe (CDF-based)
        int lceMethodValue = 0;
        if ("clahe".equals(process.lceMethod)) {
            lceMethodValue = 1;
        } else if ("fastclahe".equals(process.lceMethod)) {
            lceMethodValue = 2;  // GPU CLAHE (backward compatibility: still uses "fastclahe" value)
        }
        converter.seti("lceMethod", lceMethodValue);
        converter.seti("varianceLimiting", process.varianceLimiting ? 1 : 0);
        converter.seti("matMode", process.matMode ? 1 : 0);
        converter.seti("matGreenToYellowShift", process.matGreenToYellowShift ? 1 : 0);
        converter.seti("matYellowToWarmShift", process.matYellowToWarmShift ? 1 : 0);
        converter.seti("leicaM9Mode", process.leicaM9Mode ? 1 : 0);
        
        // Set LCE parameters from preferences (only if LCE actually ran)
        if (lceActuallyRan) {
            // Standard 3-scale strengths: [weak, medium, strong]
            converter.setf("lceStrengths", 
                    process.lceStrengthWeak,
                    process.lceStrengthMedium,
                    process.lceStrengthStrong);
            
            // Multi-scale strengths: [xfine, fine, xstrong]
            converter.setf("lceStrengthsMulti",
                    process.lceStrengthXfine,
                    process.lceStrengthFine,
                    process.lceStrengthXstrong);
            
            // Standard 3-scale limits: [weak, medium, strong]
            converter.setf("lceLimits",
                    process.lceLimitWeak,
                    process.lceLimitMedium,
                    process.lceLimitStrong);
            
            // Multi-scale limits: [xfine, fine, xstrong]
            converter.setf("lceLimitsMulti",
                    process.lceLimitXfine,
                    process.lceLimitFine,
                    process.lceLimitXstrong);
            
            // Standard 3-scale radii: [weak, medium, strong] (percentages)
            converter.setf("lceRadii",
                    process.lceRadiusWeak,
                    process.lceRadiusMedium,
                    process.lceRadiusStrong);
            
            // Multi-scale radii: [xfine, fine, xstrong] (percentages)
            converter.setf("lceRadiiMulti",
                    process.lceRadiusXfine,
                    process.lceRadiusFine,
                    process.lceRadiusXstrong);
        }
        //NoiseReduce.NRParams nrParams = previousStages.getStage(NoiseReduce.class).getNRParams();
        //converter.setf("sharpenFactor", nrParams.sharpenFactor);
        //converter.setf("adaptiveSaturation", nrParams.adaptiveSaturation, nrParams.adaptiveSaturationPow);
        converter.setf("sharpenFactor", process.sharpenFactor);
        converter.setf("adaptiveSaturation", process.adaptiveSaturation);

        // Baseline exposure handling
        // 
        // Baseline exposure is now applied in PreProcess stage with falling curve compression
        // to prevent values > 1.0. For SDR output, we should NOT re-apply it here.
        // 
        // For HDR output (outputHDR enabled):
        //   We need to apply baseline exposure WITHOUT compression to preserve full dynamic range.
        //   The shader will use applyBaselineExposureUncompressed() when outputHDR is enabled.
        //
        // When exposure fusion is enabled:
        //   The DoubleExpose stage works with data that already has baseline exposure applied
        //   in PreProcess, and uses it to calculate adaptive exposure factors.
        //
        // When fusion is disabled:
        //   Baseline exposure was already applied in PreProcess with compression curve.
        //
        // For SDR: set to 1.0 to skip re-application (already handled in setHdrOutputMode)
        // The actual value will be set in setHdrOutputMode() when HDR capture is needed
        Log.d(TAG, "Baseline exposure: " + sensor.baselineExposure + " EV (mult=" + mOriginalBaselineExposure + 
                  ", already applied in PreProcess with compression curve for SDR)");

        // Check if HDR fusion was actually applied (needed for HDR compensation calculations)
        // Check if Merge stage actually ran by checking if it has output (not just if toggle is enabled)
        // Also check for EarlyExposureFusion (runs when baselineExposureCompression == 17)
        // Also check for LateExposureFusion (runs when hdrCompressionMethod == 5)
        Merge mergeStage = previousStages.getStage(Merge.class);
        EarlyExposureFusion earlyFusionStage =
                previousStages.getStage(EarlyExposureFusion.class);
        LateExposureFusion lateFusionStage = previousStages.getStage(LateExposureFusion.class);
        boolean hdrFusionApplied = (mergeStage != null && mergeStage.getMerged() != null) ||
                (earlyFusionStage != null && earlyFusionStage.isFusionApplied()) ||
                (lateFusionStage != null && lateFusionStage.isFusionApplied());

        HistogramMatch histogramMatchStage = previousStages.getStage(HistogramMatch.class);
        boolean histogramMatchApplied = histogramMatchStage != null && histogramMatchStage.isApplied();

        // Night mode detection (similar to convert_uraw script)
        // Light Value < 0 indicates a dark scene that needs special processing
        boolean isNightMode = sensor.lightValue < 0f;
        Log.d(TAG, "Night mode check: lightValue=" + sensor.lightValue + ", isNightMode=" + isNightMode + 
                  ", hdrFusionApplied=" + hdrFusionApplied);

        // Tone adjustments (Lightroom-style)
        // This section applies user-adjustable tone mapping parameters similar to Adobe Lightroom.
        // These controls allow fine-tuning of the image's tonal distribution, including:
        // - Exposure: Overall brightness adjustment (in EV units)
        // - Highlights/Shadows: Selective recovery of bright and dark areas
        // - Whites/Blacks: Setting the white and black points
        // - Contrast: Overall contrast adjustment
        // - Texture/Clarity/Dehaze: Local contrast and detail enhancement
        // - Vibrance/Saturation: Color intensity adjustments
        // All values are normalized from the -100 to +100 UI range to -1.0 to +1.0 for shader use.
        // Convert exposure from EV to multiplier: 2^EV
        float exposureMultiplier = (float) Math.pow(2.0, process.toneExposure);
        
        // Night mode: reduce tone exposure adjustment to prevent over-brightening
        // Similar to convert_uraw script which uses different processing for night images
        if (isNightMode) {
            // Scale reduction based on light value magnitude (more aggressive than baseline)
            // More aggressive reduction needed to match script's behavior
            // For LIGHT_VALUE = -1.0: reduction factor ≈ 0.6 (40% reduction)
            // For LIGHT_VALUE = -2.0: reduction factor ≈ 0.2 (80% reduction, capped at 40%)
            // For LIGHT_VALUE = -4.0: reduction factor ≈ 0.4 (60% reduction)
            float lightValueMagnitude = Math.abs(sensor.lightValue);
            float reductionFactor = Math.max(0.4f, 1.0f - lightValueMagnitude * 0.4f);
            exposureMultiplier *= reductionFactor;
            Log.d(TAG, "Tone exposure: " + process.toneExposure + " EV (night mode: reduced to " + 
                      (reductionFactor * 100) + "% for LV=" + sensor.lightValue + ")");
        } else {
            Log.d(TAG, "Tone exposure: " + process.toneExposure + " EV (mult=" + exposureMultiplier + ", no night mode)");
        }
        
        converter.setf("toneExposure", exposureMultiplier);
        Log.d(TAG, "Final toneExposure sent to shader: " + exposureMultiplier + " (from " + process.toneExposure + " EV)");
        
        // HDR Compensation:
        // Based on convert_uraw_v5.1.sh logic: HDR images need contrast boost and saturation reduction
        // The gamma compression in HDR fusion flattens contrast and boosts perceived saturation.
        // 
        // IMPORTANT: Baseline exposure compression (Reinhard, ACES, etc.) desaturates colors
        // regardless of whether fusion is enabled. Tone mapping curves compress RGB channels
        // differently, reducing saturation. We need to compensate for this.
        // 
        // Reference: convert_uraw calculates adjustments based on gamma:
        // - loggamma = log(1 + cgamma_highlights)
        // - cs_sat = saturation * (2.2 - 0.5 * loggamma^2 - pow(0.4, cgamma_highlights))
        // - Higher gamma (HDR) → lower saturation multiplier
        // - Contrast scales with gamma: higher gamma → more contrast boost needed
        //
        // For our case, baselineExposure (in EV) correlates with gamma:
        // - baselineExposure ~3 EV → gamma ~1.5-2.0 → significant compensation needed
        // - baselineExposure ~6 EV → gamma ~2.5-3.0 → strong compensation needed
        float hdrSigmoidalContrast = 0f;
        float hdrSigmoidalMidpoint = 0.5f; // Default midpoint at 50%
        float hdrSaturationReduction = 0f;
        float vibranceBoost = 0f;
        
        float baselineEV = sensor.baselineExposure;

        // Calculate gamma from baseline exposure EV (matching convert_uraw_v5.1.sh approach)
        // The script calculates: cgamma_highlights = gamma_highlights * log(mean/100) / log((HIGHRES == 1 ? 0.6 : 0.1) + midrange*exposure) / 2.2
        // For our case, we use baseline EV as a proxy for compression level
        // Baseline EV represents how much the image was compressed, which correlates with gamma
        // Formula based on script: higher baseline EV → more compression → higher gamma
        float baselineGamma = 0f;
        if (baselineEV > 0.0f) {
            // Calculate gamma from baseline EV similar to convert_uraw script
            // The script's gamma calculation depends on mean luminance, but baseline EV is a good proxy
            // For typical HDR images: baseline EV 3-6 EV → gamma 1.5-3.0
            // Using a logarithmic relationship: gamma ≈ 1.0 + log2(2^baselineEV) * factor
            // Simplified: gamma ≈ 1.0 + baselineEV * 0.3 (matches script's behavior for typical ranges)
            baselineGamma = 1.0f + baselineEV * 0.3f;
            baselineGamma = Math.max(1.0f, Math.min(baselineGamma, 3.5f)); // Clamp to reasonable range
        }
        
        // Calculate fusion gamma separately (fusion always adds compression when enabled)
        // Fusion uses underGamma = 1.2f (from DoubleExpose.java), which means pow(value, 1.2)
        // This compresses (darkens) the image to preserve highlights
        // For EarlyExposureFusion, the fusion itself adds compression similar to DoubleExpose
        float fusionGamma = hdrFusionApplied ? 1.42f : 0f;
        
        // Calculate effective gamma for saturation compensation
        // Combine fusion gamma and baseline exposure gamma
        float effectiveGamma = 0f;
        if (fusionGamma > 0f && baselineGamma > 0f) {
            // Both fusion and baseline exposure compression apply
            effectiveGamma = fusionGamma + (baselineGamma - 1.0f);
        } else if (fusionGamma > 0f) {
            // Only fusion compression applies
            effectiveGamma = fusionGamma;
        } else if (baselineGamma > 0f) {
            // Only baseline exposure compression applies
            effectiveGamma = baselineGamma;
        }
        
        // Calculate saturation compensation when compression was applied
        if (effectiveGamma > 0f) {
            // Calculate saturation compensation based on compression
            // Baseline exposure compression (Reinhard, ACES, etc.) desaturates colors
            // by compressing RGB channels differently
            float loggamma = (float) Math.log(1.0 + effectiveGamma);
            
            // Calculate saturation reduction (similar to convert_uraw's cs_sat formula)
            // Original: cs_sat = saturation * (2.2 - 0.5 * loggamma^2 - pow(0.4, cgamma_highlights))
            // We want to reduce saturation when gamma is high
            float satMultiplier = 2.2f - 0.5f * loggamma * loggamma - (float) Math.pow(0.4, effectiveGamma);
            satMultiplier = Math.max(0.5f, Math.min(1.0f, satMultiplier / 2.2f)); // Normalize to [0.5, 1.0]
            hdrSaturationReduction = (1.0f - satMultiplier) * 0.5f; // Convert to saturation reduction (0-25%)
            
            // Scale saturation reduction based on scene brightness (light value)
            // High baseline exposure can occur in different scenarios:
            // 1. Bright HDR scenes (sunsets, bright daylight) - need saturation reduction
            // 2. Dark scenes (night images) - need MORE saturation, not less
            // 3. Underexposed images that were brightened - also need more saturation
            // 
            // Solution: Scale saturation reduction based on actual scene brightness
            // Bright scenes (positive light value): full reduction
            // Dark scenes (negative light value): reduced or no reduction
            float brightnessScale;
            if (sensor.lightValue < 0f) {
                // Dark scenes: reduce the saturation reduction proportionally
                // More negative light value = less saturation reduction
                // LV = -1.0 → scale = 0.7 (30% reduction)
                // LV = -2.0 → scale = 0.4 (60% reduction)
                // LV = -4.0 → scale = 0.0 (100% reduction, no desaturation)
                float lightValueMagnitude = Math.abs(sensor.lightValue);
                brightnessScale = Math.max(0.0f, 1.0f - lightValueMagnitude * 0.3f);
                Log.d(TAG, "Dark scene (LV=" + sensor.lightValue + "): scaling saturation reduction by " + 
                      String.format("%.1f", brightnessScale * 100) + "% (fusion=" + hdrFusionApplied + ")");
            } else if (sensor.lightValue > 2.0f) {
                // Very bright scenes: full saturation reduction
                brightnessScale = 1.0f;
            } else {
                // Moderate brightness (0 < LV <= 2.0): partial reduction
                // Scale linearly from 0.5 at LV=0 to 1.0 at LV=2.0
                brightnessScale = 0.5f + (sensor.lightValue / 2.0f) * 0.5f;
                Log.d(TAG, "Moderate brightness (LV=" + sensor.lightValue + "): scaling saturation reduction by " + 
                      String.format("%.1f", brightnessScale * 100) + "% (fusion=" + hdrFusionApplied + ")");
            }
            
            hdrSaturationReduction *= brightnessScale;
            hdrSaturationReduction = hdrSaturationReduction - 0.5f;
            
            Log.d(TAG, String.format("Saturation compensation (baseline=%.2f EV, gamma≈%.2f, fusion=%s): reduction=%.1f%%",
                    baselineEV, effectiveGamma, hdrFusionApplied ? "yes" : "no",
                    hdrSaturationReduction * 100));

            float expTerm = 4.0f * effectiveGamma - 4.0f;
            float powTerm = (float) Math.pow(0.5, expTerm);
            float powTerm2 = (float) Math.pow(0.5, 1.0 - 1.0 / effectiveGamma);
            // Formula from convert_uraw_v5.1.sh line 379: 3.3 * (1 - pow(0.5, 4*gamma-4)) * gamma * pow(0.5, 1-1/gamma)
            float gammaFactor = 1.8f * (1.0f - powTerm) * effectiveGamma * powTerm2;
            hdrSigmoidalContrast = Math.max(1.0f, Math.min(6.0f, gammaFactor * 3.3f));
            // Midpoint: typically 50% (0.5), but can be adjusted based on image characteristics
            hdrSigmoidalMidpoint = 0.5f;

            if (histogramMatchApplied) {
                vibranceBoost = -hdrSaturationReduction;

                // Reduce sigmoidal contrast proportionally to histogram match strength.
                // Histogram matching already handles tone mapping to match the reference JPEG,
                // so we reduce the sigmoidal contrast compensation to avoid double-processing.
                // Formula: linearly interpolate from full contrast (strength=0) to minimal contrast (strength=1)
                // At strength=0.0: use full calculated contrast
                // At strength=1.0: use minimal contrast (1.0, the minimum allowed)
                float reductionFactor = 1.0f - process.histMatchStrength;
                hdrSigmoidalContrast = Math.max(1.0f, 1.0f + (hdrSigmoidalContrast - 1.0f) * reductionFactor);
            }

            Log.d(TAG, String.format("HDR compensation (baseline=%.2f EV, baselineGamma=%.2f, fusionGamma=%.2f, effectiveGamma=%.2f, night=%s): " +
                            "sigmoidal-contrast=%.2f@%.0f%%, saturation-%.1f",
                    baselineEV, baselineGamma, fusionGamma, effectiveGamma, isNightMode ? "yes" : "no",
                    hdrSigmoidalContrast, hdrSigmoidalMidpoint * 100,
                    hdrSaturationReduction * 100));
        }
        
        // Pass HDR adjustments to shader (applied only for HDR images)
        converter.setf("hdrSigmoidalContrast", hdrSigmoidalContrast);
        converter.setf("hdrSigmoidalMidpoint", hdrSigmoidalMidpoint);
        
        // Pass HDR compression method to shader
        converter.seti("hdrCompressionMethod", process.hdrCompressionMethod);
        
        // Pass night mode flag to shader (for special processing)
        converter.seti("isNightMode", isNightMode ? 1 : 0);
        if (isNightMode) {
            Log.d(TAG, "Night mode processing enabled (Light Value: " + sensor.lightValue + ")");
        }
        
        // Normalize -100 to +100 range to -1 to +1
        // Note: HDR contrast boost is applied separately as sigmoidal contrast, not added to toneContrast
        float contrastDefault = 0.0f;
        float vibranceDefault = 0.0f;
        float saturationDefault = 0.0f;
        float dehazeDefault = 0.0f;

        // Calculate adaptive vibrance based on lightValue and baselineExposure
        // Formula: (lightValue - 2^baselineEV - 7 + offset) * a * b
        // where offset, a, and b are curves (polynomials)
        float adaptiveVibrance = 0.0f;
        if (process.context != null) {
            android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(process.context);
            Preferences pref = Preferences.global();
            
            if (pref.adaptiveSaturationCurveEnabled.get()) {
                // Calculate base value: lightValue - 2^baselineEV - 5
                float baseValue = sensor.lightValue - mOriginalBaselineExposure - 7.0f;
                
                // Get offset from slider (not a curve)
                float offset = pref.adaptiveSaturationPower.get();
                
                // Look up multiplier A (baselineExposure → multiplier a)
                float multiplierA = AdaptiveSaturationCurveActivity.lookupMultiplierA(
                        prefs, sensor.baselineExposure);
                
                // Look up multiplier B (lightValue → multiplier b)
                float multiplierB = AdaptiveSaturationCurveActivity.lookupMultiplierB(
                        prefs, sensor.lightValue);
                
                // Calculate adaptive vibrance: (lightValue - 2^baselineEV - 7 + offset) * a * b
                float adjustedBase = baseValue * multiplierA * multiplierB + offset;
                if (adjustedBase > 0.0f) {
                    adaptiveVibrance = adjustedBase;

                    Log.d(TAG, "Adaptive vibrance: baseValue=" + baseValue + 
                              " (LV=" + sensor.lightValue + " - 2^" + sensor.baselineExposure + 
                              "=" + mOriginalBaselineExposure + " - 7), offset=" + offset +
                              ", multiplierA=" + multiplierA + ", multiplierB=" + multiplierB +
                              ", vibrance=" + adaptiveVibrance);
                }
            }
        }
        
        // Convert adaptive vibrance to -1 to +1 range for shader
        // Scale: assuming vibrance values are typically 0-10, divide by 100 to get reasonable range
        float adaptiveVibranceNormalized = adaptiveVibrance / 10.0f;

        converter.setf("toneHighlights", process.toneHighlights / 100f);
        converter.setf("toneShadows", process.toneShadows / 100f);
        converter.setf("toneWhites", process.toneWhites / 100f);
        converter.setf("toneContrast", process.toneContrast / 100f + contrastDefault);
        converter.setf("toneBlacks", process.toneBlacks / 100f);
        converter.setf("toneTexture", process.toneTexture / 100f);
        converter.setf("toneClarity", process.toneClarity / 100f);
        converter.setf("toneDehaze", process.toneDehaze / 100f + dehazeDefault);
        converter.setf("toneVibrance", process.toneVibrance / 100f + vibranceDefault + adaptiveVibranceNormalized + vibranceBoost);
        converter.setf("toneSaturation", process.toneSaturation / 100f + saturationDefault - hdrSaturationReduction);
        Log.d(TAG, "Tone adjustments: exposure=" + process.toneExposure + "EV, " +
                "highlights=" + process.toneHighlights + ", shadows=" + process.toneShadows +
                ", whites=" + process.toneWhites + ", contrast=" + (process.toneContrast + (contrastDefault * 100)) +
                ", blacks=" + process.toneBlacks + ", texture=" + process.toneTexture +
                ", clarity=" + process.toneClarity + ", dehaze=" + process.toneDehaze +
                ", vibrance=" + (process.toneVibrance + (vibranceDefault * 100) + (adaptiveVibranceNormalized * 100) + (vibranceBoost * 100)) +
                ", saturation=" + (process.toneSaturation + (saturationDefault * 100) - hdrSaturationReduction * 100));

        // Color Transform matrix
        converter.setf("colorTransform", process.colorTransform);

        float[] saturation = process.saturationMap;
        float[] sat = new float[saturation.length + 1];
        System.arraycopy(saturation, 0, sat, 0, saturation.length);
        sat[saturation.length] = saturation[0];

        // Must use direct buffer for OpenGL ES
        FloatBuffer satBuffer = ByteBuffer.allocateDirect(sat.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        satBuffer.put(sat);
        satBuffer.rewind();
        
        mSatTex = new Texture(sat.length, 1, 1, Texture.Format.Float16,
                satBuffer, GL_LINEAR, GL_CLAMP_TO_EDGE);
        converter.setTexture("saturation", mSatTex);

        // Fill with noise - must use direct buffer for OpenGL ES
        new Random(8682522807148012L).nextBytes(dither);
        ByteBuffer ditherBuffer = ByteBuffer.allocateDirect(dither.length);
        ditherBuffer.put(dither);
        ditherBuffer.rewind();
        mDitherTex = new Texture(ditherSize, ditherSize, 1, Texture.Format.UInt16,
                ditherBuffer);
        converter.setTexture("ditherTex", mDitherTex);
        converter.seti("ditherSize", ditherSize);

        // Tone Curve (User-defined takes priority, then DNG profile, then default)
        // Only apply tone curves if enabled by user toggle
        if (process.toneCurveEnabled) {
            if (process.userToneCurve != null && process.userToneCurve.length >= 4) {
                // User-defined tone curve from the curve editor
                float[] lutData = buildToneCurveLUT(process.userToneCurve);
                // Must use direct buffer for OpenGL ES
                FloatBuffer lutBuffer = ByteBuffer.allocateDirect(lutData.length * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer();
                lutBuffer.put(lutData);
                lutBuffer.rewind();
                mToneCurveTex = new Texture(TONE_CURVE_LUT_SIZE, 1, 1, Texture.Format.Float16,
                        lutBuffer, GL_LINEAR, GL_CLAMP_TO_EDGE);
                converter.setTexture("profileToneCurve", mToneCurveTex);
                converter.seti("hasProfileToneCurve", 1);
                Log.d(TAG, "Using USER tone curve (" + (process.userToneCurve.length / 2) + " control points)");
            } else if (sensor.hasToneCurve()) {
                // DNG Profile Tone Curve
                float[] lutData = buildToneCurveLUT(sensor.profileToneCurve);
                Log.d(TAG, "Using DNG ProfileToneCurve (" + (sensor.profileToneCurve.length / 2) + " points)");
                // Must use direct buffer for OpenGL ES
                FloatBuffer lutBuffer = ByteBuffer.allocateDirect(lutData.length * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer();
                lutBuffer.put(lutData);
                lutBuffer.rewind();
                mToneCurveTex = new Texture(TONE_CURVE_LUT_SIZE, 1, 1, Texture.Format.Float16,
                        lutBuffer, GL_LINEAR, GL_CLAMP_TO_EDGE);
                converter.setTexture("profileToneCurve", mToneCurveTex);
                converter.seti("hasProfileToneCurve", 1);
                Log.d(TAG, "Using DNG ProfileToneCurve (" + (sensor.profileToneCurve.length / 2) + " points)");
            } else {
                Log.d(TAG, "No profile tone curve available, using polynomial");
                converter.seti("hasProfileToneCurve", 0);
            }
        } else {
            Log.d(TAG, "Tone curve disabled by user toggle, using polynomial");
            converter.seti("hasProfileToneCurve", 0);
        }
        
        // DNG Profile HueSatMap
        // Per-hue HSL adjustments - this is how camera manufacturers create their signature look
        if (sensor.hasHueSatMap()) {
            int[] dims = sensor.profileHueSatMapDims;
            int hueDivs = dims[0];
            int satDivs = dims[1];
            int valDivs = dims[2];
            
            // Use illuminant 1 data (TODO: interpolate between 1 and 2 based on white balance)
            float[] texData = buildHueSatMapTexture(dims, sensor.profileHueSatMapData1);
            
            int texWidth = hueDivs * valDivs;
            int texHeight = satDivs;
            
            // Must use direct buffer for OpenGL ES
            FloatBuffer hueSatBuffer = ByteBuffer.allocateDirect(texData.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            hueSatBuffer.put(texData);
            hueSatBuffer.rewind();
            
            mHueSatMapTex = new Texture(texWidth, texHeight, 3, Texture.Format.Float16,
                    hueSatBuffer, GL_LINEAR, GL_CLAMP_TO_EDGE);
            converter.setTexture("profileHueSatMap", mHueSatMapTex);
            converter.seti("hasProfileHueSatMap", 1);
            converter.seti("hueSatMapDims", hueDivs, satDivs, valDivs);
            Log.d(TAG, "Using DNG ProfileHueSatMap (" + hueDivs + "x" + satDivs + "x" + valDivs + ")");
        } else {
            converter.seti("hasProfileHueSatMap", 0);
        }
        
        // DNG Profile LookTable
        // 3D RGB->RGB color LUT for final color grading/look
        if (sensor.hasLookTable()) {
            int[] dims = sensor.profileLookTableDims;
            int depth = dims[0];  // Blue axis
            int rows = dims[1];   // Green axis
            int cols = dims[2];   // Red axis
            
            float[] texData = buildLookTableTexture(dims, sensor.profileLookTableData);
            
            int texWidth = cols * depth;
            int texHeight = rows;
            
            // Must use direct buffer for OpenGL ES
            FloatBuffer lookBuffer = ByteBuffer.allocateDirect(texData.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            lookBuffer.put(texData);
            lookBuffer.rewind();
            
            mLookTableTex = new Texture(texWidth, texHeight, 3, Texture.Format.Float16,
                    lookBuffer, GL_LINEAR, GL_CLAMP_TO_EDGE);
            converter.setTexture("profileLookTable", mLookTableTex);
            converter.seti("hasProfileLookTable", 1);
            converter.seti("lookTableDims", cols, rows, depth);
            // 0 = linear encoding, 1 = sRGB encoding
            converter.seti("lookTableEncoding", sensor.profileLookTableEncoding);
            Log.d(TAG, "Using DNG ProfileLookTable (" + cols + "x" + rows + "x" + depth + 
                       ", encoding=" + (sensor.profileLookTableEncoding == 0 ? "linear" : "sRGB") + ")");
        } else {
            converter.seti("hasProfileLookTable", 0);
        }
        
        // External LUT file (e.g., .cube format)
        // Applied after DNG Profile LookTable (if present) and before tone adjustments
        if (process.externalLutPath != null && !process.externalLutPath.isEmpty()) {
            // Get Android context from ProcessParams (set by DngParser)
            android.content.Context context = process.context;
            if (context == null) {
                Log.w(TAG, "Context not available for LUT loading, skipping external LUT");
                converter.seti("hasExternalLut", 0);
            } else {
                LutParser.ParsedLut parsedLut = LutParser.parseCubeFile(context, process.externalLutPath);
            
            if (parsedLut != null) {
                // Build texture data using the same layout as DNG Profile LookTable
                // The .cube format stores data as: for each blue, for each green, for each red
                int[] dims = new int[] { parsedLut.depth, parsedLut.rows, parsedLut.cols };
                float[] texData = buildLookTableTexture(dims, parsedLut.data);
                
                int texWidth = parsedLut.cols * parsedLut.depth;
                int texHeight = parsedLut.rows;
                
                // Must use direct buffer for OpenGL ES
                FloatBuffer externalLutBuffer = ByteBuffer.allocateDirect(texData.length * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer();
                externalLutBuffer.put(texData);
                externalLutBuffer.rewind();
                
                mExternalLutTex = new Texture(texWidth, texHeight, 3, Texture.Format.Float16,
                        externalLutBuffer, GL_LINEAR, GL_CLAMP_TO_EDGE);
                converter.setTexture("externalLut", mExternalLutTex);
                converter.seti("hasExternalLut", 1);
                converter.seti("externalLutDims", parsedLut.cols, parsedLut.rows, parsedLut.depth);
                // Assume linear encoding for external LUTs (standard for .cube format)
                converter.seti("externalLutEncoding", 0);
                // Extract filename from path/URI for logging
                String fileName = process.externalLutPath;
                if (fileName.contains("/")) {
                    fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
                }
                if (fileName.contains(":")) {
                    // Remove URI scheme
                    fileName = fileName.substring(fileName.lastIndexOf(':') + 1);
                }
                
                Log.d(TAG, "Using external LUT file: " + fileName + 
                      " (" + parsedLut.cols + "x" + parsedLut.rows + "x" + parsedLut.depth + 
                      ", title: " + (parsedLut.title != null ? parsedLut.title : "none") + ")");
            } else {
                converter.seti("hasExternalLut", 0);
                Log.w(TAG, "Failed to parse external LUT file: " + process.externalLutPath);
            }
            }
        } else {
            converter.seti("hasExternalLut", 0);
        }
        
        // Color matching: compute global color correction based on mean color difference
        // between RAW (xyY) and JPEG (sRGB) preview
        // Note: Histogram matching is now handled by HistogramMatch stage
        Analysis analysis = previousStages.getStage(Analysis.class);
        float[] rawMeanColor = analysis != null ? analysis.getMeanColor() : null;
        
        if (histogramMatchApplied && rawMeanColor != null) {
            Bitmap preview = sensor.previewImage;
            int width = preview.getWidth();
            int height = preview.getHeight();
            
            // Extract pixels from reference preview
            int[] pixels = new int[width * height];
            preview.getPixels(pixels, 0, width, 0, 0, width, height);
            
            // Compute JPEG mean RGB for color matching
            float[] jpegMeanRgb = new float[3];
            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                jpegMeanRgb[0] += ((pixel >> 16) & 0xFF) / 255.0f;
                jpegMeanRgb[1] += ((pixel >> 8) & 0xFF) / 255.0f;
                jpegMeanRgb[2] += (pixel & 0xFF) / 255.0f;
            }
            jpegMeanRgb[0] /= pixels.length;
            jpegMeanRgb[1] /= pixels.length;
            jpegMeanRgb[2] /= pixels.length;
            
            // Convert JPEG mean RGB to xyY for comparison
            // First linearize sRGB
            float[] linearRgb = new float[3];
            for (int c = 0; c < 3; c++) {
                float v = jpegMeanRgb[c];
                linearRgb[c] = (v <= 0.04045f) ? v / 12.92f : (float) Math.pow((v + 0.055) / 1.055, 2.4);
            }
            
            // RGB to XYZ (sRGB matrix)
            float X = 0.4124564f * linearRgb[0] + 0.3575761f * linearRgb[1] + 0.1804375f * linearRgb[2];
            float Y = 0.2126729f * linearRgb[0] + 0.7151522f * linearRgb[1] + 0.0721750f * linearRgb[2];
            float Z = 0.0193339f * linearRgb[0] + 0.1191920f * linearRgb[1] + 0.9503041f * linearRgb[2];
            
            // XYZ to xyY
            float sum = X + Y + Z;
            float jpegX = (sum > 0.0001f) ? X / sum : 0.3127f;
            float jpegY = (sum > 0.0001f) ? Y / sum : 0.3290f;
            
            // Color correction: shift chromaticity from RAW to JPEG
            float rawColorCorrX = jpegX - rawMeanColor[0];
            float rawColorCorrY = jpegY - rawMeanColor[1];
            
            // Limit max correction magnitude (in chromaticity space, 0.02 is already quite visible)
            float maxCorrection = 0.02f;
            rawColorCorrX = Math.max(-maxCorrection, Math.min(maxCorrection, rawColorCorrX));
            rawColorCorrY = Math.max(-maxCorrection, Math.min(maxCorrection, rawColorCorrY));
            
            // Apply blend factor to avoid overshooting (start conservative)
            // The RAW intermediate is after color matrix, while JPEG is final rendered output
            // They're not directly comparable, so we blend cautiously
            float blendFactor = 0.5f;
            float colorCorrX = rawColorCorrX * blendFactor;
            float colorCorrY = rawColorCorrY * blendFactor;
            
            converter.setf("colorMatchCorrection", colorCorrX, colorCorrY);
            
            Log.d(TAG, "Color matching: RAW mean xy=(" + rawMeanColor[0] + "," + rawMeanColor[1] + 
                  "), JPEG mean xy=(" + jpegX + "," + jpegY + 
                  "), raw delta=(" + rawColorCorrX + "," + rawColorCorrY + 
                  "), applied (x" + blendFactor + ")=(" + colorCorrX + "," + colorCorrY + ")");
        } else {
            converter.setf("colorMatchCorrection", 0f, 0f);
        }

        converter.seti("histogramMatchApplied", histogramMatchApplied ? 1 : 0);
        converter.setf("histMatchMaxHdr", 1.0f);

        // Set default to SDR output (clamped)
        converter.seti("outputHDR", 0);
        
        // Synthetic HDR headroom for scenes without natural HDR content
        // When baselineExposure <= 1.0 (no natural HDR), we can synthetically expand
        // highlights to create visible HDR effect on HDR displays.
        // 
        // Only enabled when UHDR output is turned on (to save processing time).
        // The value represents how much to expand peaks (e.g., 3.0 = 3x SDR white)
        // Set to 0.0 or 1.0 to disable synthetic HDR expansion.
        float syntheticHdrHeadroom = 0.0f;
        if (process.ultraHdrEnabled && sensor.baselineExposure <= 0.0f) {
            // UHDR is enabled but no natural HDR content - enable synthetic expansion
            // Using 3.0 (300%) as default - provides noticeable HDR effect
            // without being too aggressive
            syntheticHdrHeadroom = 3.0f;
            Log.d(TAG, "Synthetic HDR enabled: baselineExposure=" + sensor.baselineExposure + 
                      " EV, headroom=" + syntheticHdrHeadroom + "x");
        }
        converter.setf("syntheticHdrHeadroom", syntheticHdrHeadroom);
        
        // Set the baseline exposure compression method number
        converter.seti("baselineExposureCompression", process.baselineExposureCompression);
        
        // Baseline exposure handling for UHDR gain map creation:
        // 
        // The gain map requires different treatment for SDR vs HDR:
        // - SDR: baseline exposure with compression (values ≤ 1.0)
        // - HDR: baseline exposure WITHOUT compression (values can exceed 1.0)
        //
        // When compression WAS applied in PreProcess (baselineExposureCompression != 0):
        // - SDR: set baselineExposure=1.0 so shader skips re-application (already compressed)
        // - HDR: set baselineExposure=original to re-apply without compression
        //
        // When compression was DISABLED in PreProcess (baselineExposureCompression == 0):
        // - Both SDR/HDR: baseline exposure already applied as linear multiply
        boolean compressionWasApplied = (process.baselineExposureCompression != 0);
        
        if (compressionWasApplied) {
            // Compression was applied in PreProcess
            // For SDR: skip re-application by setting baselineExposure to 1.0
            // For HDR: re-apply without compression (handled in setHdrOutputMode)
            converter.setf("baselineExposure", 1.0f);
            Log.d(TAG, "Baseline exposure " + mOriginalBaselineExposure + 
                      " was applied WITH compression in PreProcess. SDR mode: skipping re-application.");
        } else {
            // No compression - linear multiply was applied in PreProcess
            // Both SDR and HDR use the same values (already applied)
            converter.setf("baselineExposure", mOriginalBaselineExposure);
            Log.d(TAG, "Baseline exposure " + mOriginalBaselineExposure + 
                      " was applied as linear multiply in PreProcess. Skipping re-application.");
        }

        // Restore output FBO.
        glBindFramebuffer(GL_FRAMEBUFFER, mFbo[0]);
    }

    /**
     * Enable HDR output mode (no clamping, preserves values > 1.0).
     * Call this before rendering to capture HDR version.
     * 
     * For UHDR gain map creation:
     * - SDR: uses compressed baseline exposure from PreProcess
     * - HDR: re-applies baseline exposure WITHOUT compression (if compression was used)
     */
    public void setHdrOutputMode(boolean enabled) {
        GLPrograms converter = getConverter();
        ProcessParams process = getProcessParams();
        mOutputHdr = enabled;  // Store for use in execute()
        converter.seti("outputHDR", enabled ? 1 : 0);
        
        boolean compressionWasApplied = (process.baselineExposureCompression != 0);
        
        // Set the baseline exposure compression method number
        converter.seti("baselineExposureCompression", process.baselineExposureCompression);
        
        if (enabled) {
            // HDR mode: need to apply baseline exposure without compression
            if (compressionWasApplied) {
                // Compression was applied in PreProcess - re-apply without compression for HDR
                converter.setf("baselineExposure", mOriginalBaselineExposure);
                Log.d(TAG, "HDR mode: re-applying baseline exposure " + mOriginalBaselineExposure + 
                          " WITHOUT compression (was compressed in PreProcess)");
            } else {
                // No compression was applied - linear multiply already done
                converter.setf("baselineExposure", mOriginalBaselineExposure);
                Log.d(TAG, "HDR mode: baseline exposure " + mOriginalBaselineExposure + 
                          " already applied as linear multiply in PreProcess");
            }
        } else {
            // SDR mode: keep compressed values from PreProcess
            if (compressionWasApplied) {
                converter.setf("baselineExposure", 1.0f);
                Log.d(TAG, "SDR mode: baseline exposure " + mOriginalBaselineExposure + 
                          " was compressed in PreProcess, skipping re-application");
            } else {
                converter.setf("baselineExposure", mOriginalBaselineExposure);
                Log.d(TAG, "SDR mode: baseline exposure " + mOriginalBaselineExposure + 
                          " was applied as linear multiply in PreProcess");
            }
        }
    }

    /**
     * Re-execute the ToneMap stage with the given previous stages.
     * This is used for capturing HDR output after the initial SDR render.
     * 
     * @param previousStages The previous stages in the pipeline
     */
    public void reExecute(StagePipeline.StageMap previousStages) {
        execute(previousStages);
    }

    @Override
    public int getShader() {
        return R.raw.stage3_3_tonemap_fs;
    }

    @Override
    public void close() {
        if (mDitherTex != null) {
            mDitherTex.close();
        }
        if (mSatTex != null) {
            mSatTex.close();
        }
        if (mToneCurveTex != null) {
            mToneCurveTex.close();
        }
        if (mHueSatMapTex != null) {
            mHueSatMapTex.close();
        }
        if (mLookTableTex != null) {
            mLookTableTex.close();
        }
        if (mExternalLutTex != null) {
            mExternalLutTex.close();
        }
        if (mPreviewTex != null) {
            mPreviewTex.close();
        }
    }
}
