#version 300 es
#define PI 3.1415926535897932384626433832795f

/* Use highp for calculations to prevent banding in smooth gradients */
precision highp float;
precision mediump usampler2D;

uniform sampler2D highRes;
uniform int intermediateWidth;
uniform int intermediateHeight;

uniform sampler2D weakBlur;
uniform sampler2D mediumBlur;
uniform sampler2D strongBlur;

// Extended blurs for multi-scale LCE (Boosted and MAT modes)
uniform sampler2D xfineBlur;   // σ=0.22 - finest detail
uniform sampler2D fineBlur;    // σ=0.4  - fine detail
uniform sampler2D xstrongBlur; // σ=5.0  - largest structure

uniform int yOffset;

uniform bool lce;
uniform int lceMethod;  // 0 = lce (multiplicative), 1 = clahe (full, separate stage), 2 = gpu clahe (CDF-based)
uniform bool lceMultiScale;  // Enable 6-scale LCE (Boosted and MAT modes)
uniform bool varianceLimiting;  // Enable variance-based LCE limiting
uniform bool matMode;  // Enable MAT mode color processing
uniform bool matGreenToYellowShift;  // Enable green to yellow hue shift
uniform bool matYellowToWarmShift;  // Enable yellow to warm hue shift
uniform bool leicaM9Mode;  // Enable Leica M9 CCD sensor emulation

// LCE parameters from preferences
uniform vec3 lceStrengths;      // [weak, medium, strong] strengths
uniform vec3 lceStrengthsMulti; // [xfine, fine, xstrong] strengths for multi-scale
uniform vec3 lceLimits;         // [weak, medium, strong] clip limits
uniform vec3 lceLimitsMulti;    // [xfine, fine, xstrong] clip limits for multi-scale
uniform vec3 lceRadii;          // [weak, medium, strong] blur radii (percentages)
uniform vec3 lceRadiiMulti;     // [xfine, fine, xstrong] blur radii (percentages)
uniform vec2 adaptiveSaturation;

// Sensor and picture variables
uniform vec4 toneMapCoeffs; // Coefficients for a polynomial tonemapping curve

// Transform
uniform mat3 XYZtoProPhoto; // Color transform from XYZ to a wide-gamut colorspace
uniform mat3 proPhotoToSRGB; // Color transform from wide-gamut colorspace to sRGB

// Post processing
uniform float sharpenFactor;
uniform sampler2D saturation;
uniform float satLimit;

// Dithering
uniform usampler2D ditherTex;
uniform int ditherSize;

// Size
uniform ivec2 outOffset;

// Baseline exposure from DNG (for HDR handling)
uniform float baselineExposure;  // Multiplier (2^EV) from DNG BaselineExposure tag
uniform int baselineExposureCompression;  // Compression method: 0=None, 1=Reinhard, 2=ACES Filmic, 3=Uncharted 2, 4=Improved Rational, 5=Gradient Domain, 6=Hejl-Dawson, 7=Modified ACES, 8=Reinhard-Jodie, 9=Lottes, 10=Gamma-Based, 11=Gamma+ACES Fusion, 12=Exposure Slider, 13=Sigmoidal, 14=Piecewise, 15=Histogram Match, 16=LibRaw exp_bef, 17=Exposure Fusion (HDR)

// Tone adjustments (Lightroom-style)
uniform float toneExposure;    // Multiplier (2^EV)
uniform float toneHighlights;  // -1.0 to +1.0 (from -100 to +100)
uniform float toneShadows;     // -1.0 to +1.0 (from -100 to +100)
uniform float toneWhites;      // -1.0 to +1.0 (from -100 to +100)
uniform float toneContrast;    // -1.0 to +1.0 (from -100 to +100)
uniform float toneBlacks;      // -1.0 to +1.0 (from -100 to +100)
uniform float toneTexture;     // -1.0 to +1.0 (from -100 to +100)
uniform float toneClarity;     // -1.0 to +1.0 (from -100 to +100)
uniform float toneDehaze;      // -1.0 to +1.0 (from -100 to +100)
uniform float toneVibrance;    // -1.0 to +1.0 (from -100 to +100)
uniform float toneSaturation;  // -1.0 to +1.0 (from -100 to +100)

// HDR-specific adjustments (applied only for HDR images)
uniform float hdrSigmoidalContrast; // Sigmoidal contrast strength for HDR (0.0 = no adjustment, typically 1.0-5.0)
uniform float hdrSigmoidalMidpoint; // Sigmoidal contrast midpoint (typically 0.5 = 50%)
uniform float hdrShadowAdjust; // Shadow lowering for HDR images (0.0 = no adjustment, positive = lower shadows)
uniform int isNightMode; // Night mode flag (1 = night mode, 0 = normal) - Light Value < 0
uniform int hdrCompressionMethod; // HDR compression method: 0=Reinhard, 1=ACES Filmic, 2=Uncharted 2, 3=Improved Rational, 4=Gamma+ACES Fusion, 5=Late Exposure Fusion
uniform int inputIsRgb; // 1 = highRes is RGB (from LateExposureFusion), 0 = highRes is xyY (normal path)

// Color Transform (user-defined 3x3 RGB mixing matrix)
uniform mat3 colorTransform;

// DNG Profile Tone Curve
uniform bool hasProfileToneCurve;   // Whether a profile tone curve is available
uniform sampler2D profileToneCurve; // 1D LUT texture for the tone curve

// DNG Profile HueSatMap - per-hue HSL adjustments
// This is how camera manufacturers create their signature color look
uniform bool hasProfileHueSatMap;     // Whether a HueSatMap is available
uniform sampler2D profileHueSatMap;   // 2D texture containing flattened 3D map
uniform ivec3 hueSatMapDims;          // [hueDivisions, satDivisions, valDivisions]

// DNG Profile LookTable - 3D RGB->RGB color LUT
// Used for final color grading/creative look
uniform bool hasProfileLookTable;     // Whether a LookTable is available
uniform sampler2D profileLookTable;   // 2D texture containing flattened 3D LUT
uniform ivec3 lookTableDims;          // [cols, rows, depth] (R, G, B axes)
uniform int lookTableEncoding;        // 0 = linear, 1 = sRGB encoding

// External LUT file (e.g., .cube format) - applied after DNG Profile LookTable
uniform bool hasExternalLut;          // Whether an external LUT is available
uniform sampler2D externalLut;        // 2D texture containing flattened 3D LUT
uniform ivec3 externalLutDims;        // [cols, rows, depth] (R, G, B axes)
uniform int externalLutEncoding;     // 0 = linear, 1 = sRGB encoding

// Reference Preview Image (embedded JPEG from DNG)
// Used for tone matching via histogram matching
// Histogram matching can be applied here (when inputIsRgb <= 0) or in HistogramMatch stage
uniform int histogramMatchApplied;     // Whether reference preview is available for histogram matching
uniform float histMatchControlInputs[9];   // Input positions [0,1] for control points
uniform float histMatchControlOutputs[9]; // Output values [0,1] for control points
uniform float histMatchControlTangents[9]; // Tangents (slopes) at control points
uniform int histMatchNumControlPoints;     // Number of control points
uniform float histMatchMaxHdr;            // Maximum HDR value for normalization
uniform vec2 colorMatchCorrection;    // Chromaticity correction (delta x, delta y) for color matching

// HDR output mode: when true, output HDR version (no clamp), when false, output SDR (clamped)
uniform int outputHDR;  // 0 = SDR (clamped), 1 = HDR (no clamp)

// Synthetic HDR headroom for scenes without natural HDR content (baselineExposure <= 1.0)
// When enabled (> 1.0), expands highlights to create UHDR gain map data
// Typical values: 2.0-4.0 (2x-4x SDR white)
uniform float syntheticHdrHeadroom;

// Out
out vec4 color;

#include sigmoid
#include xyytoxyz
#include xyztoxyy
#include gamma
// #include compression  // Temporarily disabled - inlining functions below

// =====================================================
// SMOOTH BLENDING FUNCTIONS (No Hard Limits)
// =====================================================
// Smooth blending functions to prevent banding from hard limits
// MUST be defined before any code that uses them

// Smooth clamp: smoothly limits to [minVal, maxVal] without hard cutoff
// Uses smoothstep for smooth rolloff to prevent banding
// transitionWidth: size of transition zone (e.g., 0.05 = 5% of range)
float smoothClamp(float x, float minVal, float maxVal, float transitionWidth) {
    float range = maxVal - minVal;
    float lowerBound = minVal + transitionWidth * range;
    float upperBound = maxVal - transitionWidth * range;
    
    // Below transition zone: smoothly approach minVal
    if (x < lowerBound) {
        float t = smoothstep(minVal - transitionWidth * range, lowerBound, x);
        return mix(minVal, lowerBound, t);
    }
    // Above transition zone: smoothly approach maxVal
    else if (x > upperBound) {
        float t = smoothstep(upperBound, maxVal + transitionWidth * range, x);
        return mix(upperBound, maxVal, t);
    }
    // In valid range: pass through unchanged
    return x;
}

// Smooth max: smoothly blends to zero instead of hard cutoff
// Prevents banding when values go negative
float smoothMax(float x, float minVal, float transitionWidth) {
    return mix(minVal, x, smoothstep(minVal - transitionWidth, minVal + transitionWidth, x));
}

// Smooth min: smoothly blends to max instead of hard cutoff
float smoothMin(float x, float maxVal, float transitionWidth) {
    return mix(x, maxVal, smoothstep(maxVal - transitionWidth, maxVal + transitionWidth, x));
}

// Smooth clamp for vec3 (applies to each channel)
vec3 smoothClampVec3(vec3 x, float minVal, float maxVal, float transitionWidth) {
    return vec3(
        smoothClamp(x.r, minVal, maxVal, transitionWidth),
        smoothClamp(x.g, minVal, maxVal, transitionWidth),
        smoothClamp(x.b, minVal, maxVal, transitionWidth)
    );
}

// Smooth max for vec3 (prevents hard cutoff at zero)
vec3 smoothMaxVec3(vec3 x, float minVal, float transitionWidth) {
    return vec3(
        smoothMax(x.r, minVal, transitionWidth),
        smoothMax(x.g, minVal, transitionWidth),
        smoothMax(x.b, minVal, transitionWidth)
    );
}

// ============================================================================
// HDR LUMINANCE DECODING
// ============================================================================
// The xyY intermediate format stores HDR luminance encoded:
// - .xy = chromaticity (x, y) - always in [0, 1]
// - .z = Y / hdrScale (normalized luminance, always <= 1.0)
// - .w = 1.0 / hdrScale (inverse scale, always <= 1.0 so it won't be clamped)
// To decode: Y = .z / .w

// Decode HDR xyY from texture sample
// Alpha stores 1/scale, so we divide z by alpha to restore Y
// If alpha == 1.0, no encoding was applied (values were already in [0,1])
// Use 0.0001 as floor to support HDR_MAX up to 10000
vec3 decodeHDRxyY(vec4 encoded) {
    // If alpha is 1.0, no encoding was applied - Y is already correct
    if (encoded.w >= 0.9999) {
        return vec3(encoded.x, encoded.y, encoded.z);
    }
    // Otherwise, decode: Y = encoded.z / encoded.w
    float invScale = max(encoded.w, 0.0001);
    return vec3(encoded.x, encoded.y, encoded.z / invScale);
}

// Fetch and decode HDR xyY from highRes texture
vec3 fetchHighResXYY(ivec2 pos) {
    vec4 encoded = texelFetch(highRes, pos, 0);
    return decodeHDRxyY(encoded);
}

// ============================================================================
// COLOR SPACE CONVERSIONS
// ============================================================================

// Convert RGB to HSV color space
// Returns vec3(hue, saturation, value) where hue is in [0, 1] (0=red, 1/6=yellow, 1/3=green, etc.)
vec3 rgb2hsv(vec3 rgb) {
    float maxC = max(max(rgb.r, rgb.g), rgb.b);
    float minC = min(min(rgb.r, rgb.g), rgb.b);
    float delta = maxC - minC;
    
    float h = 0.0;
    if (delta > 0.0001) {
        if (maxC == rgb.r) {
            h = mod(((rgb.g - rgb.b) / delta) / 6.0 + 1.0, 1.0);
        } else if (maxC == rgb.g) {
            h = ((rgb.b - rgb.r) / delta + 2.0) / 6.0;
        } else {
            h = ((rgb.r - rgb.g) / delta + 4.0) / 6.0;
        }
    }
    
    float s = (maxC > 0.0001) ? (delta / maxC) : 0.0;
    float v = maxC;
    
    return vec3(h, s, v);
}

// Convert HSV to RGB color space
// Input: vec3(hue, saturation, value) where hue is in [0, 1]
vec3 hsv2rgb(vec3 hsv) {
    float h = hsv.x * 6.0;  // Scale to [0, 6]
    float s = hsv.y;
    float v = hsv.z;
    
    int i = int(floor(h));
    float f = h - float(i);
    float p = v * (1.0 - s);
    float q = v * (1.0 - s * f);
    float t = v * (1.0 - s * (1.0 - f));
    
    vec3 rgb;
    if (i == 0 || i == 6) {
        rgb = vec3(v, t, p);
    } else if (i == 1) {
        rgb = vec3(q, v, p);
    } else if (i == 2) {
        rgb = vec3(p, v, t);
    } else if (i == 3) {
        rgb = vec3(p, q, v);
    } else if (i == 4) {
        rgb = vec3(t, p, v);
    } else {  // i == 5
        rgb = vec3(v, p, q);
    }
    
    return rgb;
}

// ============================================================================
// BASELINE EXPOSURE WITH COMPRESSION
// ============================================================================
// 
// Applies baseline exposure using a falling curve that compresses dynamic range:
// - Dark values get brightened more than light values
// - Ensures no values exceed 1.0
// - Uses a compression curve: y = 1.0 - (1.0 - x)^exposure when exposure > 1.0
//   This naturally brightens shadows more than highlights
// ============================================================================

// Apply baseline exposure with compression curve
// This ensures values never exceed 1.0 while brightening shadows more than highlights
// exposure: baseline exposure multiplier (2^EV)
// Returns: compressed value in [0, 1]
//
// The curve uses: y = 1.0 - (1.0 - x)^exposure when exposure > 1.0
// This is a compression curve that:
// - Brightens shadows more than highlights (falling curve)
// - Naturally limits output to [0, 1]
// - More aggressive compression for higher exposure values
float applyBaselineExposureCompressed(float x, float exposure) {
    if (abs(exposure - 1.0) < 0.001) {
        return x;  // No change
    }
    
    // Clamp input to [0, 1] for the curve
    x = clamp(x, 0.0, 1.0);
    
    if (exposure > 1.0) {
        // Brightening: use compression curve that brightens shadows more
        // Curve: y = 1.0 - (1.0 - x)^exposure
        // This curve:
        // - Brightens shadows more than highlights (falling curve)
        // - Naturally compresses highlights toward 1.0
        // - Higher exposure = more compression
        // Example: exposure=2.0, x=0.1 → y≈0.19 (brightened), x=0.9 → y≈0.99 (slightly brightened)
        return 1.0 - pow(1.0 - x, exposure);
    } else {
        // Darkening: use power curve (darkens highlights more than shadows)
        // Curve: y = x^exposure
        // This darkens highlights more than shadows (opposite of brightening)
        // Example: exposure=0.5, x=0.1 → y≈0.32 (less darkening), x=0.9 → y≈0.95 (more darkening)
        return pow(x, exposure);
    }
}

// Apply baseline exposure WITHOUT compression (for HDR capture)
// This preserves the full dynamic range by simply multiplying by exposure
// Values can exceed 1.0, representing HDR highlights
float applyBaselineExposureUncompressed(float x, float exposure) {
    if (abs(exposure - 1.0) < 0.001) {
        return x;  // No change
    }
    
    // Simple multiplication: preserves full dynamic range
    // No clamping, no compression curves
    // For brightening (exposure > 1.0): values can exceed 1.0
    // For darkening (exposure < 1.0): values are reduced proportionally
    return x * exposure;
}

// ============================================================================
// HDR COMPRESSION FOR 16-BIT LINEAR RAW
// ============================================================================
// 
// HDR compression is needed when baselineExposure > 1.0 (brightening):
// - Values can exceed 1.0 and would be hard-clamped later
// - Reinhard compression smoothly maps highlights: 1.0 → 0.85, 2.0 → 1.0, preserving detail
//
// Mode 1: With ProfileToneCurve - minimal intervention
//   The camera's tone curve handles everything. We just apply shoulder
//   roll-off to prevent harsh clipping of HDR highlights.
//
// Mode 2: Without ProfileToneCurve - full HDR compression  
//   We need to compress [0, 64] → [0, 1] ourselves.
// ============================================================================

// Compression functions - inlined from import_compression.glsl
// (Include temporarily disabled due to loading issues)
// ============================================================================
// SHARED HDR COMPRESSION FUNCTIONS
// ============================================================================

// Simple Reinhard tone mapping: x / (1 + x)
float reinhard(float x) {
    return x / (1.0 + x);
}

// ============================================================================
// GAMMA+ACES FUSION HDR COMPRESSION
// ============================================================================
// Full port of the stage1 GammaACESFusion compression methods
// These compress HDR values to [0, 1] while preserving contrast

// Soft ACES Filmic compression for HDR values
// NO per-pixel normalization - that causes discontinuities!
// ACES naturally compresses HDR to ~1.0 asymptotically
vec3 acesFilmicSoftCompress_GammaFusion(vec3 x, float maxVal) {
    // Modified ACES with softer shoulder
    // Tuned so that input 1.0 → output ~0.85, giving headroom for HDR
    float a = 2.51;
    float b = 0.03;
    float c = 2.43;
    float d = 0.59;
    float e = 0.14;
    
    // Standard ACES filmic curve - naturally compresses to [0, ~1.0]
    // No per-pixel normalization - this ensures smooth continuous response
    vec3 result = (x * (a * x + b)) / (x * (c * x + d) + e);
    
    return result;
}

// Gamma-based compression - matches stage1 applyBaselineExposureGammaBased
// Key insight: gamma = baselineExposure (not maxVal!)
// The idea: instead of multiplying by baselineExposure, apply equivalent gamma
// Then compensate for contrast loss with sigmoidal contrast
vec3 gammaBasedCompress_GammaFusion(vec3 rgb) {
    // Normalize to [0,1] while preserving color ratios (like stage1)
    float hdrScale = max(baselineExposure, 1.0);
    vec3 normalizedInput = rgb / hdrScale;
    
    // Calculate gamma from baselineExposure (the uniform)
    // gamma = baselineExposure means:
    // - baselineExposure = 2.0 → pow(x, 0.5) = sqrt(x) (brightens)
    // - baselineExposure = 4.0 → pow(x, 0.25) (brightens more)
    float cgamma = clamp(baselineExposure, 0.5, 3.0);
    
    // Apply gamma correction: pow(x, 1/gamma)
    vec3 gammaCorrected = pow(normalizedInput, vec3(1.0 / cgamma));
    
    // Calculate sigmoidal contrast to compensate for gamma's contrast loss
    // Uses the same formula as stage1: calculateSigmoidalContrastFromGamma
    float contrastStrength = 1.0;
    if (cgamma > 1.0) {
        // Matches calculateSigmoidalContrastFromGamma: 1.0 + (gamma - 1.0) * 1.8
        contrastStrength = 1.0 + (cgamma - 1.0) * 1.8;
        contrastStrength *= 3.3;  // Apply 3.3 multiplier like stage1
    }
    contrastStrength = min(contrastStrength, 6.0);
    
    // Apply sigmoidal contrast
    float midpoint = 0.5;
    vec3 result;
    for (int i = 0; i < 3; i++) {
        float val = gammaCorrected[i];
        float sigmoid_mid = 1.0 / (1.0 + exp(-contrastStrength * (val - midpoint)));
        float sigmoid_0 = 1.0 / (1.0 + exp(-contrastStrength * (0.0 - midpoint)));
        float sigmoid_1 = 1.0 / (1.0 + exp(-contrastStrength * (1.0 - midpoint)));
        result[i] = (sigmoid_mid - sigmoid_0) / (sigmoid_1 - sigmoid_0);
    }
    
    // For HDR highlights, blend toward white (like stage1)
    // This gives bright highlights a gentle push without going fully white
    if (hdrScale > 1.0) {
        float excess = hdrScale - 1.0;
        float compressedExcess = excess / (1.0 + excess);  // Reinhard on excess
        result = mix(result, vec3(1.0), compressedExcess * 0.3);  // 30% blend toward white
    }
    
    return result;
}

// Soft shoulder compression - preserves shadow/midtone contrast, only compresses highlights
// C1 continuous (smooth derivative) at the knee point = no banding
// - Below knee: linear (unchanged)
// - Above knee: smooth Reinhard-style rolloff into available headroom
// - HDR values > 1.0 compressed smoothly to fit in [0, 1]
vec3 softShoulderCompress(vec3 rgb) {
    float knee = 0.8;      // Start of shoulder (values below this are linear)
    float maxOut = 1.0;    // Maximum output value
    float headroom = maxOut - knee;  // 0.2 - space for compressed highlights
    
    vec3 result;
    for (int i = 0; i < 3; i++) {
        float x = rgb[i];
        if (x <= knee) {
            // Linear region - preserves contrast in shadows/midtones
            result[i] = x;
        } else {
            // Shoulder region - soft rolloff using modified Reinhard
            // f(x) = knee + (x - knee) / (1 + (x - knee) / headroom)
            // This is C1 continuous: derivative at knee = 1 (matches linear)
            // As x -> inf, f(x) -> knee + headroom = maxOut
            float excess = x - knee;
            float compressedExcess = excess / (1.0 + excess / headroom);
            result[i] = knee + compressedExcess;
        }
    }
    return result;
}

// Reinhard compression - smooth, no discontinuities (legacy, for reference)
vec3 reinhardCompress_GammaFusion(vec3 rgb) {
    // Simple Reinhard on all values - affects contrast everywhere
    return rgb / (vec3(1.0) + rgb);
}

// =====================================================================
// Synthetic HDR Expansion
// =====================================================================
// For scenes without natural HDR content (baselineExposure <= 1.0), this
// function expands highlights to create synthetic headroom for UHDR gain maps.
// 
// The approach uses a soft-knee curve that:
// - Preserves shadows and midtones (linear below knee)
// - Smoothly expands highlights above the knee point
// - Uses smoothstep-like curve for natural gradients
//
// This allows UHDR to show enhanced highlights on HDR displays even when
// the source material has no clipped/compressed highlight data.
vec3 syntheticHdrExpand(vec3 sdr, float headroom) {
    // headroom: expansion factor (e.g., 3.0 = expand peaks to 3x SDR white)
    // Only meaningful when headroom > 1.0
    if (headroom <= 1.0) {
        return sdr;
    }
    
    // Knee point: start expanding above this luminance
    // Lower values = more aggressive expansion, higher = more conservative
    const float knee = 0.6;
    
    // Calculate luminance for threshold check
    float Y = dot(sdr, vec3(0.2126, 0.7152, 0.0722));
    
    if (Y > knee) {
        // Normalize to [0,1] range above knee
        float t = (Y - knee) / (1.0 - knee);
        
        // Smoothstep-like curve for natural gradients
        // shape: 0 at knee, 1 at white point, smooth in between
        float shape = t * t * (3.0 - 2.0 * t);
        
        // Calculate gain: 1.0 at knee, headroom at white point
        float gain = 1.0 + (headroom - 1.0) * shape;
        
        // Apply gain while preserving color ratios
        return sdr * gain;
    }
    
    return sdr;
}

// Gamma+ACES Fusion HDR compression - blends three methods for best results
// Ported from stage1_linear_preprocess_fs.glsl applyBaselineExposureGammaACESFusion
// - Reinhard as base (balanced, most of the image)
// - ACES Soft blended in for contrast in mid-tones/darker areas
// - Gamma-based blended in for highlight retention in bright areas
vec3 gammaACESFusionCompress(vec3 rgb, float maxVal) {
    
    // Apply all three methods (like stage1)
    vec3 reinhardResult = reinhardCompress_GammaFusion(rgb);
    vec3 acesResult = acesFilmicSoftCompress_GammaFusion(rgb, maxVal);
    vec3 gammaResult = gammaBasedCompress_GammaFusion(rgb);
    
    // Calculate luminance of Reinhard result for weighting (matches stage1 which uses Reinhard result)
    float inputLuminance = dot(reinhardResult, vec3(0.2126, 0.7152, 0.0722));
    
    // ACES weight: higher in mid-tones/darker areas where contrast is needed
    // Peak around 0.3-0.5 luminance for contrast boost (matches stage1)
    float acesWeight = 0.0;
    if (inputLuminance < 0.5) {
        acesWeight = smoothstep(0.5, 0.2, inputLuminance) * 0.5;  // Max 50% ACES
    }
    
    // Gamma weight: higher in bright areas where highlight retention is needed
    // Peak in highlights (above 0.5 luminance) (matches stage1)
    float gammaWeight = 0.0;
    if (inputLuminance > 0.5) {
        gammaWeight = smoothstep(0.5, 1.0, inputLuminance) * 0.5;  // Max 50% Gamma
    }
    
    // Blend weights - ensure they sum to 1.0 (matches stage1)
    float totalAdjustment = acesWeight + gammaWeight;
    float reinhardWeight = 1.0 - totalAdjustment;
    
    // Blend the three results
    vec3 result = reinhardResult * reinhardWeight + 
                  acesResult * acesWeight + 
                  gammaResult * gammaWeight;
    
    // Smooth rolloff instead of hard clamp to prevent banding
    return smoothClampVec3(result, 0.0, 1.0, 0.001);  // 0.1% transition zone - only affects extremes
}

// Color-preserving Reinhard compression for vec3
vec3 reinhardCompress(vec3 rgb, float targetWhite) {
    vec3 compressed = rgb / (vec3(1.0) + rgb);
    float maxCompressed = max(max(compressed.r, compressed.g), compressed.b);
    
    if (maxCompressed > 0.001) {
        // Scale so that maxCompressed maps to targetWhite
        float targetScale = targetWhite / maxCompressed;
        compressed = compressed * targetScale;
        
        // Smooth rolloff instead of hard division clamp to prevent banding
        float maxResult = max(max(compressed.r, compressed.g), compressed.b);
        if (maxResult > 0.995) {  // Only limit values very close to or exceeding 1.0
            // Smooth blend: when maxResult is 0.995-1.01, smoothly scale down to limit at 1.0
            // At 0.995: scale = 1.0 (no change)
            // At 1.0: scale = 1.0 / 1.0 = 1.0 (limit exactly at 1.0)
            // At 1.01: scale = 1.0 / 1.01 ≈ 0.99 (scale down)
            float t = smoothstep(0.995, 1.01, maxResult);
            float targetMax = mix(1.0, 1.0, t);  // Target is always 1.0, but we smooth the transition
            float scale = mix(1.0, targetMax / maxResult, t);
            compressed = compressed * scale;
        }
    }
    
    return compressed;
}

// ACES Filmic tone mapping
float acesFilmic(float x) {
    float a = 2.51;
    float b = 0.03;
    float c = 2.43;
    float d = 0.59;
    float e = 0.14;
    return (x * (a * x + b)) / (x * (c * x + d) + e);
}

// Color-preserving ACES Filmic compression
vec3 acesFilmicCompress(vec3 rgb, float targetWhite) {
    vec3 compressed = vec3(
        acesFilmic(rgb.r),
        acesFilmic(rgb.g),
        acesFilmic(rgb.b)
    );
    
    float maxCompressed = max(max(compressed.r, compressed.g), compressed.b);
    
    if (maxCompressed > 0.001) {
        // Scale so that maxCompressed maps to targetWhite
        float targetScale = targetWhite / maxCompressed;
        compressed = compressed * targetScale;
        
        // Smooth rolloff instead of hard division clamp to prevent banding
        float maxResult = max(max(compressed.r, compressed.g), compressed.b);
        if (maxResult > 0.995) {  // Only limit values very close to or exceeding 1.0
            // Smooth blend: when maxResult is 0.995-1.01, smoothly scale down to limit at 1.0
            // At 0.995: scale = 1.0 (no change)
            // At 1.0: scale = 1.0 / 1.0 = 1.0 (limit exactly at 1.0)
            // At 1.01: scale = 1.0 / 1.01 ≈ 0.99 (scale down)
            float t = smoothstep(0.995, 1.01, maxResult);
            float targetMax = mix(1.0, 1.0, t);  // Target is always 1.0, but we smooth the transition
            float scale = mix(1.0, targetMax / maxResult, t);
            compressed = compressed * scale;
        }
    }
    
    return compressed;
}

// Uncharted 2 tone mapping
float uncharted2(float x) {
    float A = 0.15;
    float B = 0.50;
    float C = 0.10;
    float D = 0.20;
    float E = 0.02;
    float F = 0.30;
    
    float result = ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
    float whiteScale = ((11.2 * (A * 11.2 + C * B) + D * E) / (11.2 * (A * 11.2 + B) + D * F)) - E / F;
    return result / whiteScale;
}

// Color-preserving Uncharted 2 compression
vec3 uncharted2Compress(vec3 rgb, float targetWhite) {
    vec3 compressed = vec3(
        uncharted2(rgb.r),
        uncharted2(rgb.g),
        uncharted2(rgb.b)
    );
    
    float maxCompressed = max(max(compressed.r, compressed.g), compressed.b);
    
    if (maxCompressed > 0.001) {
        // Scale so that maxCompressed maps to targetWhite
        float targetScale = targetWhite / maxCompressed;
        compressed = compressed * targetScale;
        
        // Smooth rolloff instead of hard division clamp to prevent banding
        float maxResult = max(max(compressed.r, compressed.g), compressed.b);
        if (maxResult > 0.995) {  // Only limit values very close to or exceeding 1.0
            // Smooth blend: when maxResult is 0.995-1.01, smoothly scale down to limit at 1.0
            // At 0.995: scale = 1.0 (no change)
            // At 1.0: scale = 1.0 / 1.0 = 1.0 (limit exactly at 1.0)
            // At 1.01: scale = 1.0 / 1.01 ≈ 0.99 (scale down)
            float t = smoothstep(0.995, 1.01, maxResult);
            float targetMax = mix(1.0, 1.0, t);  // Target is always 1.0, but we smooth the transition
            float scale = mix(1.0, targetMax / maxResult, t);
            compressed = compressed * scale;
        }
    }
    
    return compressed;
}

// Improved Rational compression
float improvedRational(float x) {
    float a = 0.5;
    return x / (a + (1.0 - a) * x);
}

// Color-preserving Improved Rational compression
vec3 improvedRationalCompress(vec3 rgb, float targetWhite) {
    vec3 compressed = vec3(
        improvedRational(rgb.r),
        improvedRational(rgb.g),
        improvedRational(rgb.b)
    );
    
    float maxCompressed = max(max(compressed.r, compressed.g), compressed.b);
    
    if (maxCompressed > 0.001) {
        // Scale so that maxCompressed maps to targetWhite
        float targetScale = targetWhite / maxCompressed;
        compressed = compressed * targetScale;
        
        // Smooth rolloff instead of hard division clamp to prevent banding
        float maxResult = max(max(compressed.r, compressed.g), compressed.b);
        if (maxResult > 0.995) {  // Only limit values very close to or exceeding 1.0
            // Smooth blend: when maxResult is 0.995-1.01, smoothly scale down to limit at 1.0
            // At 0.995: scale = 1.0 (no change)
            // At 1.0: scale = 1.0 / 1.0 = 1.0 (limit exactly at 1.0)
            // At 1.01: scale = 1.0 / 1.01 ≈ 0.99 (scale down)
            float t = smoothstep(0.995, 1.01, maxResult);
            float targetMax = mix(1.0, 1.0, t);  // Target is always 1.0, but we smooth the transition
            float scale = mix(1.0, targetMax / maxResult, t);
            compressed = compressed * scale;
        }
    }
    
    return compressed;
}

// Apply HDR compression using the selected method with target white
vec3 applyHDRCompressionVec3(vec3 rgb, float targetWhite, int method) {
    if (true) {
        return reinhardCompress(rgb, targetWhite);
    } else if (method == 1) {
        return acesFilmicCompress(rgb, targetWhite);
    } else if (method == 2) {
        return uncharted2Compress(rgb, targetWhite);
    } else if (method == 3) {
        return improvedRationalCompress(rgb, targetWhite);
    } else {
        return reinhardCompress(rgb, targetWhite);
    }
}

// Full HDR compression for when we don't have a profile tone curve
vec3 fullHDRCompress(vec3 rgb, int method) {
    return applyHDRCompressionVec3(rgb, 0.85, method);
}

// HDR compression for images with ProfileToneCurve
vec3 shoulderRolloff(vec3 rgb, int method) {
    return applyHDRCompressionVec3(rgb, 0.9, method);
}

// Compression wrapper functions are now in shared include file (import_compression.glsl)
// Use the color-preserving vec3 versions: fullHDRCompress(vec3, int) and shoulderRolloff(vec3, int)

// ============================================================================
// HISTOGRAM MATCHING HDR COMPRESSION
// ============================================================================
// Two approaches to compress HDR values to [0,1] using histogram matching:
//
// APPROACH 1: GammaACESFusion + Histogram Match (RECOMMENDED - ACTIVE)
//   - Use gammaACESFusionCompress which is specifically designed for HDR→[0,1]
//   - Then refine with histogram matching
//   - Clean, no highlight recovery needed
//
// APPROACH 2: Clamp + Histogram Match + Highlight Recovery (COMMENTED OUT)
//   - Clamp to [0,1] (lose HDR temporarily)
//   - Apply histogram matching
//   - Recover highlights by blending with HDR-aware processing
//   - Similar to gammaACESFusion's "blend toward white" technique

// Evaluate parametric tone curve using Hermite interpolation
// Direct evaluation (no LUT quantization) for smooth, accurate results
// Used for histogram matching when applied in this shader (inputIsRgb <= 0)
highp float evaluateToneCurve(highp float x) {
    // Clamp input to valid range
    x = clamp(x, 0.0, 1.0);
    
    // Find which segment we're in
    int segment = 0;
    for (int i = 0; i < histMatchNumControlPoints - 1; i++) {
        if (x >= histMatchControlInputs[i] && x <= histMatchControlInputs[i + 1]) {
            segment = i;
            break;
        }
    }
    
    // Handle edge cases
    if (x <= histMatchControlInputs[0]) {
        return histMatchControlOutputs[0];
    }
    if (x >= histMatchControlInputs[histMatchNumControlPoints - 1]) {
        return histMatchControlOutputs[histMatchNumControlPoints - 1];
    }
    
    // Hermite interpolation within segment
    highp float x0 = histMatchControlInputs[segment];
    highp float x1 = histMatchControlInputs[segment + 1];
    highp float y0 = histMatchControlOutputs[segment];
    highp float y1 = histMatchControlOutputs[segment + 1];
    highp float m0 = histMatchControlTangents[segment];
    highp float m1 = histMatchControlTangents[segment + 1];
    
    // Normalize to [0, 1] within segment
    highp float t = (x - x0) / (x1 - x0);
    t = clamp(t, 0.0, 1.0);
    
    // Scale tangents by segment width
    highp float h = x1 - x0;
    m0 *= h;
    m1 *= h;
    
    // Hermite basis functions
    highp float t2 = t * t;
    highp float t3 = t2 * t;
    highp float h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
    highp float h10 = t3 - 2.0 * t2 + t;
    highp float h01 = -2.0 * t3 + 3.0 * t2;
    highp float h11 = t3 - t2;
    
    // Interpolate
    return h00 * y0 + h10 * m0 + h01 * y1 + h11 * m1;
}

// Histogram matching compression (color-preserving version)
// Only used when inputIsRgb <= 0 (xyY input path)
// 
// Hybrid approach:
// 1. Uses RGB-space histogram matching to calculate the correct exposure adjustment
// 2. Applies that adjustment while preserving both color ratios AND saturation
//    by maintaining the color vector (distance from neutral gray)
vec3 histogramMatchCompress(vec3 rgb, float maxHdr) {
    // Calculate luminance
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    if (luma < 0.0001) return rgb;
    
    // Normalize for histogram matching
    float normalizedLuma = luma;
    if (maxHdr > 1.0) {
        normalizedLuma = min(luma / maxHdr, 1.0);
    }
    
    // Convert to gamma space for curve evaluation
    float gammaLuma = gammaEncode(normalizedLuma);
    
    // Evaluate histogram matching curve (this gives us the correct exposure)
    float targetGammaLuma = evaluateToneCurve(gammaLuma);
    
    // Convert back to linear to get target luminance
    float targetLinearLuma = gammaDecode(targetGammaLuma);
    
    // Step 1: Calculate the exposure adjustment ratio
    float lumaScale = targetLinearLuma / max(normalizedLuma, 0.0001);
    
    // Step 2: Apply adjustment while preserving color and saturation
    // Calculate the neutral gray value at the current luminance
    vec3 gray = vec3(luma);
    
    // Calculate the color vector (distance from gray - this represents saturation)
    vec3 colorVector = rgb - gray;
    
    // Apply luminance adjustment to the gray component
    vec3 newGray = vec3(targetLinearLuma);
    
    // Preserve the color vector (maintains both color direction and saturation magnitude)
    // This preserves both the color ratios (hue/chromaticity) and saturation level
    vec3 newRGB = newGray + colorVector;
    
    // Ensure no negative values
    newRGB = max(newRGB, vec3(0.0));
    
    return newRGB;
}

// Compression wrapper functions are now in shared include file (import_compression.glsl)
// Use the color-preserving vec3 versions: fullHDRCompress(vec3, int) and shoulderRolloff(vec3, int)

// ============================================================================
// LIGHTROOM-STYLE TONE ADJUSTMENTS
// ============================================================================

// Highlight adjustment: Lightroom-style
// strength: -1.0 = recover/compress highlights (darken), +1.0 = boost highlights (brighten)
// In Lightroom: left (negative) = recover, right (positive) = boost
// 
// IMPROVED: Uses monotonic curve approach to prevent tone inversions.
// The adjustment is applied as a luminance curve modification that:
// 1. Never causes highlights to become darker than midtones
// 2. Preserves tonal ordering (monotonicity)
// 3. Has smooth rolloff at the threshold boundary
vec3 adjustHighlights(vec3 rgb, float strength) {
    if (abs(strength) < 0.001) return rgb;
    
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    if (luma < 0.001) return rgb;  // Protect near-black values
    
    // HDR-aware: normalize luma for curve calculation
    float normalizedLuma = min(luma, 1.0);
    bool isHDR = luma > 1.0;
    
    // IMPROVED: Lower threshold for better effect on typical images
    // Most images after HDR compression have values in [0, 0.5] range
    const float highlightStart = 0.4;   // Lowered from 0.5 - affects more pixels
    const float highlightFull = 0.8;    // Lowered from 0.85 - wider effective range
    
    // Compute position in highlight zone [0, 1]
    // 0 = at threshold, 1 = pure white/HDR
    float highlightPos = smoothstep(highlightStart, highlightFull, normalizedLuma);
    
    // For HDR values, extend the highlight position beyond 1.0
    float effectivePos = highlightPos;
    if (isHDR) {
        // HDR values get progressively stronger effect
        effectivePos = mix(highlightPos, 1.0, min((luma - 1.0) * 0.5, 1.0));
    }
    
    // Calculate the target luminance using a monotonic curve
    float targetLuma;
    
    if (strength > 0.0) {
        // Positive = boost highlights (brighten)
        // IMPROVED: Much stronger effect multiplier
        float boostAmount = effectivePos * strength * 1.2;  // Increased from 0.5
        
        if (isHDR) {
            // For HDR, use multiplicative boost
            targetLuma = luma * (1.0 + boostAmount * 0.8);  // Increased from 0.6
        } else {
            // For SDR, use asymptotic approach to 1.0
            targetLuma = normalizedLuma + boostAmount * (1.0 - normalizedLuma);
        }
    } else {
        // Negative = recover/compress highlights
        // KEY FIX: Use a curve that compresses highlights toward midtones
        // without pushing them below the threshold.
        //
        // The curve smoothly maps:
        //   - Values at highlightStart stay at highlightStart
        //   - Values at 1.0 move toward highlightStart
        //   - Monotonicity is preserved
        
        float recoveryAmount = -strength;  // 0 to 1
        
        // IMPROVED: Stronger recovery target
        float targetForWhite = mix(1.0, highlightStart + 0.15, recoveryAmount * 0.9);  // Increased from 0.8
        
        // Linear interpolation in highlight zone preserves monotonicity
        float compressedInZone = mix(highlightStart, targetForWhite, highlightPos);
        
        // IMPROVED: Much wider blend zone for better effect
        // Apply effect to ALL pixels in highlight zone, not just narrow band
        float blendFactor = smoothstep(highlightStart - 0.2, highlightStart + 0.3, normalizedLuma);
        // Use full blendFactor strength
        targetLuma = mix(normalizedLuma, compressedInZone, blendFactor * recoveryAmount);
        
        // Ensure we never go below a safe floor (prevents inversions)
        float safeFloor = highlightStart * (1.0 - recoveryAmount * 0.3);
        if (normalizedLuma > highlightStart) {
            targetLuma = max(targetLuma, safeFloor);
        }
        
        if (isHDR) {
            // IMPROVED: Stronger HDR compression
            float excess = luma - 1.0;
            float hdrCompression = 1.0 + excess * recoveryAmount * 0.8;  // Increased from 0.7
            targetLuma = targetLuma + (luma - normalizedLuma) / hdrCompression;
        }
    }
    
    // Apply the luminance change while preserving color ratios
    float lumaScale = targetLuma / luma;
    return rgb * lumaScale;
}

// Shadow adjustment: Lightroom-style
// strength: -1.0 = crush shadows (darken), +1.0 = lift shadows (brighten)
// In Lightroom: left (negative) = darken, right (positive) = brighten
//
// IMPROVED: Better zone definition and bounded formulas that:
// 1. Create smoother rolloff at the shadow/midtone boundary
// 2. Prevent shadows from exceeding the zone ceiling when lifted
// 3. Maintain monotonicity (darker pixels always remain darker than lighter ones)
vec3 adjustShadows(vec3 rgb, float strength) {
    if (abs(strength) < 0.001) return rgb;
    
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    if (luma < 0.0001) return rgb;  // Protect pure black
    
    // HDR-aware: normalize luma for mask calculation
    float normalizedLuma = min(luma, 1.0);
    bool isHDR = luma > 1.0;
    
    // Shadow zone definition with wider transition for smoother rolloff
    const float shadowEnd = 0.35;       // Core shadow zone ends here
    const float transitionEnd = 0.50;   // Full transition to midtones
    
    // Shadow mask with smooth rolloff using cosine curve (C∞ smooth at both endpoints)
    // Full effect in deep shadows, gradual reduction toward midtones
    // Cosine curve: 1.0 at luma=0, smoothly falls to 0.0 at shadowEnd
    float shadowT = smoothstep(0.0, shadowEnd, normalizedLuma);
    float shadowMask = 0.5 + 0.5 * cos(shadowT * PI);  // C∞ smooth cosine falloff
    
    // Extended transition mask for seamless blending into midtones
    float transitionMask = 1.0 - smoothstep(shadowEnd, transitionEnd, normalizedLuma);
    
    // Combine masks: full effect in shadows, partial in transition zone
    float effectMask = shadowMask + (1.0 - shadowMask) * transitionMask * 0.3;
    
    // For HDR values, greatly reduce shadow adjustment (they're highlights)
    if (isHDR) {
        effectMask *= 0.05;
    }
    
    if (strength > 0.0) {
        // Positive = lift shadows (brighten)
        // Use bounded asymptotic formula: shadows approach ceiling but don't exceed it
        
        float liftAmount = effectMask * strength;
        
        // Ceiling for lifted shadows: prevents shadows from becoming midtones
        float ceiling = transitionEnd;  // ~0.5
        
        // Asymptotic lift formula: rgb + lift * (ceiling - luma)
        // This naturally limits how far shadows can be lifted
        // Darker shadows get more lift (proportionally), lighter shadows get less
        float headroom = max(ceiling - normalizedLuma, 0.0);
        float lift = liftAmount * 0.6;  // Strength factor
        
        // Apply lift proportionally to RGB channels (preserves color)
        vec3 lifted;
        if (isHDR) {
            // For HDR, use simple multiplicative boost
            lifted = rgb * (1.0 + lift * 0.3);
        } else {
            // Bounded lift: approaches ceiling asymptotically
            float targetLuma = normalizedLuma + lift * headroom;
            float scale = targetLuma / normalizedLuma;
            lifted = rgb * scale;
        }
        
        rgb = lifted;
        
    } else {
        // Negative = crush shadows (darken)
        // Multiplicative darkening with detail preservation
        
        float crushAmount = -strength;
        float crush = effectMask * crushAmount;
        
        // Graduated crush: very dark values get slightly less crushing
        // to preserve some shadow detail (like film toe curve)
        float detailPreserve = smoothstep(0.0, 0.08, normalizedLuma);
        float effectiveCrush = crush * (0.5 + 0.5 * detailPreserve);
        
        // Apply multiplicative crush
        vec3 crushed;
        if (isHDR) {
            // Minimal crushing for HDR values
            crushed = rgb * (1.0 - effectiveCrush * 0.1);
        } else {
            crushed = rgb * (1.0 - effectiveCrush);
        }
        
        // Preserve minimum luminance to avoid complete black
        float minLuma = 0.002 * (1.0 - crushAmount * 0.3);
        float crushedLuma = dot(crushed, vec3(0.2126, 0.7152, 0.0722));
        if (crushedLuma < minLuma && luma > 0.001) {
            crushed = crushed * (minLuma / max(crushedLuma, 0.0001));
        }
        
        rgb = crushed;
    }
    
    // Smooth rolloff to zero instead of hard clamp to prevent banding
    return smoothMaxVec3(rgb, 0.0, 0.001);  // 0.1% transition zone - only affects near-zero values
}

// Contrast adjustment with shadow/highlight protection
// strength: -1.0 = reduce contrast, +1.0 = increase contrast
// Uses luminance-space processing to preserve color, with zone-based protection
// HDR-aware: handles values > 1.0 correctly
//
// IMPROVED: Much stronger S-curve to match Lightroom behavior.
// Uses a sigmoid-based curve for natural contrast enhancement.
vec3 adjustContrast(vec3 rgb, float strength) {
    if (abs(strength) < 0.001) return rgb;
    
    // Work in luminance space to preserve color (prevents color shifts)
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    if (luma < 0.0001) return rgb;  // Protect near-black
    
    vec3 chroma = rgb / luma; // Preserve chroma ratios
    
    // HDR-aware: normalize luma for curve calculation
    float normalizedLuma = min(luma, 1.0);
    bool isHDR = luma > 1.0;
    
    // Protection zones with smooth transitions
    // Shadows: protect 0-15% to prevent crushing blacks
    // Highlights: protect 90-100% to prevent clipping whites
    float shadowProtect = 1.0 - smoothstep(0.0, 0.15, normalizedLuma);
    float highlightProtect = smoothstep(0.90, 1.0, normalizedLuma);
    
    // Midtone zone gets full effect
    float midtoneWeight = 1.0 - shadowProtect * 0.7 - highlightProtect * 0.7;
    
    // For HDR values, reduce effect in proportion to how far above 1.0
    float hdrReduction = 1.0;
    if (isHDR) {
        hdrReduction = 1.0 / (1.0 + (luma - 1.0) * 2.0);
    }
    
    // Compute effective strength with zone weighting
    float effectiveStrength = strength * midtoneWeight * hdrReduction;
    
    // Midpoint for contrast pivot
    const float mid = 0.5;
    float deviation = normalizedLuma - mid;
    
    // IMPROVED S-curve using a combination of techniques:
    // 1. Stronger base curve coefficient (was 0.75, now 2.0)
    // 2. Use both cubic and linear components for better control
    // 3. Add sigmoidal shaping for more natural rolloff
    
    float enhancedLuma;
    
    if (abs(effectiveStrength) > 0.001) {
        // Sigmoidal contrast curve
        // Maps [0,1] -> [0,1] with adjustable steepness
        // Steepness factor: higher = more contrast
        float steepness = 1.0 + abs(effectiveStrength) * 4.0;  // 1.0 to 5.0
        
        if (effectiveStrength > 0.0) {
            // Increase contrast: steeper S-curve
            // Use a modified sigmoid: more effect in midtones, protected at extremes
            float t = normalizedLuma;
            
            // Compute sigmoid centered at midpoint
            // sigmoid(x) = 1 / (1 + exp(-k*(x-0.5)))
            // We approximate this with a polynomial for GPU efficiency
            float x = (t - mid) * steepness;
            float sigmoid = 0.5 + 0.5 * x / (1.0 + abs(x));  // Fast sigmoid approximation
            
            // Blend between linear (original) and sigmoid based on strength
            enhancedLuma = mix(normalizedLuma, sigmoid, effectiveStrength * 0.8);
            
            // Add cubic boost for extra punch in midtones
            float cubicBoost = deviation * deviation * deviation * effectiveStrength * 1.5;
            enhancedLuma += cubicBoost;
            
        } else {
            // Decrease contrast: flatten the curve
            // Move values toward middle gray
            float flattenAmount = -effectiveStrength * 0.6;
            enhancedLuma = mix(normalizedLuma, mid, flattenAmount);
            
            // Preserve some of the original structure
            float preserveDetail = deviation * (1.0 - flattenAmount * 0.5);
            enhancedLuma += preserveDetail * 0.3;
        }
    } else {
        enhancedLuma = normalizedLuma;
    }
    
    // Clamp to valid range for SDR
    enhancedLuma = clamp(enhancedLuma, 0.001, 1.0);
    
    // Shadow preservation: prevent crushing very dark values
    if (normalizedLuma > 0.0 && normalizedLuma < 0.03) {
        float minPreserve = normalizedLuma * 0.5;
        enhancedLuma = max(enhancedLuma, minPreserve);
    }
    
    // Highlight preservation: prevent hard clipping
    if (normalizedLuma > 0.97) {
        float maxAllow = mix(normalizedLuma, 1.0, 0.5);
        enhancedLuma = min(enhancedLuma, maxAllow);
    }
    
    // For HDR values, scale proportionally
    if (isHDR) {
        float hdrRatio = luma / normalizedLuma;
        enhancedLuma = enhancedLuma * hdrRatio;
    }
    
    // Reconstruct RGB from enhanced luminance and original chroma
    return enhancedLuma * chroma;
}

// Blacks adjustment: Lightroom-style black point adjustment
// strength: -1.0 = crush blacks (deepen), +1.0 = lift blacks (lighten)
// In Lightroom: left (negative) = deepen, right (positive) = lighten
// HDR-aware: skips adjustment for HDR values (they're highlights, not shadows)
//
// IMPROVED: Uses bounded formulas that prevent blacks from crossing into midtones
// and ensures smooth rolloff at the zone boundary.
vec3 adjustBlacks(vec3 rgb, float strength) {
    if (abs(strength) < 0.001) return rgb;
    
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    
    // HDR-aware: skip black adjustment for HDR values (they're highlights, not shadows)
    if (luma > 1.0) {
        return rgb;  // No black adjustment for HDR highlights
    }
    
    // Black zone definition
    const float blackEnd = 0.25;       // Where black zone ends
    const float transitionEnd = 0.35;  // Where transition to midtones completes
    
    // Compute position in black zone with smooth rolloff using cosine curve (C∞ smooth)
    // blackMask = 1.0 for pure black, 0.0 for midtones and above
    // Cosine curve provides smoother falloff than polynomial
    float blackT = smoothstep(0.0, blackEnd, luma);
    float blackMask = 0.5 + 0.5 * cos(blackT * PI);  // C∞ smooth cosine falloff
    
    // Additional smooth transition zone to prevent hard boundaries
    float transitionMask = 1.0 - smoothstep(blackEnd, transitionEnd, luma);
    
    if (strength > 0.0) {
        // Positive = lift blacks (raise black point, lighten)
        // IMPROVED: Use bounded asymptotic formula instead of simple addition
        // This prevents blacks from becoming brighter than the zone ceiling
        
        float liftAmount = blackMask * strength;
        
        // Target ceiling: blacks should not exceed the zone boundary
        float ceiling = blackEnd * 0.8;  // ~0.2, keeps lifted blacks in shadow range
        
        // Asymptotic lift: approaches ceiling but never exceeds it
        // Formula: luma + lift * (ceiling - luma) ensures we stay below ceiling
        float lift = liftAmount * 0.4;
        vec3 lifted = rgb + lift * (ceiling - luma) * (rgb / max(luma, 0.001));
        
        // Apply with transition mask for smooth blending at boundary
        rgb = mix(rgb, lifted, transitionMask);
        
    } else {
        // Negative = crush blacks (lower black point, deepen)
        // IMPROVED: Stronger crushing with detail preservation in very dark areas
        
        float crushAmount = -strength;
        float crush = blackMask * crushAmount;
        
        // Graduated crushing: darker values get crushed more
        // This creates a natural "toe" curve like film
        float crushCurve = crush * (0.6 - 0.4 * smoothstep(0.0, 0.1, luma));
        
        // Apply multiplicative crush
        vec3 crushed = rgb * (1.0 - crushCurve);
        
        // Preserve minimum detail to avoid pure black (allows some shadow detail)
        float minDetail = 0.005 * (1.0 - crushAmount * 0.5);
        crushed = max(crushed, vec3(minDetail));
        
        // Apply with transition mask for smooth blending
        rgb = mix(rgb, crushed, transitionMask);
    }
    
    // Smooth rolloff to zero instead of hard clamp to prevent banding
    return smoothMaxVec3(rgb, 0.0, 0.001);  // 0.1% transition zone - only affects near-zero values
}

// Whites adjustment: Lightroom-style white point adjustment
// strength: -1.0 = compress whites (decrease), +1.0 = expand whites (increase)
// In Lightroom: left (negative) = decrease, right (positive) = increase
// HDR-aware: handles values > 1.0 correctly
// Whites adjustment: Lightroom-style white point adjustment
// strength: -1.0 = compress whites (decrease), +1.0 = expand whites (increase)
// In Lightroom: left (negative) = decrease, right (positive) = increase
// HDR-aware: handles values > 1.0 correctly
//
// IMPROVED: Uses monotonic curve approach to prevent tone inversions,
// consistent with the improved highlights adjustment.
vec3 adjustWhites(vec3 rgb, float strength) {
    if (abs(strength) < 0.001) return rgb;
    
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    if (luma < 0.001) return rgb;  // Protect very dark values
    
    // HDR-aware: normalize luma for mask calculation
    float normalizedLuma = min(luma, 1.0);
    bool isHDR = luma > 1.0;
    
    // IMPROVED: Lower threshold - most images have pixels in this range after compression
    const float whiteStart = 0.60;   // Lowered from 0.70 - affects more pixels
    const float whiteFull = 0.85;    // Lowered from 0.90 - wider effective range
    
    // Compute position in white zone [0, 1]
    float whitePos = smoothstep(whiteStart, whiteFull, normalizedLuma);
    
    // For HDR values, extend effect
    float effectivePos = whitePos;
    if (isHDR) {
        effectivePos = mix(whitePos, 1.0, min((luma - 1.0) * 0.5, 1.0));
    }
    
    // Calculate target luminance
    float targetLuma;
    
    if (strength > 0.0) {
        // Positive = expand whites (push toward white/clipping)
        // IMPROVED: Much stronger effect multiplier
        float expandAmount = effectivePos * strength * 0.8;  // Increased from 0.4
        
        if (isHDR) {
            // For HDR, use multiplicative expansion
            targetLuma = luma * (1.0 + expandAmount * 0.7);  // Increased from 0.5
        } else {
            // For SDR, asymptotic approach to 1.0
            float headroom = 1.0 - normalizedLuma;
            targetLuma = normalizedLuma + expandAmount * headroom;
        }
    } else {
        // Negative = compress whites (pull away from clipping)
        // Use monotonic compression that preserves tonal ordering
        float compressAmount = -strength;
        
        // IMPROVED: Stronger compression target
        float targetForWhite = mix(1.0, whiteStart + 0.2, compressAmount * 0.8);  // Increased from 0.7
        
        // Linear interpolation in white zone preserves monotonicity
        float compressedInZone = mix(whiteStart, targetForWhite, whitePos);
        
        // IMPROVED: Wider blend zone for better effect
        float blendFactor = smoothstep(whiteStart - 0.15, whiteStart + 0.2, normalizedLuma);
        targetLuma = mix(normalizedLuma, compressedInZone, blendFactor * compressAmount);
        
        // Ensure monotonicity: never go below a safe floor
        float safeFloor = whiteStart * (1.0 - compressAmount * 0.2);
        if (normalizedLuma > whiteStart) {
            targetLuma = max(targetLuma, safeFloor);
        }
        
        if (isHDR) {
            // IMPROVED: Stronger HDR compression
            float excess = luma - 1.0;
            float hdrCompression = 1.0 + excess * compressAmount * 0.7;  // Increased from 0.6
            targetLuma = targetLuma + (luma - normalizedLuma) / hdrCompression;
        }
    }
    
    // Apply luminance change while preserving color ratios
    float lumaScale = targetLuma / luma;
    return rgb * lumaScale;
}

// Dehaze: remove atmospheric haze or add haze effect (contrast part only)
// strength: -1.0 = add haze, +1.0 = remove haze
// HDR-aware: handles values > 1.0 correctly
// NOTE: Saturation effects moved to applyColorAdjustmentsLab() for efficiency
// Returns: modified RGB and dehaze saturation adjustment factor via out parameter
vec3 applyDehazeContrast(vec3 rgb, float strength, out float dehazeSatFactor) {
    dehazeSatFactor = 0.0;  // No saturation adjustment by default
    
    if (abs(strength) < 0.001) return rgb;
    
    // Calculate input brightness to scale effect and prevent highlight artifacts
    float inputLuma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    
    // HDR-aware: normalize luma for smoothstep calculations
    float normalizedLuma = min(inputLuma, 1.5);  // Allow up to 1.5 for smoothstep range
    bool isHDR = inputLuma > 1.0;
    
    // Estimate atmospheric light (typically the brightest diffuse color)
    float minChannel = min(min(rgb.r, rgb.g), rgb.b);
    float maxChannel = max(max(rgb.r, rgb.g), rgb.b);
    
    // Haze tends to raise the minimum channel (reducing contrast)
    // Dark channel prior: haze-free images have very low minimum in dark regions
    float hazeAmount = minChannel;
    
    if (strength > 0.0) {
        // Remove haze: increase contrast, especially in low-contrast areas
        
        // Scale effect strength based on input brightness to reduce impact on highlights
        // Brightness factor: 1.0 for dark pixels, decreasing to ~0.3 for bright pixels
        // Using smoothstep for smooth rolloff starting around 0.3 luma
        float brightnessFactor = mix(1.0, 0.3, smoothstep(0.3, 1.2, normalizedLuma));
        
        // For HDR values, further reduce dehaze strength
        if (isHDR) {
            brightnessFactor *= 0.2;  // Minimal dehaze for HDR highlights
        }
        
        float effectiveStrength = strength * brightnessFactor;
        
        // Subtract estimated haze and rescale
        float transmission = 1.0 - hazeAmount * effectiveStrength * 0.8;
        transmission = max(transmission, 0.1); // Prevent division issues
        
        // Apply rolloff curve to limit amplification in highlights
        // For bright pixels, use a gentler transmission to prevent extreme amplification
        float rolloffFactor = mix(1.0, 0.5, smoothstep(0.5, 1.5, normalizedLuma));
        transmission = mix(transmission, 1.0, (1.0 - rolloffFactor) * 0.4);
        transmission = max(transmission, 0.1);
        
        // For HDR values, use gentler transmission to avoid extreme amplification
        if (isHDR) {
            transmission = mix(transmission, 1.0, 0.6);  // Stronger rolloff for HDR
        }
        
        rgb = (rgb - hazeAmount * effectiveStrength * 0.4) / transmission;
        
        // Output saturation boost factor for Lab processing
        // (Haze desaturates, so removing haze should boost saturation)
        dehazeSatFactor = effectiveStrength * 0.15;
    } else {
        // Add haze: reduce contrast, desaturate, lighten shadows
        float hazeStrength = -strength;
        
        // For HDR values, reduce haze addition
        if (isHDR) {
            hazeStrength *= 0.2;  // Minimal haze for HDR highlights
        }
        
        vec3 hazeColor = vec3(0.8, 0.85, 0.9); // Slight blue tint
        rgb = mix(rgb, hazeColor, hazeStrength * 0.3);
        
        // Output desaturation factor for Lab processing
        dehazeSatFactor = -hazeStrength * 0.2;
    }
    
    // Smooth rolloff to zero instead of hard clamp to prevent banding
    // Preserve full range for final clamp (HDR values > 1.0 allowed)
    return smoothMaxVec3(rgb, 0.0, 0.001);  // 0.1% transition zone - only affects near-zero values
}

// Legacy dehaze function (self-contained with RGB saturation adjustment)
vec3 applyDehaze(vec3 rgb, float strength) {
    float dehazeSatFactor;
    rgb = applyDehazeContrast(rgb, strength, dehazeSatFactor);
    
    // Apply saturation adjustment in RGB space (for backward compatibility)
    if (abs(dehazeSatFactor) > 0.001) {
        float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
        rgb = mix(vec3(luma), rgb, 1.0 + dehazeSatFactor);
    }
    
    return max(rgb, vec3(0.0));
}

// =====================================================
// LAB COLORSPACE CONVERSION FUNCTIONS
// Used for perceptually uniform saturation adjustment
// =====================================================

// Smoothed sRGB to linear RGB (inverse gamma)
// Uses smooth blending near the threshold to prevent banding from C2 discontinuity
vec3 srgb_to_linear(vec3 srgb) {
    vec3 linear;
    const float threshold = 0.04045;
    const float transitionWidth = 0.02;  // Smooth transition zone
    const float lowEnd = threshold - transitionWidth;
    const float highEnd = threshold + transitionWidth;
    
    for (int i = 0; i < 3; i++) {
        float c = srgb[i];
        // Linear portion: c / 12.92
        float linearResult = c / 12.92;
        // Power portion: pow((c + 0.055) / 1.055, 2.4)
        float powerResult = pow((c + 0.055) / 1.055, 2.4);
        
        // Smooth blend between the two using smoothstep
        // Below lowEnd: pure linear, above highEnd: pure power
        float blend = smoothstep(lowEnd, highEnd, c);
        linear[i] = mix(linearResult, powerResult, blend);
    }
    return linear;
}

// Smoothed linear RGB to sRGB (gamma encoding)
// Uses smooth blending near the threshold to prevent banding from C2 discontinuity
vec3 linear_to_srgb(vec3 linear) {
    vec3 srgb;
    const float threshold = 0.0031308;
    const float transitionWidth = 0.002;  // Smooth transition zone
    const float lowEnd = threshold - transitionWidth;
    const float highEnd = threshold + transitionWidth;
    
    for (int i = 0; i < 3; i++) {
        float c = linear[i];
        // Linear portion: c * 12.92
        float linearResult = c * 12.92;
        // Power portion: 1.055 * pow(c, 1.0 / 2.4) - 0.055
        float powerResult = 1.055 * pow(max(c, 0.0001), 1.0 / 2.4) - 0.055;
        
        // Smooth blend between the two using smoothstep
        // Below lowEnd: pure linear, above highEnd: pure power
        float blend = smoothstep(lowEnd, highEnd, c);
        srgb[i] = mix(linearResult, powerResult, blend);
    }
    return srgb;
}

// Linear RGB to XYZ (D65 white point, sRGB primaries)
vec3 rgb_to_xyz(vec3 rgb) {
    // sRGB to XYZ matrix (D65)
    mat3 M = mat3(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041
    );
    return M * rgb;
}

// XYZ to linear RGB (D65 white point, sRGB primaries)
vec3 xyz_to_rgb(vec3 xyz) {
    // XYZ to sRGB matrix (D65)
    mat3 M = mat3(
         3.2404542, -1.5371385, -0.4985314,
        -0.9692660,  1.8760108,  0.0415560,
         0.0556434, -0.2040259,  1.0572252
    );
    return M * xyz;
}

// XYZ to Lab (D65 white point: X=0.95047, Y=1.0, Z=1.08883)
vec3 xyz_to_lab(vec3 xyz) {
    // D65 white point
    const vec3 white = vec3(0.95047, 1.0, 1.08883);
    
    // Normalize by white point
    vec3 n = xyz / white;
    
    // Smoothed Lab transfer function to prevent banding from C2 discontinuity
    const float epsilon = 216.0 / 24389.0;  // 0.008856
    const float kappa = 24389.0 / 27.0;     // 903.3
    const float transitionWidth = 0.005;    // Smooth transition zone
    const float lowEnd = epsilon - transitionWidth;
    const float highEnd = epsilon + transitionWidth;
    
    vec3 f;
    for (int i = 0; i < 3; i++) {
        float x = n[i];
        // Linear portion: (kappa * x + 16.0) / 116.0
        float linearResult = (kappa * x + 16.0) / 116.0;
        // Cube root portion: pow(x, 1.0 / 3.0)
        float cubeRootResult = pow(max(x, 0.0001), 1.0 / 3.0);
        
        // Smooth blend using smoothstep
        float blend = smoothstep(lowEnd, highEnd, x);
        f[i] = mix(linearResult, cubeRootResult, blend);
    }
    
    // L, a, b
    float L = 116.0 * f.y - 16.0;
    float a = 500.0 * (f.x - f.y);
    float b = 200.0 * (f.y - f.z);
    
    return vec3(L, a, b);
}

// Lab to XYZ (D65 white point)
vec3 lab_to_xyz(vec3 lab) {
    // D65 white point
    const vec3 white = vec3(0.95047, 1.0, 1.08883);
    
    float L = lab.x;
    float a = lab.y;
    float b = lab.z;
    
    // Inverse Lab transfer
    float fy = (L + 16.0) / 116.0;
    float fx = a / 500.0 + fy;
    float fz = fy - b / 200.0;
    
    // Smoothed inverse Lab transfer function to prevent banding from C2 discontinuity
    const float epsilon = 216.0 / 24389.0;
    const float kappa = 24389.0 / 27.0;
    const float transitionWidth = 0.005;
    const float lowEnd = epsilon - transitionWidth;
    const float highEnd = epsilon + transitionWidth;
    
    // For xr: threshold is on fx^3
    float fx3 = fx * fx * fx;
    float xr_linear = (116.0 * fx - 16.0) / kappa;
    float xr_cube = fx3;
    float xr = mix(xr_linear, xr_cube, smoothstep(lowEnd, highEnd, fx3));
    
    // For yr: threshold is on L (using kappa * epsilon)
    float yr_threshold = kappa * epsilon;  // ~7.9996
    float yr_transWidth = 2.0;  // Transition zone in L units
    float yr_linear = L / kappa;
    float yr_cube = pow((L + 16.0) / 116.0, 3.0);
    float yr = mix(yr_linear, yr_cube, smoothstep(yr_threshold - yr_transWidth, yr_threshold + yr_transWidth, L));
    
    // For zr: threshold is on fz^3
    float fz3 = fz * fz * fz;
    float zr_linear = (116.0 * fz - 16.0) / kappa;
    float zr_cube = fz3;
    float zr = mix(zr_linear, zr_cube, smoothstep(lowEnd, highEnd, fz3));
    
    return vec3(xr, yr, zr) * white;
}

// Full RGB to Lab conversion
vec3 rgb_to_lab(vec3 rgb) {
    // Clamp to valid range for Lab conversion
    rgb = max(rgb, vec3(0.0));
    vec3 linear = srgb_to_linear(rgb);
    vec3 xyz = rgb_to_xyz(linear);
    return xyz_to_lab(xyz);
}

// Full Lab to RGB conversion
vec3 lab_to_rgb(vec3 lab) {
    vec3 xyz = lab_to_xyz(lab);
    vec3 linear = xyz_to_rgb(xyz);
    // Clamp to valid range before gamma encoding
    linear = max(linear, vec3(0.0));
    return linear_to_srgb(linear);
}

// =====================================================
// LUV COLORSPACE CONVERSION
// =====================================================
// Luv is superior to Lab for emissive/display colors:
// - More stable hue when chroma changes (no hue shifts!)
// - Better hue distribution (more uniform across 0-360°)
// - Designed for self-illuminated colors (displays, monitors)
// Reference: https://gist.github.com/Myndex/47c793f8a054041bd2b52caa7ad5271c
//
// Lab suffers from unstable hue that changes when chroma changes,
// which is exactly the problem we're trying to solve.

// XYZ to Luv (D65 white point)
vec3 xyz_to_luv(vec3 xyz) {
    // D65 white point
    const vec3 white = vec3(0.95047, 1.0, 1.08883);
    
    // Calculate u' and v' for sample and white point
    float denom = xyz.x + 15.0 * xyz.y + 3.0 * xyz.z;
    if (denom < 0.0001) {
        return vec3(0.0, 0.0, 0.0);  // Very dark or invalid
    }
    
    float u_prime = 4.0 * xyz.x / denom;
    float v_prime = 9.0 * xyz.y / denom;
    
    float denom_white = white.x + 15.0 * white.y + 3.0 * white.z;
    float u_prime_white = 4.0 * white.x / denom_white;
    float v_prime_white = 9.0 * white.y / denom_white;
    
    // L* (same as Lab) - smoothed to prevent banding from C2 discontinuity
    float yr = xyz.y / white.y;
    const float epsilon = 216.0 / 24389.0;
    const float kappa = 24389.0 / 27.0;
    const float transitionWidth = 0.005;
    const float lowEnd = epsilon - transitionWidth;
    const float highEnd = epsilon + transitionWidth;
    
    float L_linear = kappa * yr;
    float L_power = 116.0 * pow(max(yr, 0.0001), 1.0/3.0) - 16.0;
    float L = mix(L_linear, L_power, smoothstep(lowEnd, highEnd, yr));
    
    // u* and v*
    float u = 13.0 * L * (u_prime - u_prime_white);
    float v = 13.0 * L * (v_prime - v_prime_white);
    
    return vec3(L, u, v);
}

// Luv to XYZ (D65 white point)
vec3 luv_to_xyz(vec3 luv) {
    // D65 white point
    const vec3 white = vec3(0.95047, 1.0, 1.08883);
    
    float L = luv.x;
    float u = luv.y;
    float v = luv.z;
    
    // Handle L = 0 case
    if (L < 0.0001) {
        return vec3(0.0);
    }
    
    // Calculate u' and v' for white point
    float denom_white = white.x + 15.0 * white.y + 3.0 * white.z;
    float u_prime_white = 4.0 * white.x / denom_white;
    float v_prime_white = 9.0 * white.y / denom_white;
    
    // Calculate u' and v' for sample
    float u_prime = u / (13.0 * L) + u_prime_white;
    float v_prime = v / (13.0 * L) + v_prime_white;
    
    // Calculate Y from L* - smoothed to prevent banding from C2 discontinuity
    const float epsilon = 216.0 / 24389.0;
    const float kappa = 24389.0 / 27.0;
    float L_threshold = kappa * epsilon;  // ~7.9996
    float transWidth = 2.0;  // Transition zone in L units
    
    float yr_linear = L / kappa;
    float yr_power = pow((L + 16.0) / 116.0, 3.0);
    float yr = mix(yr_linear, yr_power, smoothstep(L_threshold - transWidth, L_threshold + transWidth, L));
    float Y = yr * white.y;
    
    // Calculate X and Z from Y, u', v'
    if (v_prime < 0.0001) {
        return vec3(0.0, Y, 0.0);
    }
    
    float X = Y * 9.0 * u_prime / (4.0 * v_prime);
    float Z = Y * (12.0 - 3.0 * u_prime - 20.0 * v_prime) / (4.0 * v_prime);
    
    return vec3(X, Y, Z);
}

// Full RGB to Luv conversion
vec3 rgb_to_luv(vec3 rgb) {
    // Smooth rolloff to zero instead of hard clamp
    rgb = smoothMaxVec3(rgb, 0.0, 0.001);  // 0.1% transition zone - only affects near-zero values
    vec3 linear = srgb_to_linear(rgb);
    vec3 xyz = rgb_to_xyz(linear);
    return xyz_to_luv(xyz);
}

// Full Luv to RGB conversion
vec3 luv_to_rgb(vec3 luv) {
    vec3 xyz = luv_to_xyz(luv);
    vec3 linear = xyz_to_rgb(xyz);
    linear = max(linear, vec3(0.0));
    return linear_to_srgb(linear);
}

// =====================================================
// LCHuv COLORSPACE CONVERSION (Polar Luv)
// =====================================================
// LCHuv = Lightness, Chroma, Hue (in Luv space)
// More stable hue than Lab/LCH when chroma changes
// This is the key difference: Luv preserves hue better than Lab

// Convert Luv (L, u, v) to LCHuv (L, C, H)
vec3 luv_to_lchuv(vec3 luv) {
    float L = luv.x;
    float u = luv.y;
    float v = luv.z;
    
    float C = sqrt(u * u + v * v);
    float H = atan(v, u);  // Returns [-π, π]
    
    return vec3(L, C, H);
}

// Convert LCHuv back to Luv
vec3 lchuv_to_luv(vec3 lchuv) {
    float L = lchuv.x;
    float C = lchuv.y;
    float H = lchuv.z;
    
    float u = C * cos(H);
    float v = C * sin(H);
    
    return vec3(L, u, v);
}

// Gamut clipping for LCHuv (same approach as LCH, but using Luv)
vec3 gamut_clip_lchuv(vec3 lchuv) {
    // Handle edge case: zero chroma (grayscale)
    if (lchuv.y < 0.0001) {
        return lchuv;
    }
    
    // Convert LCHuv → Luv → XYZ → RGB to check gamut
    vec3 luv = lchuv_to_luv(lchuv);
    vec3 xyz = luv_to_xyz(luv);
    vec3 linear = xyz_to_rgb(xyz);
    
    // Check if we're out of gamut (negative values)
    float minChannel = min(min(linear.r, linear.g), linear.b);
    bool outOfGamut = minChannel < -0.0001;
    
    if (!outOfGamut) {
        return lchuv;  // Already in gamut
    }
    
    // Binary search for maximum chroma that fits in gamut
    float minC = 0.0;
    float maxC = lchuv.y;
    float bestC = 0.0;
    
    for (int i = 0; i < 12; i++) {
        float testC = (minC + maxC) * 0.5;
        vec3 testLchuv = vec3(lchuv.x, testC, lchuv.z);
        vec3 testLuv = lchuv_to_luv(testLchuv);
        vec3 testXyz = luv_to_xyz(testLuv);
        vec3 testLinear = xyz_to_rgb(testXyz);
        
        float testMin = min(testLinear.r, min(testLinear.g, testLinear.b));
        if (testMin >= -0.0001) {
            bestC = testC;
            minC = testC;
        } else {
            maxC = testC;
        }
    }
    
    return vec3(lchuv.x, bestC, lchuv.z);
}

// =====================================================
// LCH COLORSPACE CONVERSION (Polar Lab) - DEPRECATED
// =====================================================
// NOTE: Lab/LCH has unstable hue when chroma changes.
// We're switching to Luv/LChuv which has stable hue.
// Keeping these functions for reference/compatibility.
// LCH = Lightness, Chroma, Hue
// This is a polar representation of Lab that preserves hue exactly
// when scaling chroma, avoiding hue shifts from Cartesian rounding errors

// Convert Lab (L, a, b) to LCH (L, C, H)
// L = lightness (unchanged)
// C = chroma = sqrt(a² + b²)
// H = hue = atan2(b, a) in radians [-π, π]
vec3 lab_to_lch(vec3 lab) {
    float L = lab.x;
    float a = lab.y;
    float b = lab.z;
    
    float C = sqrt(a * a + b * b);
    float H = atan(b, a);  // Returns [-π, π]
    
    return vec3(L, C, H);
}

// Convert LCH back to Lab
// L = lightness
// C = chroma
// H = hue in radians
vec3 lch_to_lab(vec3 lch) {
    float L = lch.x;
    float C = lch.y;
    float H = lch.z;
    
    float a = C * cos(H);
    float b = C * sin(H);
    
    return vec3(L, a, b);
}

// =====================================================
// GAMUT CLIPPING FOR LCH
// =====================================================
// Reduces chroma until RGB is in valid range, preserving hue and lightness
// This is critical to avoid hue shifts from RGB clamping
// Based on darktable's gamut_check_Yrg approach
vec3 gamut_clip_lch(vec3 lch) {
    // Handle edge case: zero chroma (grayscale)
    if (lch.y < 0.0001) {
        return lch;
    }
    
    // Convert LCH → Lab → XYZ → RGB to check gamut
    vec3 lab = lch_to_lab(lch);
    vec3 xyz = lab_to_xyz(lab);
    vec3 linear = xyz_to_rgb(xyz);
    
    // Check if we're out of gamut (negative values)
    // For HDR, we allow > 1.0, but not negative
    float minChannel = min(min(linear.r, linear.g), linear.b);
    bool outOfGamut = minChannel < -0.0001;  // Small epsilon for floating point
    
    if (!outOfGamut) {
        return lch;  // Already in gamut
    }
    
    // Binary search for maximum chroma that fits in gamut
    // This preserves hue and lightness while reducing chroma
    float minC = 0.0;
    float maxC = lch.y;
    float bestC = 0.0;
    
    // Iterate to find maximum chroma that keeps all channels >= 0
    for (int i = 0; i < 12; i++) {
        float testC = (minC + maxC) * 0.5;
        vec3 testLch = vec3(lch.x, testC, lch.z);
        vec3 testLab = lch_to_lab(testLch);
        vec3 testXyz = lab_to_xyz(testLab);
        vec3 testLinear = xyz_to_rgb(testXyz);
        
        float testMin = min(testLinear.r, min(testLinear.g, testLinear.b));
        if (testMin >= -0.0001) {
            bestC = testC;
            minC = testC;
        } else {
            maxC = testC;
        }
    }
    
    return vec3(lch.x, bestC, lch.z);
}

// =====================================================
// GLOBAL SATURATION ADJUSTMENT - RawTherapee-style (HSV-based)
// =====================================================
// 
// Uses HSV colorspace with RawTherapee's proven approach:
// - Avoids conversion round-trip errors (RGB→Lab/Luv→RGB causes hue shifts)
// - Non-linear curve for positive saturation prevents oversaturation
// - Simple multiply for negative saturation
//
// RawTherapee's approach (from improcfun.cc:2635-2648):
// - For positive saturation: interpolate with curve 1 - (1-s)^4
//   This prevents oversaturation by rolling off as saturation approaches 1.0
// - For negative saturation: simple multiply s * (1 + satby100)
//
// This is simpler and more reliable than Lab/Luv conversions which introduce
// hue shifts due to gamut clipping and precision errors.
//
// strength: includes default offset (0.15) from Java code
//           When slider = 0: strength = 0.15 (default boost)
vec3 applySaturationAdjust(vec3 rgb, float strength) {
    if (abs(strength) < 0.001) return rgb;
    
    // Calculate the effective multiplier
    float satMult = 1.0 + strength;
    
    // Exposure fusion oversaturation fix: apply additional adaptive desaturation
    // for strongly colored areas when fusion is active
    // Skip adaptive desaturation for night images - they need MORE saturation, not less
    float adaptiveReduction = 0.0;
    if (hdrSigmoidalContrast > 0.0 && isNightMode == 0 && baselineExposure > 1.0) {
        // Quick approximate saturation check in RGB space
        float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
        float maxv = max(max(rgb.r, rgb.g), rgb.b);
        float approxSat = (maxv > 0.001) ? 1.0 - luma / maxv : 0.0;
        if (approxSat > 0.4) {
            float satExcess = (approxSat - 0.4) / 0.6;
            float baseReduction = satExcess * satExcess * 0.3;
            // Smooth blending instead of hard clamp
            float compressionScale = smoothClamp((hdrSigmoidalContrast - 1.0) / 5.0, 0.0, 1.0, 0.05) * 0.5 + 0.5;
            adaptiveReduction = baseReduction * compressionScale;
        }
    }
    satMult = satMult - adaptiveReduction;
    
    // Convert RGB → HSV (no round-trip conversion errors!)
    vec3 hsv = rgb2hsv(rgb);
    float s = hsv.y;
    
    if (satMult > 1.0) {
        // Positive saturation: use cosine S-curve (C∞ smooth at both endpoints)
        // Smooth blending for saturation above 1.0 instead of hard clamp
        float satBlend = smoothClamp(s, 0.0, 1.0, 0.001);  // 0.1% transition zone - minimal
        // Cosine S-curve: 0→0, 1→1, with smooth acceleration/deceleration
        float targetS = 0.5 - 0.5 * cos(satBlend * PI);
        float satAmount = smoothClamp(satMult - 1.0, 0.0, 1.0, 0.05);  // Smooth blend to [0, 1]
        s = mix(s, targetS, satAmount);
        s = smoothMax(s, 0.0, 0.001);  // 0.1% transition zone - only affects near-zero
    } else {
        // Negative saturation: simple multiply
        s *= satMult;
        // Smooth rolloff to zero for negative results
        s = smoothMax(s, 0.0, 0.001);  // 0.1% transition zone - only affects near-zero
    }
    
    hsv.y = s;
    
    // Convert HSV → RGB
    rgb = hsv2rgb(hsv);
    
    // Smooth rolloff to zero instead of hard clamp to prevent banding
    return smoothMaxVec3(rgb, 0.0, 0.001);  // 0.1% transition zone - only affects near-zero values for smooth rolloff
}

/* =====================================================
 * COMMENTED OUT: Previous HSV/RGB hybrid approach
 * =====================================================
 * 
 * This approach used HSV for desaturation and RGB luminance mix for saturation.
 * While it worked, it had discontinuity at the transition point and was not
 * perceptually uniform.
 *
vec3 applySaturationAdjust_HybridHSVRGB(vec3 rgb, float strength) {
    if (abs(strength) < 0.001) return rgb;
    
    float satMult = 1.0 + strength;
    
    float adaptiveReduction = 0.0;
    if (hdrSigmoidalContrast > 0.0 && isNightMode == 0 && baselineExposure > 1.0) {
        float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
        float maxv = max(max(rgb.r, rgb.g), rgb.b);
        float approxSat = (maxv > 0.001) ? 1.0 - luma / maxv : 0.0;
        if (approxSat > 0.4) {
            float satExcess = (approxSat - 0.4) / 0.6;
            float baseReduction = satExcess * satExcess * 0.3;
            float compressionScale = clamp((hdrSigmoidalContrast - 1.0) / 5.0, 0.0, 1.0) * 0.5 + 0.5;
            adaptiveReduction = baseReduction * compressionScale;
        }
    }
    satMult = satMult - adaptiveReduction;
    
    // Choose approach based on whether we're saturating or desaturating
    if (satMult < 1.0) {
        // DESATURATION: Use HSV for uniform effect across all hues (fixes cyan)
        float maxv = max(max(rgb.r, rgb.g), rgb.b);
        float minv = min(min(rgb.r, rgb.g), rgb.b);
        if (maxv <= minv) return rgb;  // Grayscale
        
        vec3 hsv = rgb2hsv(rgb);
        hsv.y = hsv.y * satMult;  // Uniform desaturation across all hues
        rgb = hsv2rgb(hsv);
    } else {
        // SATURATION: Use RGB luminance mix (original approach)
        float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
        rgb = mix(vec3(luma), rgb, satMult);
    }
    
    return max(rgb, vec3(0.0));
}
*/

// =====================================================
// VIBRANCE - LChuv-based (Best Practice)
// =====================================================
// Smart saturation that protects skin tones
// strength: -1.0 = desaturate, +1.0 = saturate (protecting skin tones)
// 
// Uses LChuv colorspace for perceptually uniform behavior across all hues.
// Luv has more stable hue than Lab when chroma changes.
// Key features:
// - Affects less saturated colors MORE (classic vibrance behavior)
// - Protects skin tones from over-saturation
// - Perceptually uniform across cyan, red, blue, etc.
// - Preserves hue exactly (more stable than Lab!)
vec3 applyVibrance(vec3 rgb, float strength) {
    if (abs(strength) < 0.001) return rgb;
    
    // Convert RGB → Luv → LChuv
    vec3 luv = rgb_to_luv(rgb);
    vec3 lchuv = luv_to_lchuv(luv);
    
    // Extract components
    float L = lchuv.x;
    float C = lchuv.y;  // Chroma
    float H = lchuv.z;  // Hue
    
    // Normalize chroma for calculations (typical Luv chroma range is 0-128 or so)
    // Use smooth blending instead of hard clamp to prevent banding
    // Much wider transition zone for gentler slope
    float normChroma = smoothClamp(C / 100.0, 0.0, 1.0, 0.001);  // 0.1% transition zone - minimal
    
    // Skin tone detection - EXTREMELY WIDE transitions to prevent banding
    // Use very wide smoothstep ranges to ensure gradual, smooth transitions
    // Hue: very wide range from -0.3 to 1.7 (centered around 0.2-1.4) for gradual falloff
    float skinHue = smoothstep(-0.3, 0.2, H) * smoothstep(1.7, 1.4, H);
    // Chroma: very wide range from -0.2 to 0.8 (centered around 0.05-0.6) for gradual falloff
    float skinChroma = smoothstep(0.8, 0.6, normChroma) * smoothstep(-0.2, 0.05, normChroma);
    float skinScore = skinHue * skinChroma;  // 0.0 to 1.0, very gradually varying
    
    // Vibrance: affects less saturated colors MORE
    // Based on darktable implementation: simple linear formula for smooth, banding-free results
    // Calculate chroma weight: 0.0 for high chroma, 1.0 for low chroma
    // Use smoothstep with wide transition zones for smooth, banding-free results
    float chromaWeight = 1.0 - smoothstep(0.0, 0.8, normChroma);  // Wide transition: 0-80% chroma
    
    // Apply smooth skin tone protection using sine curve - minimum at medium skin tone
    // Sine curve: 0.0 at edges (skinScore=0,1), maximum at center (skinScore=0.5)
    // This gives: 0.0 at edges (full vibrance), 1.0 at center (maximum protection)
    float skinProtection = sin(skinScore * 3.14159265359);  // Smooth sine curve, max protection at 0.5
    float protectedWeight = mix(chromaWeight, chromaWeight * 0.3, skinProtection);  // 0.3x effect at maximum protection
    
    // Simple linear vibrance formula (like darktable): 1.0 + strength * weight
    // This is inherently smooth and banding-free
    float satMult = 1.0 + strength * protectedWeight;
    
    // Scale chroma only (preserves hue exactly - more stable than Lab!)
    lchuv.y *= satMult;  // C (chroma)
    // lchuv.x (L) and lchuv.z (H) unchanged
    
    // CRITICAL: Gamut clip BEFORE converting back to RGB
    // This preserves hue while ensuring valid RGB values
    lchuv = gamut_clip_lchuv(lchuv);
    
    // Convert LChuv → Luv → RGB
    luv = lchuv_to_luv(lchuv);
    rgb = luv_to_rgb(luv);
    
    // Smooth rolloff to zero instead of hard clamp to prevent banding
    // Use minimal transition (0.1%) to avoid crushing blacks
    return smoothMaxVec3(rgb, 0.0, 0.001);  // 0.1% transition zone - only affects near-zero values
}

/* =====================================================
 * COMMENTED OUT: Previous RGB-based vibrance approach
 * =====================================================
 *
vec3 applyVibrance_RGBBased(vec3 rgb, float strength) {
    if (abs(strength) < 0.001) return rgb;
    
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    
    // Calculate current saturation
    float maxC = max(max(rgb.r, rgb.g), rgb.b);
    float minC = min(min(rgb.r, rgb.g), rgb.b);
    float sat = (maxC > 0.001) ? (maxC - minC) / maxC : 0.0;
    
    // Skin tone detection (orange-red hues with moderate saturation)
    float skinScore = 0.0;
    if (rgb.r > rgb.g && rgb.g > rgb.b && sat > 0.1 && sat < 0.6) {
        float warmth = (rgb.r - rgb.b) / (maxC + 0.001);
        skinScore = smoothstep(0.1, 0.4, warmth) * smoothstep(0.6, 0.2, sat);
    }
    
    // Vibrance affects less saturated colors more, and protects skin
    float vibranceAmount = (1.0 - sat) * (1.0 - skinScore * 0.7);
    float satAdjust = 1.0 + strength * vibranceAmount;
    
    rgb = luma + (rgb - luma) * satAdjust;
    
    return max(rgb, vec3(0.0));
}
*/

// =====================================================
// COMBINED COLOR ADJUSTMENTS - Single Lab Conversion
// =====================================================
// Combines dehaze saturation, vibrance, and saturation into a single Lab pass
// to avoid redundant colorspace conversions.
//
// This is more efficient than calling separate functions:
// - Old way: RGB→Lab→RGB (vibrance) + RGB→Lab→RGB (saturation) = 4 conversions
// - New way: RGB→Lab→RGB (combined) = 2 conversions
//
// Parameters:
// - dehazeSatFactor: saturation adjustment from dehaze (positive = boost, negative = reduce)
// - vibranceStrength: vibrance strength (-1 to +1)
// - saturationStrength: global saturation strength (includes 0.15 default offset)
vec3 applyColorAdjustmentsLab(vec3 rgb, float dehazeSatFactor, float vibranceStrength, float saturationStrength) {
    // Skip if no adjustments needed
    bool hasDehazeEffect = abs(dehazeSatFactor) > 0.001;
    bool hasVibranceEffect = abs(vibranceStrength) > 0.001;
    bool hasSaturationEffect = abs(saturationStrength) > 0.001;
    
    if (!hasDehazeEffect && !hasVibranceEffect && !hasSaturationEffect) {
        return rgb;
    }
    
    // === Single conversion to HSV (all adjustments in HSV to avoid hue shifts) ===
    vec3 hsv = rgb2hsv(rgb);
    float h = hsv.x;  // Hue [0, 1]
    float s = hsv.y;     // Saturation [0, 1]
    float v = hsv.z;     // Value [0, 1]
    
    // === 1. Dehaze saturation adjustment (HSV) ===
    // Applied uniformly (no special behavior needed)
    float dehazeMult = 1.0 + dehazeSatFactor;
    
    // === 2. Vibrance: affects less saturated colors MORE, protects skin (HSV) ===
    float vibranceMult = 1.0;
    if (hasVibranceEffect) {
        // Skin tone detection using periodic cosine for natural hue wrapping (C∞ smooth)
        // Cosine naturally handles the wraparound at hue=0/1 (red)
        // Peak detection at skin tone hue center (~0.05 = red-orange)
        float skinHueCenter = 0.05;  // Red-orange skin tone center
        // Use (1 + cos) / 2 to remap from [-1,1] to [0,1] smoothly
        // cos((h - center) * 2π) has period 1, so wraps correctly for hue [0,1]
        // This gives: 1.0 at center, 0.5 at ±0.25 from center, 0.0 at ±0.5 from center
        float skinHue = (1.0 + cos((h - skinHueCenter) * 2.0 * PI)) * 0.5;
        // Square for narrower peak (C1 smooth): 1.0 at center, 0.25 at ±0.25, 0.0 at ±0.5
        skinHue = skinHue * skinHue;
        
        // Saturation: cosine bell curve for smooth falloff (skin tones are moderately saturated)
        // Peak around s=0.3, falls off smoothly to 0 at s=0 and s=0.65
        float satCenter = 0.3;
        float satWidth = 0.35;
        float satDist = abs(s - satCenter) / satWidth;
        // (1 + cos(x*π)) / 2 maps [0,1] to [1,0] smoothly with zero derivative at boundaries
        // For satDist > 1, clamp with min - derivative at satDist=1 is 0, so transition is C1 smooth
        float skinSat = (1.0 + cos(min(satDist, 1.0) * PI)) * 0.5;
        
        float skinScore = skinHue * skinSat;  // 0.0 to 1.0, very gradually varying
        
        // Vibrance affects less saturated colors MORE
        // Based on darktable implementation:
        // - Simple linear formula: 1.0 + strength * vibranceAmount
        // - vibranceAmount is inversely proportional to saturation (1.0 for low sat, 0.0 for high sat)
        // - Use smoothstep for smooth transitions to prevent banding
        
        // Calculate saturation weight: 0.0 for high saturation, 1.0 for low saturation
        // Use smoothstep with wide transition zones for smooth, banding-free results
        // The wider the transition, the smoother the result (0.3 = 30% transition zone)
        float satWeight = 1.0 - smoothstep(0.0, 0.8, s);  // Wide transition: 0-80% saturation
        
        // Apply smooth skin tone protection using sine curve - minimum at medium skin tone
        // Sine curve: 0.0 at edges (skinScore=0,1), maximum at center (skinScore=0.5)
        // This gives: 0.0 at edges (full vibrance), 1.0 at center (maximum protection)
        float skinProtection = sin(skinScore * 3.14159265359);  // Smooth sine curve, max protection at 0.5
        float protectedWeight = mix(satWeight, satWeight * 0.3, skinProtection);  // 0.3x effect at maximum protection
        
        // Simple linear vibrance formula (like darktable): 1.0 + strength * weight
        // This is inherently smooth and banding-free
        vibranceMult = 1.0 + vibranceStrength * protectedWeight;
    }
    
    // === 3. Combine dehaze and vibrance multipliers ===
    float combinedMult = dehazeMult * vibranceMult;
    s *= combinedMult;
    
    // === 4. Global saturation adjustment (HSV, RawTherapee style) ===
    if (hasSaturationEffect) {
        float satMult = 1.0 + saturationStrength;
        
        // Exposure fusion oversaturation fix
        if (hdrSigmoidalContrast > 0.0 && isNightMode == 0 && baselineExposure > 1.0) {
            // Calculate approximate saturation from RGB (before HSV conversion)
            // We need to reconstruct RGB from current HSV to get accurate saturation estimate
            vec3 tempRgb = hsv2rgb(hsv);
            float luma = dot(tempRgb, vec3(0.2126, 0.7152, 0.0722));
            float maxv = max(max(tempRgb.r, tempRgb.g), tempRgb.b);
            float approxSat = (maxv > 0.001) ? 1.0 - luma / maxv : 0.0;
            if (approxSat > 0.4) {
                float satExcess = (approxSat - 0.4) / 0.6;
                float baseReduction = satExcess * satExcess * 0.3;
                float compressionScale = clamp((hdrSigmoidalContrast - 1.0) / 5.0, 0.0, 1.0) * 0.5 + 0.5;
                satMult -= baseReduction * compressionScale;
            }
        }
        
        if (satMult > 1.0) {
            // Positive saturation: use cosine S-curve (C∞ smooth at both endpoints)
            // Smooth blending for saturation above 1.0 instead of hard clamp
            // Much wider transition zone to prevent banding
            float satBlend = smoothClamp(s, 0.0, 1.0, 0.001);  // 0.1% transition zone - minimal
            // Cosine S-curve: 0→0, 1→1, with smooth acceleration/deceleration
            float targetS = 0.5 - 0.5 * cos(satBlend * PI);
            float satAmount = smoothClamp(satMult - 1.0, 0.0, 1.0, 0.001);  // 0.1% transition zone - minimal
            s = mix(s, targetS, satAmount);
            s = smoothMax(s, 0.0, 0.001);  // Minimal transition (0.1%) - only affects near-zero
        } else {
            // Negative saturation: simple multiply
            s *= satMult;
            // Smooth rolloff to zero for negative results
            s = smoothMax(s, 0.0, 0.001);  // Minimal transition (0.1%) - only affects near-zero
        }
    }
    
    // === 5. Convert HSV → RGB (single conversion) ===
    hsv.y = s;
    rgb = hsv2rgb(hsv);
    
    // Smooth rolloff to zero instead of hard clamp to prevent banding
    // Use minimal transition (0.1%) to avoid crushing blacks
    return smoothMaxVec3(rgb, 0.0, 0.001);  // 0.1% transition zone - only affects near-zero values
}

/* =====================================================
 * COMMENTED OUT: Lab/Luv-based approach (has hue shifts when saturation != 0)
 * =====================================================
 *
vec3 applyColorAdjustmentsLab_Luv(vec3 rgb, float dehazeSatFactor, float vibranceStrength, float saturationStrength) {
    // Skip if no adjustments needed
    bool hasDehazeEffect = abs(dehazeSatFactor) > 0.001;
    bool hasVibranceEffect = abs(vibranceStrength) > 0.001;
    bool hasSaturationEffect = abs(saturationStrength) > 0.001;
    
    if (!hasDehazeEffect && !hasVibranceEffect && !hasSaturationEffect) {
        return rgb;
    }
    
    // === Single conversion to Luv → LChuv (all adjustments in LChuv for accuracy) ===
    vec3 luv = rgb_to_luv(rgb);
    vec3 lchuv = luv_to_lchuv(luv);
    
    // Extract components
    float L = lchuv.x;
    float C = lchuv.y;  // Chroma
    float H = lchuv.z;  // Hue (in radians)
    
    // Normalize chroma for calculations - use smooth clamp to prevent banding
    float normChroma = smoothClamp(C / 100.0, 0.0, 1.0, 0.001);  // 0.1% transition zone - minimal
    
    // === 1. Dehaze saturation adjustment ===
    // Applied uniformly (no special behavior needed)
    float dehazeMult = 1.0 + dehazeSatFactor;
    
    // === 2. Vibrance: affects less saturated colors MORE ===
    float vibranceMult = 1.0;
    if (hasVibranceEffect) {
        // Skin tone detection - EXTREMELY WIDE transitions to prevent banding
        // Use very wide smoothstep ranges to ensure gradual, smooth transitions
        // Hue: very wide range from -0.3 to 1.7 (centered around 0.2-1.4) for gradual falloff
        float skinHue = smoothstep(-0.3, 0.2, H) * smoothstep(1.7, 1.4, H);
        // Chroma: very wide range from -0.2 to 0.8 (centered around 0.05-0.6) for gradual falloff
        float skinChroma = smoothstep(0.8, 0.6, normChroma) * smoothstep(-0.2, 0.05, normChroma);
        float skinScore = skinHue * skinChroma;  // 0.0 to 1.0, very gradually varying
        
        // Vibrance affects less saturated colors MORE
        // Based on darktable implementation: simple linear formula for smooth, banding-free results
        // Calculate chroma weight: 0.0 for high chroma, 1.0 for low chroma
        // Use smoothstep with wide transition zones for smooth, banding-free results
        float chromaWeight = 1.0 - smoothstep(0.0, 0.8, normChroma);  // Wide transition: 0-80% chroma
        
        // Apply smooth skin tone protection using sine curve - minimum at medium skin tone
        // Sine curve: 0.0 at edges (skinScore=0,1), maximum at center (skinScore=0.5)
        // This gives: 0.0 at edges (full vibrance), 1.0 at center (maximum protection)
        float skinProtection = sin(skinScore * 3.14159265359);  // Smooth sine curve, max protection at 0.5
        float protectedWeight = mix(chromaWeight, chromaWeight * 0.3, skinProtection);  // 0.3x effect at maximum protection
        
        // Simple linear vibrance formula (like darktable): 1.0 + strength * weight
        // This is inherently smooth and banding-free
        vibranceMult = 1.0 + vibranceStrength * protectedWeight;
    }
    
    // === 3. Combine dehaze and vibrance multipliers ===
    float combinedMult = dehazeMult * vibranceMult;
    
    // === 4. Global saturation adjustment ===
    if (hasSaturationEffect) {
        float satMult = 1.0 + saturationStrength;
        
        // Exposure fusion oversaturation fix
        if (hdrSigmoidalContrast > 0.0 && isNightMode == 0 && baselineExposure > 1.0) {
            // Estimate saturation from current chroma
            // For LChuv, we can use normalized chroma as saturation estimate
            if (normChroma > 0.4) {
                float satExcess = (normChroma - 0.4) / 0.6;
                float baseReduction = satExcess * satExcess * 0.3;
                float compressionScale = clamp((hdrSigmoidalContrast - 1.0) / 5.0, 0.0, 1.0) * 0.5 + 0.5;
                satMult -= baseReduction * compressionScale;
            }
        }
        
        // Apply saturation multiplier to chroma
        combinedMult *= satMult;
    }
    
    // === 5. Apply combined multiplier to chroma ===
    lchuv.y *= combinedMult;
    
    // === 6. Gamut clip BEFORE converting back to RGB ===
    // This preserves hue while ensuring valid RGB values
    lchuv = gamut_clip_lchuv(lchuv);
    
    // === 7. Convert LChuv → Luv → RGB (single conversion) ===
    luv = lchuv_to_luv(lchuv);
    rgb = luv_to_rgb(luv);
    
    // Smooth rolloff to zero instead of hard clamp to prevent banding
    return smoothMaxVec3(rgb, 0.0, 0.001);  // 0.1% transition zone - only affects near-zero values
}
*/

// Apply HDR-specific shadow lowering curve
// This darkens shadows in HDR images to match reference processing
// Uses a polynomial curve that primarily affects shadow regions
vec3 applyHDRShadowAdjust(vec3 rgb, float adjust) {
    if (adjust <= 0.0) return rgb;
    
    // Apply a curve that lowers shadows more than midtones/highlights
    // Similar to the polynomial tonemap curve but specifically for shadow reduction
    vec3 result;
    for (int i = 0; i < 3; i++) {
        float x = rgb[i];
        // Polynomial curve: -0.025 * x^3 + 0.05 * x^2 - 0.15 * x
        // This primarily affects shadows (x < 0.3) while preserving highlights
        float shadowCurve = -0.025 * x * x * x + 0.05 * x * x - 0.15 * x;
        // Scale by adjustment amount and apply
        result[i] = x + shadowCurve * adjust;
    }
    return max(result, vec3(0.0));  // Only clamp negatives, preserve HDR values > 1.0
}

// Calculate sigmoidal contrast compensation strength from exposure value
// Both brightening and darkening affect contrast, but in different ways:
// - Brightening: compresses highlights → reduces contrast → needs contrast boost
// - Darkening: expands highlights relatively → increases contrast → needs contrast reduction
float calculateExposureContrastCompensation(float exposure) {
    if (abs(exposure - 1.0) < 0.001) {
        return 0.0;  // No compensation needed
    }
    
    if (exposure > 1.0) {
        // Brightening: curve compresses highlights, reducing contrast
        // Calculate effective "gamma" from exposure curve
        // The exposure curve y = (x * e) / (1 + x * (e - 1)) has similar contrast reduction
        // to gamma correction with gamma = 1/e (approximately)
        float effectiveGamma = 1.0 / exposure;
        // Compensation strength: stronger exposure → more contrast loss → more compensation
        // Typical range: exposure 1.0-4.0 → contrast 0.0-3.0
        float contrastStrength = (1.0 - effectiveGamma) * 4.0;
        return clamp(contrastStrength, 0.0, 3.0);
    } else {
        // Darkening: power curve compresses shadows but relatively expands highlights
        // This creates non-uniform contrast (low in shadows, high in highlights)
        // We need to reduce contrast to compensate for the highlight expansion
        // The lower the exposure, the more expansion in highlights
        // Return negative value to indicate contrast reduction needed
        float contrastReduction = (1.0 - exposure) * 2.0;
        return -clamp(contrastReduction, 0.0, 2.0);  // Negative = reduce contrast
    }
}

// Apply exposure using a unified curve from (0,0) to (1,1) that works on the full range
// The curve compresses highlights when brightening and shadows when darkening
// Works seamlessly for both SDR [0,1] and HDR (> 1.0) values without discontinuities
// Note: Contrast compensation is applied separately in main() after this function
vec3 applyExposureCurve(vec3 rgb, float exposure) {
    if (abs(exposure - 1.0) < 0.001) {
        return rgb;  // No change
    }
    
    vec3 result;
    for (int i = 0; i < 3; i++) {
        float x = max(rgb[i], 0.0);  // Only clamp negatives
        
        if (exposure > 1.0) {
            // Brightening: unified curve that compresses highlights across full range
            // Single formula that works for all x >= 0: y = (x * exposure) / (1.0 + x * (exposure - 1.0))
            // This naturally:
            // - Maps [0,1] to [0,1] with highlight compression (similar to 1.0 - (1.0 - x)^exposure)
            // - Compresses highlights for x > 1.0 smoothly
            // - Is continuous and differentiable everywhere
            result[i] = (x * exposure) / (1.0 + x * (exposure - 1.0));
        } else {
            // Darkening: use unified formula that properly darkens values
            // Formula: y = (x * exposure) / (1.0 + x * (1.0 - exposure))
            // This is the inverse form of the brightening curve
            // For exposure = 0.5: y = (x * 0.5) / (1.0 + x * 0.5)
            // - Maps [0,1] to [0,1] with shadow compression
            // - Properly darkens values (makes them smaller)
            // - Works for all x >= 0 including HDR highlights
            result[i] = (x * exposure) / (1.0 + x * (1.0 - exposure));
        }
    }
    
    return result;
}

// Main Lightroom-style tone adjustment function
vec3 applyToneAdjustments(vec3 rgb) {
    // Exposure is now applied before HDR compression in main() to preserve full dynamic range
    // 1. Highlight recovery/boost
    rgb = adjustHighlights(rgb, toneHighlights);
    
    // 3. Shadow lift/crush
    rgb = adjustShadows(rgb, toneShadows);
    
    // 4. Whites adjustment
    rgb = adjustWhites(rgb, toneWhites);
    
    // 5. Blacks adjustment
    rgb = adjustBlacks(rgb, toneBlacks);
    
    // 6. Contrast (S-curve)
    rgb = adjustContrast(rgb, toneContrast);
    
    // 7. Dehaze (contrast part only - saturation handled in combined Lab pass)
    float dehazeSatFactor;
    rgb = applyDehazeContrast(rgb, toneDehaze, dehazeSatFactor);
    
    // 8-9. Combined color adjustments in Lab colorspace (single conversion)
    // This combines: dehaze saturation, vibrance, and global saturation
    // Much more efficient than separate functions (2 conversions vs 4)
    rgb = applyColorAdjustmentsLab(rgb, dehazeSatFactor, toneVibrance, toneSaturation);
    
    // Smooth rolloff to zero instead of hard clamp to prevent banding
    // Allow values > 1.0 for HDR, but smoothly blend negatives to zero
    // Final clamping happens at the color transform stage
    return smoothMaxVec3(rgb, 0.0, 0.001);  // 0.1% transition zone - only affects near-zero values for smooth rolloff
}

// Estimate local variance/contrast from blur scale differences
// High variance = detailed area, Low variance = flat area (sky, smooth surfaces)
float estimateLocalVariance(float pixel, float weakBlur, float mediumBlur, float strongBlur) {
    // Local contrast at different scales
    float fineContrast = abs(pixel - weakBlur);
    float medContrast = abs(weakBlur - mediumBlur);
    float coarseContrast = abs(mediumBlur - strongBlur);
    
    // Combine contrasts - weight fine contrast more heavily
    float variance = fineContrast * 0.5 + medContrast * 0.3 + coarseContrast * 0.2;
    
    // Normalize by local brightness to get relative contrast
    float localMean = max(mediumBlur, 0.001);
    return variance / localMean;
}

// Detect strong edges that would cause haloing
// Returns 1.0 for safe areas, gradually decreasing to ~0.3 for strong edges
// Uses smooth transitions to avoid visible boundaries
// More sensitive to medium-scale edges to prevent haloing
float detectEdges(float pixel, float weakBlur, float mediumBlur, float strongBlur) {
    // Strong edge = large difference between blur scales
    // This indicates a sharp transition that would cause haloing
    // Pay special attention to medium-scale edges (weak-medium and medium-strong)
    float edgeStrength1 = abs(weakBlur - mediumBlur) / max(mediumBlur, 0.001);
    float edgeStrength2 = abs(mediumBlur - strongBlur) / max(strongBlur, 0.001);
    // Weight medium-scale edges more heavily (they cause the most visible haloing)
    float maxEdgeStrength = max(edgeStrength1 * 1.2, edgeStrength2 * 1.2);
    
    // Also check for very large pixel-to-blur differences (sharp edges)
    // Medium blur is most relevant for haloing detection
    float pixelEdge = abs(pixel - mediumBlur) / max(mediumBlur, 0.001);
    maxEdgeStrength = max(maxEdgeStrength, pixelEdge);
    
    // Use a very smooth, gradual transition
    // More aggressive reduction at lower thresholds to catch medium-scale haloing
    // Smooth transition from 0.03 to 0.15 for earlier reduction
    float edgeReduction = smoothstep(0.03, 0.15, maxEdgeStrength);
    return mix(0.25, 1.0, 1.0 - edgeReduction);  // Lower minimum (0.25) for stronger edge protection
}

// CLAHE-like clip limiting: prevents over-enhancement in high-contrast areas
// Returns clip limit factor (0-1) where lower values = more limiting
// Based on convert_uraw script's CLAHE approach
float computeClipLimit(float variance, float brightness, float edgeFactor) {
    // CLAHE clip limiting: reduce enhancement in areas with high local contrast
    // High variance = high contrast = potential for halos
    // Use a smooth curve that strongly limits high-variance areas
    float varianceLimit = 1.0 - smoothstep(0.08, 0.25, variance) * 0.8;  // Strong limiting at high variance
    
    // Edge factor: reduce at detected edges
    float edgeLimit = mix(edgeFactor, 1.0, 0.4);  // Minimum 0.4 at edges
    
    // Combine: use minimum (most restrictive)
    float clipLimit = min(varianceLimit, edgeLimit);
    
    // Gentle highlight protection
    float highlightProtection = 1.0 - smoothstep(0.9, 1.0, brightness) * 0.15;
    
    return clipLimit * highlightProtection;
}

// Local Contrast Enhancement (LCE) - multiplicative contrast based on ratio to local mean
// Enhances contrast in both directions: brightens areas brighter than blur, darkens areas darker than blur
// Uses multiplicative enhancement which preserves tonality better than additive methods
float applyLCE(float original, float blurred, float clipLimit, float strength) {
    // Avoid division by zero
    if (blurred < 0.0001) {
        return original;
    }
    
    // Compute local contrast ratio (how much brighter/darker than blur)
    float contrastRatio = original / blurred;
    
    // Apply clip limiting: reduce enhancement in high-contrast areas
    // contrastRatio of 1.0 = no change, >1.0 = brighter, <1.0 = darker
    float limitedRatio = 1.0 + (contrastRatio - 1.0) * clipLimit;
    
    // Apply strength: blend between no enhancement (ratio=1.0) and full enhancement
    float enhancedRatio = mix(1.0, limitedRatio, strength);
    
    // Apply enhanced ratio to original
    float enhanced = original * enhancedRatio;
    
    return max(enhanced, 0.0);
}

// GPU-friendly CLAHE approximation using neighbor sampling for proper variance estimation
// Approximates histogram equalization by:
// 1. Computing local mean (from blur) and local variance (from neighbor sampling)
// 2. Computing z-score to estimate position in local distribution
// 3. Applying Gaussian CDF approximation to remap values (spreads histogram)
// 4. Contrast limiting prevents over-enhancement
float applyGpuCLAHE(float original, float blurred, sampler2D blurTex, ivec2 pos, float clipLimit, float strength) {
    // Avoid division by zero
    if (blurred < 0.0001) {
        return original;
    }
    
    // Sample neighbors to compute proper local variance
    // Use a 5x5 sparse sampling pattern for efficiency (9 samples instead of 25)
    float sumSq = 0.0;
    float sum = 0.0;
    const int offsets[9] = int[9](-2, -1, 0, 1, 2, -2, -1, 0, 1); // Reuse for x and y
    
    // Sample in a cross pattern + corners for good coverage
    ivec2 sampleOffsets[9] = ivec2[9](
        ivec2(-2, 0), ivec2(-1, 0), ivec2(0, 0), ivec2(1, 0), ivec2(2, 0),  // Horizontal
        ivec2(0, -2), ivec2(0, -1), ivec2(0, 1), ivec2(0, 2)                 // Vertical (skip center)
    );
    
    for (int i = 0; i < 9; i++) {
        float texVal = texelFetch(blurTex, pos + sampleOffsets[i], 0).x;
        sum += texVal;
        sumSq += texVal * texVal;
    }
    
    // Compute local variance: Var(X) = E[X²] - E[X]²
    float localMean = sum / 9.0;
    float localVariance = max(sumSq / 9.0 - localMean * localMean, 0.0001);
    float localStd = sqrt(localVariance);
    
    // Compute z-score: how many standard deviations from local mean
    float z = (original - blurred) / max(localStd, 0.001);
    z = clamp(z, -3.0, 3.0);  // Limit to 3-sigma range
    
    // Apply Gaussian CDF approximation to get "equalized" percentile position [0, 1]
    // CDF(z) ≈ 0.5 * (1 + tanh(z * 0.8))  (approximates erf(z/√2))
    float cdf = 0.5 + 0.5 * tanh(z * 0.7978845608);  // 0.7978... = sqrt(2/π) for better erf approx
    
    // Map CDF output back to pixel value range
    // The equalized value spreads pixels evenly: dark pixels get darker, bright get brighter
    // Scale by local std to match local dynamic range, centered on local mean
    float equalized = blurred + (cdf - 0.5) * localStd * 4.0;  // 4.0 = ~2 std devs each direction
    
    // Apply contrast limiting: blend between original and equalized
    float limited = mix(original, equalized, clipLimit);
    
    // Blend with original based on strength
    float result = mix(original, limited, strength);
    
    return max(result, 0.0);
}

// Simplified GPU CLAHE that works with pre-computed blur (no texture sampling)
// Uses the difference between blur scales to estimate local variance
// blurFine and blurCoarse should be blurs at different scales for the same pixel
float applyGpuCLAHESimple(float original, float blurFine, float blurCoarse, float clipLimit, float strength) {
    if (blurFine < 0.0001) {
        return original;
    }
    
    // Estimate local variance from the difference between blur scales
    // Large difference = high local variance = busy texture
    // Small difference = low local variance = smooth region
    float scaleDiff = abs(blurFine - blurCoarse);
    float localStd = max(scaleDiff * 2.0, blurFine * 0.05);  // Minimum 5% of mean
    
    // Compute z-score relative to fine blur (local mean)
    float z = (original - blurFine) / max(localStd, 0.001);
    z = clamp(z, -3.0, 3.0);
    
    // Gaussian CDF approximation
    float cdf = 0.5 + 0.5 * tanh(z * 0.7978845608);
    
    // Map back to value range
    float equalized = blurFine + (cdf - 0.5) * localStd * 4.0;
    
    // Apply contrast limiting and strength
    float limited = mix(original, equalized, clipLimit);
    return max(mix(original, limited, strength), 0.0);
}

// GPU CLAHE approximation using only a single blur (local mean)
// Estimates variance heuristically from the local mean value
// Applies CDF-like remapping to spread histogram
float applyGpuCLAHESingleBlur(float original, float blurred, float clipLimit, float strength) {
    if (blurred < 0.0001) {
        return original;
    }
    
    // Heuristic variance estimation based on local mean
    // In natural images, local std dev is roughly proportional to local mean (Weber's law)
    // Use coefficient of variation ~0.2-0.4 for typical images
    float estimatedStd = blurred * 0.25;  // Assume CV of 0.25
    
    // Also consider the actual deviation as a variance indicator
    // High deviation from blur suggests high local contrast
    float actualDev = abs(original - blurred);
    
    // Blend between Weber-based estimate and actual deviation
    // This adapts to both smooth and textured regions
    float localStd = max(mix(estimatedStd, actualDev, 0.5), blurred * 0.05);
    
    // Compute z-score: how many standard deviations from local mean
    float z = (original - blurred) / localStd;
    z = clamp(z, -3.0, 3.0);  // Limit to 3-sigma range
    
    // Apply Gaussian CDF approximation: spreads histogram evenly
    // CDF(z) ≈ 0.5 * (1 + tanh(z * sqrt(2/π)))
    float cdf = 0.5 + 0.5 * tanh(z * 0.7978845608);
    
    // Map CDF [0,1] back to value range centered on local mean
    // The multiplier controls the output dynamic range
    float equalized = blurred + (cdf - 0.5) * localStd * 3.0;
    
    // Apply contrast limiting: blend between original and equalized
    float limited = mix(original, equalized, clipLimit);
    
    // Blend with original based on strength
    return max(mix(original, limited, strength), 0.0);
}

// Apply contrast enhancement at a single scale, using the global lceMethod
// lceMethod: 0 = lce (multiplicative), 1 = clahe (full, separate stage), 2 = gpu clahe (CDF-based)
float applyContrastAtScale(float original, float blurred, float clipLimit, float strength) {
    if (lceMethod == 2) {
        // GPU CLAHE: CDF-based histogram spreading approximation
        return applyGpuCLAHESingleBlur(original, blurred, clipLimit, strength);
    } else {
        // Standard LCE (lceMethod == 0): multiplicative contrast
        return applyLCE(original, blurred, clipLimit, strength);
    }
}

// Apply contrast enhancement with access to multiple blur scales (better GPU CLAHE)
// Uses difference between blur scales for more accurate variance estimation
float applyContrastAtScaleWithBlurs(float original, float blurFine, float blurCoarse, float clipLimit, float strength) {
    if (lceMethod == 2) {
        // GPU CLAHE with better variance estimation from blur scale difference
        return applyGpuCLAHESimple(original, blurFine, blurCoarse, clipLimit, strength);
    } else {
        // Standard LCE (lceMethod == 0)
        return applyLCE(original, blurFine, clipLimit, strength);
    }
}

// Subtle contrast enhancement - applies a gentle S-curve to luminance
// Works on already-compressed values in [0,1] range
// Only applies to midtones and highlights - shadows are protected from crushing
float softContrast(float luma, float strength) {
    // Centered S-curve: enhances contrast around midpoint
    float mid = 0.5;
    float x = luma - mid;
    // Cubic S-curve for natural look (reduced multiplier for gentler effect)
    float contrastEffect = x * x * x * strength * 0.75;
    
    // Only apply contrast enhancement above midpoint (highlights)
    // Below midpoint, the S-curve would darken shadows - skip it entirely
    // This prevents any black crushing from the S-curve
    if (luma < mid) {
        // In shadows: no S-curve darkening, but allow slight lift
        // Clamp contrastEffect to be non-negative (only brighten, never darken)
        contrastEffect = max(contrastEffect, 0.0);
    }
    
    float enhanced = luma + contrastEffect;
    return max(enhanced, 0.0);  // Only clamp negatives, preserve HDR values > 1.0
}

vec3 processPatch(ivec2 xyPos) {
    // Decode HDR luminance: Y = z * w
    vec3 xyY = fetchHighResXYY(xyPos);
    
    // NOTE: Baseline exposure is now applied to RGB in main(), not here
    // This keeps the xyY processing stages working with the original sensor data
    
    float originalY = xyY.z;

    // NOTE: LCE has been moved to after HDR compression in main()
    // It is now applied to RGB values via applyLCEToRGB() function

    // =====================================================
    // TEXTURE & CLARITY (Lightroom-style, applied to luminance)
    // These work independently of the LCE mode toggle
    // =====================================================
    
    // Texture: enhances fine detail (high frequency)
    if (abs(toneTexture) > 0.001) {
        float zWeakBlur = texelFetch(weakBlur, xyPos, 0).x;
        // Fine detail = difference between original and weak blur
        float fineDetail = originalY - zWeakBlur;
        // Apply texture enhancement (positive = sharpen, negative = soften)
        float textureBoost = fineDetail * toneTexture * 0.5;
        xyY.z += textureBoost;
    }
    
    // Clarity: enhances mid-tone contrast (medium frequency)
    // Like Lightroom clarity - unsharp mask at medium frequencies, emphasizing midtones
    // FIXED: Now uses correct unsharp mask formula (original - blur) instead of (blur1 - blur2)
    if (abs(toneClarity) > 0.001) {
        float zMediumBlur = texelFetch(mediumBlur, xyPos, 0).x;
        
        // CRITICAL FIX: Mid-frequency detail = original - medium blur (unsharp mask)
        // This is the standard clarity formula, same pattern as texture (original - weak blur)
        // The previous implementation used (mediumBlur - strongBlur) which is a band-pass filter
        // that produces a very small signal. This high-pass filter produces the correct detail signal.
        float midDetail = originalY - zMediumBlur;
        
        // Clarity primarily affects midtones - use medium blur for HDR-safe midtone detection
        // (originalY can be > 1.0 for HDR, which would break the simple midtone calculation)
        float localMean = max(zMediumBlur, 0.001);
        float normalizedLocal = clamp(localMean, 0.0, 1.0);
        float midtoneMask = 1.0 - abs(normalizedLocal - 0.5) * 2.0;
        midtoneMask = max(midtoneMask, 0.3); // Still affects shadows/highlights somewhat
        
        // Apply clarity as unsharp mask: output = input + detail * strength
        // Use simple strength multiplier - midDetail is already in correct scale (typically 0.05-0.20)
        // toneClarity is -1.0 to +1.0 from slider (-100 to +100)
        // Strength of 1.5 gives visible effect: for midDetail=0.1, boost = 0.1 * 1.0 * 0.75 * 1.5 = 0.1125
        //   Applied to Y=0.5: 0.5 + 0.1125 = 0.6125 (22.5% change - clearly visible)
        float clarityBoost = midDetail * toneClarity * midtoneMask * 1.5;
        xyY.z += clarityBoost;
    }

    // =====================================================
    // SHARPENING (Unsharp Mask) - Applied at end of xyY processing
    // Sharpening is applied at the end of processPatch, after all other processing
    // This ensures it operates on the final processed values
    // =====================================================
    if (sharpenFactor > 0.001) {
        float zWeakBlur = texelFetch(weakBlur, xyPos, 0).x;
        // Compare current processed value with its blurred version
        float detail = xyY.z - zWeakBlur;
        
        // Standard unsharp mask: output = input + factor * (input - blur)
        float sharpenBoost = detail * sharpenFactor;
        
        // Protection: Prevent excessive darkening that causes black crushing
        // Allow darkening but limit it based on current value to preserve shadow detail
        float maxDarkening = xyY.z * 0.4;  // Allow up to 40% darkening, scaled by current value
        sharpenBoost = max(sharpenBoost, -maxDarkening);
        
        xyY.z += sharpenBoost;
    }

    // Smooth clamp chromaticity to valid range [0, 1] (prevents color banding)
    // But preserve HDR luminance values > 1.0 for highlight detail
    xyY.x = smoothClamp(xyY.x, 0.0, 1.0, 0.001);
    xyY.y = smoothClamp(xyY.y, 0.0, 1.0, 0.001);
    xyY.z = max(xyY.z, 0.0);  // Only clamp negatives, preserve HDR values > 1.0

    return xyY;
}

// =====================================================
// Apply LCE to RGB values (after HDR compression)
// Extracts luminance from RGB, applies LCE using blur textures,
// then scales RGB to match the enhanced luminance
// =====================================================
highp vec3 applyLCEToRGB(highp vec3 rgb, ivec2 xyPos) {
    if (!lce) {
        return rgb;
    }
    
    // Extract luminance from RGB (linear space)
    float originalLuma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    
    // Fetch blur textures (these are in xyY space, but represent the same spatial information)
    float zWeakBlur = texelFetch(weakBlur, xyPos, 0).x;
    float zMediumBlur = texelFetch(mediumBlur, xyPos, 0).x;
    float zStrongBlur = texelFetch(strongBlur, xyPos, 0).x;
    
    // Detect edges to prevent haloing
    float edgeFactor = detectEdges(originalLuma, zWeakBlur, zMediumBlur, zStrongBlur);
    
    // =====================================================
    // CLAHE-LIKE CLIP LIMITING
    // Prevents over-enhancement in high-contrast areas (prevents halos)
    // Based on convert_uraw script's CLAHE approach
    // =====================================================
    float clipLimit = 1.0;  // Default: no limiting
    if (varianceLimiting) {
        float localVariance = estimateLocalVariance(originalLuma, zWeakBlur, zMediumBlur, zStrongBlur);
        clipLimit = computeClipLimit(localVariance, originalLuma, edgeFactor);
    } else {
        // Even without variance limiting, apply edge detection to prevent haloing
        clipLimit = mix(edgeFactor, 1.0, 0.4);  // Minimum 0.4 at edges
    }
    
    float enhancedLuma = originalLuma;
    
    if (lceMultiScale) {
        // =====================================================
        // 6-SCALE LCE (Boosted and MAT modes)
        // =====================================================
        
        float zXfineBlur = texelFetch(xfineBlur, xyPos, 0).x;
        float zFineBlur = texelFetch(fineBlur, xyPos, 0).x;
        float zXstrongBlur = texelFetch(xstrongBlur, xyPos, 0).x;
        
        // Enhanced variance estimation using all 6 scales (if variance limiting enabled)
        float multiScaleEdgeFactor = detectEdges(originalLuma, zWeakBlur, zMediumBlur, zStrongBlur);
        if (varianceLimiting) {
            float localVariance = estimateLocalVariance(originalLuma, zWeakBlur, zMediumBlur, zStrongBlur);
            float fineVariance = abs(originalLuma - zXfineBlur) + abs(zXfineBlur - zFineBlur);
            float totalVariance = localVariance + fineVariance * 0.3;
            clipLimit = computeClipLimit(totalVariance, originalLuma, multiScaleEdgeFactor);
        } else {
            clipLimit = mix(multiScaleEdgeFactor, 1.0, 0.4);
        }
        
        // Determine if we're in shadows or highlights
        float lumaMask = smoothstep(0.15, 0.6, originalLuma);  // 0=shadow, 1=highlight
        
        // =====================================================
        // HIGHLIGHT PROCESSING (fine detail - small radii)
        // =====================================================
        
        float highlightLCE = originalLuma;
        float highlightEnhancement = 0.0;
        
        if (zMediumBlur > 0.0001) {
            float highlightClipLimit = mix(clipLimit, 1.0, 1.0 - lceLimits.y);
            float enhanced = applyContrastAtScale(originalLuma, zMediumBlur, highlightClipLimit, lceStrengths.y);
            highlightEnhancement += enhanced - originalLuma;
        }
        
        if (zWeakBlur > 0.0001) {
            float highlightClipLimit = mix(clipLimit, 1.0, 1.0 - lceLimits.x);
            float enhanced = applyContrastAtScale(originalLuma, zWeakBlur, highlightClipLimit, lceStrengths.x);
            highlightEnhancement += enhanced - originalLuma;
        }
        
        if (zFineBlur > 0.0001) {
            float highlightClipLimit = mix(clipLimit, 1.0, 1.0 - lceLimitsMulti.y);
            float enhanced = applyContrastAtScale(originalLuma, zFineBlur, highlightClipLimit, lceStrengthsMulti.y);
            highlightEnhancement += enhanced - originalLuma;
        }
        
        if (zXfineBlur > 0.0001) {
            float highlightClipLimit = mix(clipLimit, 1.0, 1.0 - lceLimitsMulti.x);
            float enhanced = applyContrastAtScale(originalLuma, zXfineBlur, highlightClipLimit, lceStrengthsMulti.x);
            highlightEnhancement += enhanced - originalLuma;
        }
        
        highlightLCE = originalLuma + highlightEnhancement;
        highlightLCE = max(highlightLCE, 0.0);
        
        // =====================================================
        // SHADOW PROCESSING (all scales, fine stronger than coarse)
        // =====================================================
        
        float shadowDepth = smoothstep(0.0, 0.2, originalLuma);
        float shadowStrength = mix(0.4, 1.0, shadowDepth);
        
        float shadowLCE = originalLuma;
        float shadowEnhancement = 0.0;
        
        if (zXstrongBlur > 0.0001) {
            float shadowClipLimit = clipLimit * shadowStrength * lceLimitsMulti.z;
            float enhanced = applyContrastAtScale(originalLuma, zXstrongBlur, shadowClipLimit, lceStrengthsMulti.z);
            shadowEnhancement += enhanced - originalLuma;
        }
        
        if (zStrongBlur > 0.0001) {
            float shadowClipLimit = clipLimit * shadowStrength * lceLimits.z;
            float enhanced = applyContrastAtScale(originalLuma, zStrongBlur, shadowClipLimit, lceStrengths.z);
            shadowEnhancement += enhanced - originalLuma;
        }
        
        if (zMediumBlur > 0.0001) {
            float shadowClipLimit = clipLimit * shadowStrength * lceLimits.y;
            float enhanced = applyContrastAtScale(originalLuma, zMediumBlur, shadowClipLimit, lceStrengths.y);
            shadowEnhancement += enhanced - originalLuma;
        }
        
        if (zWeakBlur > 0.0001) {
            float shadowClipLimit = clipLimit * shadowStrength * lceLimits.x;
            float enhanced = applyContrastAtScale(originalLuma, zWeakBlur, shadowClipLimit, lceStrengths.x);
            shadowEnhancement += enhanced - originalLuma;
        }
        
        shadowLCE = originalLuma + shadowEnhancement;
        shadowLCE = max(shadowLCE, 0.0);
        
        // Blend shadow and highlight processing based on luminance
        enhancedLuma = mix(shadowLCE, highlightLCE, lumaMask);
        enhancedLuma = clamp(enhancedLuma, 0.0, originalLuma * 1.6);
        
    } else {
        // =====================================================
        // STANDARD 3-SCALE LCE (Natural mode)
        // =====================================================
        
        float lceEnhancement = 0.0;
        
        if (zStrongBlur > 0.0001f) {
            float largeClipLimit = mix(clipLimit, 1.0, 1.0 - lceLimits.z);
            float enhanced = applyContrastAtScale(originalLuma, zStrongBlur, largeClipLimit, lceStrengths.z);
            lceEnhancement += enhanced - originalLuma;
        }
        
        if (zMediumBlur > 0.0001f) {
            float mediumClipLimit = mix(clipLimit, 1.0, 1.0 - lceLimits.y);
            float enhanced = applyContrastAtScale(originalLuma, zMediumBlur, mediumClipLimit, lceStrengths.y);
            lceEnhancement += enhanced - originalLuma;
        }
        
        if (zWeakBlur > 0.0001f) {
            float smallClipLimit = mix(clipLimit, 1.0, 1.0 - lceLimits.x);
            float enhanced = applyContrastAtScale(originalLuma, zWeakBlur, smallClipLimit, lceStrengths.x);
            lceEnhancement += enhanced - originalLuma;
        }
        
        enhancedLuma = originalLuma + lceEnhancement;
        enhancedLuma = max(enhancedLuma, 0.0);
    }
    
    // Apply subtle S-curve contrast enhancement for multi-scale mode
    if (lce && lceMultiScale) {
        enhancedLuma = softContrast(enhancedLuma, 0.15);
    }
    
    // Scale RGB by the ratio of enhanced to original luminance to preserve color
    float lumaRatio = 1.0;
    if (originalLuma > 0.0001) {
        lumaRatio = enhancedLuma / originalLuma;
    }
    
    // Apply the luminance adjustment while preserving color ratios
    highp vec3 result = rgb * lumaRatio;
    
    // Clamp to prevent negatives
    result = max(result, vec3(0.0));
    
    return result;
}

// =====================================================
// DNG Profile Tone Curve sampling
// Samples the camera manufacturer's intended tone curve
// =====================================================
float sampleProfileToneCurve(float x) {
    // The tone curve LUT is stored as a 1D texture
    // Profile tone curves are designed for [0, 1] SDR range
    // For HDR values > 1.0, preserve them unchanged (don't apply curve)
    if (x > 1.0) {
        return x;  // Preserve HDR values unchanged
    }
    // For [0, 1] values, sample the LUT with linear interpolation
    // Texture coordinates must be in [0, 1] for proper sampling
    return texture(profileToneCurve, vec2(x, 0.5)).x;
}

// ============================================================================
// REFERENCE PREVIEW TONE MATCHING
// ============================================================================
// NOTE: Histogram matching is now done in xyY space by HistogramMatch stage
// before ToneMap, so this function is no longer needed.
// The intermediate xyY data received by ToneMap already has histogram matching applied.

highp vec3 tonemap(highp vec3 rgb) {
    // HDR compression was already applied in main()
    // Here we apply the profile tone curve and polynomial contrast curve
    
    // If we have a profile tone curve, apply it
    if (hasProfileToneCurve) {
        rgb = vec3(
            sampleProfileToneCurve(rgb.r),
            sampleProfileToneCurve(rgb.g),
            sampleProfileToneCurve(rgb.b)
        );
    }
    
    // HDR PROTECTION: The polynomial curve is designed for [0,1] input
    // For HDR values (max > 1.0), skip the polynomial to prevent artifacts
    float maxChannel = max(max(rgb.r, rgb.g), rgb.b);
    if (maxChannel > 1.0) {
        // Skip polynomial curve for HDR values - just return as-is
        return rgb;
    }
    
    // Apply the polynomial tonemapping curve for contrast
    highp vec3 sorted = rgb;

    highp float tmp;
    int permutation = 0;

    // Sort the RGB channels by value
    if (sorted.z < sorted.y) {
        tmp = sorted.z;
        sorted.z = sorted.y;
        sorted.y = tmp;
        permutation |= 1;
    }
    if (sorted.y < sorted.x) {
        tmp = sorted.y;
        sorted.y = sorted.x;
        sorted.x = tmp;
        permutation |= 2;
    }
    if (sorted.z < sorted.y) {
        tmp = sorted.z;
        sorted.z = sorted.y;
        sorted.y = tmp;
        permutation |= 4;
    }

    highp vec2 minmax;
    minmax.x = sorted.x;
    minmax.y = sorted.z;

    // Apply tonemapping curve to min, max RGB channel values
    // Use manual multiplication instead of pow() for better precision
    highp vec2 minmax2 = minmax * minmax;  // x^2
    highp vec2 minmax3 = minmax2 * minmax; // x^3
    minmax = minmax3 * toneMapCoeffs.x +
        minmax2 * toneMapCoeffs.y +
        minmax * toneMapCoeffs.z +
        toneMapCoeffs.w;

    // Rescale middle value
    highp float newMid;
    if (sorted.z == sorted.x) {
        newMid = minmax.y;
    } else {
        highp float yprog = (sorted.y - sorted.x) / (sorted.z - sorted.x);
        newMid = minmax.x + (minmax.y - minmax.x) * yprog;
    }

    highp vec3 finalRGB;
    switch (permutation) {
        case 0: // b >= g >= r
            finalRGB.r = minmax.x;
            finalRGB.g = newMid;
            finalRGB.b = minmax.y;
            break;
        case 1: // g >= b >= r
            finalRGB.r = minmax.x;
            finalRGB.b = newMid;
            finalRGB.g = minmax.y;
            break;
        case 2: // b >= r >= g
            finalRGB.g = minmax.x;
            finalRGB.r = newMid;
            finalRGB.b = minmax.y;
            break;
        case 3: // g >= r >= b
            finalRGB.b = minmax.x;
            finalRGB.r = newMid;
            finalRGB.g = minmax.y;
            break;
        case 6: // r >= b >= g
            finalRGB.g = minmax.x;
            finalRGB.b = newMid;
            finalRGB.r = minmax.y;
            break;
        case 7: // r >= g >= b
            finalRGB.b = minmax.x;
            finalRGB.g = newMid;
            finalRGB.r = minmax.y;
            break;
    }
    // Clamp negatives to prevent artifacts (polynomial curve can produce negatives for values > 1.0)
    return max(finalRGB, vec3(0.0));
}

// =====================================================
// DNG Profile HueSatMap sampling
// Samples the camera manufacturer's per-hue color adjustments
// The 3D map is flattened into a 2D texture for compatibility
// =====================================================

// Sample the HueSatMap at given HSV coordinates
// Returns [deltaH, deltaS, deltaV] adjustments
vec3 sampleHueSatMap(float hue, float sat, float val) {
    // Map HSV to texture coordinates
    // Hue: 0-1 maps to 0 to hueDivs-1
    // Sat: 0-1 maps to 0 to satDivs-1
    // Val: 0-1 maps to 0 to valDivs-1
    
    float hueDivs = float(hueSatMapDims.x);
    float satDivs = float(hueSatMapDims.y);
    float valDivs = float(hueSatMapDims.z);
    
    // Compute indices with wrapping for hue (circular)
    float hueIdx = hue * hueDivs;
    float satIdx = sat * (satDivs - 1.0);  // Sat/Val don't wrap
    float valIdx = val * (valDivs - 1.0);
    
    // For trilinear interpolation, we need to sample 8 corners
    // But for efficiency, we'll do bilinear on two V slices and lerp
    
    int h0 = int(floor(hueIdx)) % int(hueDivs);
    int h1 = (h0 + 1) % int(hueDivs);
    float hFrac = fract(hueIdx);
    
    int s0 = int(floor(satIdx));
    int s1 = min(s0 + 1, int(satDivs) - 1);
    float sFrac = fract(satIdx);
    
    int v0 = int(floor(valIdx));
    int v1 = min(v0 + 1, int(valDivs) - 1);
    float vFrac = fract(valIdx);
    
    // Texture width = hueDivs * valDivs
    float texWidth = hueDivs * valDivs;
    
    // Sample 8 corners for trilinear interpolation
    // Texture X = hue * valDivs + val, Y = sat
    vec3 c000 = texture(profileHueSatMap, vec2((float(h0) * valDivs + float(v0) + 0.5) / texWidth, (float(s0) + 0.5) / satDivs)).rgb;
    vec3 c001 = texture(profileHueSatMap, vec2((float(h0) * valDivs + float(v1) + 0.5) / texWidth, (float(s0) + 0.5) / satDivs)).rgb;
    vec3 c010 = texture(profileHueSatMap, vec2((float(h0) * valDivs + float(v0) + 0.5) / texWidth, (float(s1) + 0.5) / satDivs)).rgb;
    vec3 c011 = texture(profileHueSatMap, vec2((float(h0) * valDivs + float(v1) + 0.5) / texWidth, (float(s1) + 0.5) / satDivs)).rgb;
    vec3 c100 = texture(profileHueSatMap, vec2((float(h1) * valDivs + float(v0) + 0.5) / texWidth, (float(s0) + 0.5) / satDivs)).rgb;
    vec3 c101 = texture(profileHueSatMap, vec2((float(h1) * valDivs + float(v1) + 0.5) / texWidth, (float(s0) + 0.5) / satDivs)).rgb;
    vec3 c110 = texture(profileHueSatMap, vec2((float(h1) * valDivs + float(v0) + 0.5) / texWidth, (float(s1) + 0.5) / satDivs)).rgb;
    vec3 c111 = texture(profileHueSatMap, vec2((float(h1) * valDivs + float(v1) + 0.5) / texWidth, (float(s1) + 0.5) / satDivs)).rgb;
    
    // Trilinear interpolation
    vec3 c00 = mix(c000, c001, vFrac);
    vec3 c01 = mix(c010, c011, vFrac);
    vec3 c10 = mix(c100, c101, vFrac);
    vec3 c11 = mix(c110, c111, vFrac);
    
    vec3 c0 = mix(c00, c01, sFrac);
    vec3 c1 = mix(c10, c11, sFrac);
    
    return mix(c0, c1, hFrac);
}

// Apply the HueSatMap adjustments to an RGB color
vec3 applyHueSatMap(vec3 rgb) {
    // This is called AFTER HDR compression, so values should be in [0, 1] range for SDR output.
    // For HDR output, compression is skipped to preserve dynamic range, so values may still be > 1.0.
    
    // Convert to HSV
    vec3 hsv = rgb2hsv(rgb);
    
    // HDR-aware: For HDR values > 1.0 (when compression was skipped), normalize value for lookup
    // but preserve the HDR scale. For SDR values [0, 1] (after compression), apply normally.
    float originalValue = hsv.z;
    float lookupValue = min(hsv.z, 1.0);  // Clamp value to [0, 1] for lookup
    bool isHDR = originalValue > 1.0;
    
    // Sample the HueSatMap using normalized value
    vec3 delta = sampleHueSatMap(hsv.x, hsv.y, lookupValue);
    
    // Apply deltas
    // DNG spec: deltaH is in degrees (-180 to 180), we work in 0-1
    // deltaS and deltaV are multipliers (0 = black, 1 = no change, 2 = double)
    hsv.x = fract(hsv.x + delta.x);  // Hue wraps around
    hsv.y = max(hsv.y + hsv.y * delta.y, 0.0);  // Saturation: multiplicative delta, only clamp negatives
    
    // For value: apply delta to normalized value, then scale back for HDR if needed
    float adjustedValue = max(lookupValue + lookupValue * delta.z, 0.0);
    if (isHDR) {
        // Scale the adjustment proportionally for HDR values to preserve HDR highlights
        float scale = originalValue / lookupValue;  // HDR scale factor
        hsv.z = adjustedValue * scale;
    } else {
        // For SDR values (after compression), apply delta normally
        hsv.z = adjustedValue;
    }
    
    // Convert back to RGB
    return hsv2rgb(hsv);
}

// =====================================================
// DNG Profile LookTable sampling
// 3D RGB->RGB color LUT for creative color grading
// The 3D LUT is flattened into a 2D texture
// =====================================================

// Sample the LookTable at given RGB coordinates
highp vec3 sampleLookTable(highp float r, highp float g, highp float b) {
    highp float cols = float(lookTableDims.x);   // Red axis
    highp float rows = float(lookTableDims.y);   // Green axis
    highp float depth = float(lookTableDims.z);  // Blue axis
    
    // Compute indices (no wrapping for RGB)
    highp float rIdx = r * (cols - 1.0);
    highp float gIdx = g * (rows - 1.0);
    highp float bIdx = b * (depth - 1.0);
    
    // Trilinear interpolation corners
    int r0 = int(floor(rIdx));
    int r1 = min(r0 + 1, int(cols) - 1);
    highp float rFrac = fract(rIdx);
    
    int g0 = int(floor(gIdx));
    int g1 = min(g0 + 1, int(rows) - 1);
    highp float gFrac = fract(gIdx);
    
    int b0 = int(floor(bIdx));
    int b1 = min(b0 + 1, int(depth) - 1);
    highp float bFrac = fract(bIdx);
    
    // Texture width = cols * depth
    highp float texWidth = cols * depth;
    
    // Sample 8 corners for trilinear interpolation
    // Texture X = red * depth + blue, Y = green
    // Cast texture results to highp for precision
    highp vec3 c000 = vec3(texture(profileLookTable, vec2((float(r0) * depth + float(b0) + 0.5) / texWidth, (float(g0) + 0.5) / rows)).rgb);
    highp vec3 c001 = vec3(texture(profileLookTable, vec2((float(r0) * depth + float(b1) + 0.5) / texWidth, (float(g0) + 0.5) / rows)).rgb);
    highp vec3 c010 = vec3(texture(profileLookTable, vec2((float(r0) * depth + float(b0) + 0.5) / texWidth, (float(g1) + 0.5) / rows)).rgb);
    highp vec3 c011 = vec3(texture(profileLookTable, vec2((float(r0) * depth + float(b1) + 0.5) / texWidth, (float(g1) + 0.5) / rows)).rgb);
    highp vec3 c100 = vec3(texture(profileLookTable, vec2((float(r1) * depth + float(b0) + 0.5) / texWidth, (float(g0) + 0.5) / rows)).rgb);
    highp vec3 c101 = vec3(texture(profileLookTable, vec2((float(r1) * depth + float(b1) + 0.5) / texWidth, (float(g0) + 0.5) / rows)).rgb);
    highp vec3 c110 = vec3(texture(profileLookTable, vec2((float(r1) * depth + float(b0) + 0.5) / texWidth, (float(g1) + 0.5) / rows)).rgb);
    highp vec3 c111 = vec3(texture(profileLookTable, vec2((float(r1) * depth + float(b1) + 0.5) / texWidth, (float(g1) + 0.5) / rows)).rgb);
    
    // Trilinear interpolation (use highp for precision)
    highp vec3 c00 = mix(c000, c001, bFrac);
    highp vec3 c01 = mix(c010, c011, bFrac);
    highp vec3 c10 = mix(c100, c101, bFrac);
    highp vec3 c11 = mix(c110, c111, bFrac);
    
    highp vec3 c0 = mix(c00, c01, gFrac);
    highp vec3 c1 = mix(c10, c11, gFrac);
    
    return mix(c0, c1, rFrac);
}

// Apply the LookTable to an RGB color
// This is called AFTER HDR compression, so values should be in [0, 1] range for SDR output.
// For HDR output, compression is skipped to preserve dynamic range, so values may still be > 1.0.
highp vec3 applyLookTable(highp vec3 rgb) {
    // HDR-aware: This function is called after HDR compression.
    // For SDR output: compression brings values to [0, 1], so LUT can be applied normally.
    // For HDR output: compression is skipped, so values may be > 1.0 - skip LUT to preserve HDR.
    float maxChannel = max(max(rgb.r, rgb.g), rgb.b);
    if (maxChannel > 1.0) {
        // For HDR values (when compression was skipped), preserve them unchanged
        // The LUT is meant for SDR color grading, not HDR highlights
        return rgb;
    }
    
    // If LUT uses sRGB encoding, convert to sRGB for lookup
    highp vec3 lookupRGB = rgb;
    if (lookTableEncoding == 1) {
        // Convert linear to sRGB gamma for lookup (use highp for precision)
        lookupRGB = vec3(gammaEncode(rgb.r), gammaEncode(rgb.g), gammaEncode(rgb.b));
    }
    
    // Clamp to valid range for lookup (values should already be in [0, 1] after compression)
    lookupRGB = clamp(lookupRGB, 0.0, 1.0);
    
    // Sample the LUT
    highp vec3 result = sampleLookTable(lookupRGB.r, lookupRGB.g, lookupRGB.b);
    
    // If LUT uses sRGB encoding, convert result back to linear (use highp for precision)
    if (lookTableEncoding == 1) {
        result = vec3(gammaDecode(result.r), gammaDecode(result.g), gammaDecode(result.b));
    }
    
    return result;
}

// Sample the External LUT at given RGB coordinates
// Uses the same trilinear interpolation as DNG Profile LookTable
highp vec3 sampleExternalLut(highp float r, highp float g, highp float b) {
    highp float cols = float(externalLutDims.x);   // Red axis
    highp float rows = float(externalLutDims.y);   // Green axis
    highp float depth = float(externalLutDims.z);  // Blue axis
    
    // Compute indices (no wrapping for RGB)
    highp float rIdx = r * (cols - 1.0);
    highp float gIdx = g * (rows - 1.0);
    highp float bIdx = b * (depth - 1.0);
    
    // Trilinear interpolation corners
    int r0 = int(floor(rIdx));
    int r1 = min(r0 + 1, int(cols) - 1);
    highp float rFrac = fract(rIdx);
    
    int g0 = int(floor(gIdx));
    int g1 = min(g0 + 1, int(rows) - 1);
    highp float gFrac = fract(gIdx);
    
    int b0 = int(floor(bIdx));
    int b1 = min(b0 + 1, int(depth) - 1);
    highp float bFrac = fract(bIdx);
    
    // Texture width = cols * depth
    highp float texWidth = cols * depth;
    
    // Sample 8 corners for trilinear interpolation
    // Texture X = red * depth + blue, Y = green
    highp vec3 c000 = vec3(texture(externalLut, vec2((float(r0) * depth + float(b0) + 0.5) / texWidth, (float(g0) + 0.5) / rows)).rgb);
    highp vec3 c001 = vec3(texture(externalLut, vec2((float(r0) * depth + float(b1) + 0.5) / texWidth, (float(g0) + 0.5) / rows)).rgb);
    highp vec3 c010 = vec3(texture(externalLut, vec2((float(r0) * depth + float(b0) + 0.5) / texWidth, (float(g1) + 0.5) / rows)).rgb);
    highp vec3 c011 = vec3(texture(externalLut, vec2((float(r0) * depth + float(b1) + 0.5) / texWidth, (float(g1) + 0.5) / rows)).rgb);
    highp vec3 c100 = vec3(texture(externalLut, vec2((float(r1) * depth + float(b0) + 0.5) / texWidth, (float(g0) + 0.5) / rows)).rgb);
    highp vec3 c101 = vec3(texture(externalLut, vec2((float(r1) * depth + float(b1) + 0.5) / texWidth, (float(g0) + 0.5) / rows)).rgb);
    highp vec3 c110 = vec3(texture(externalLut, vec2((float(r1) * depth + float(b0) + 0.5) / texWidth, (float(g1) + 0.5) / rows)).rgb);
    highp vec3 c111 = vec3(texture(externalLut, vec2((float(r1) * depth + float(b1) + 0.5) / texWidth, (float(g1) + 0.5) / rows)).rgb);
    
    // Trilinear interpolation
    highp vec3 c00 = mix(c000, c001, bFrac);
    highp vec3 c01 = mix(c010, c011, bFrac);
    highp vec3 c10 = mix(c100, c101, bFrac);
    highp vec3 c11 = mix(c110, c111, bFrac);
    
    highp vec3 c0 = mix(c00, c01, gFrac);
    highp vec3 c1 = mix(c10, c11, gFrac);
    
    return mix(c0, c1, rFrac);
}

// Apply the External LUT to linear RGB (ProPhoto space)
// Applied in linear space before sRGB conversion for correct color handling
// .cube LUTs are typically designed for linear RGB input
highp vec3 applyExternalLutToLinear(highp vec3 linearRGB) {
    // HDR-aware: Skip LUT for HDR values > 1.0 to preserve dynamic range
    float maxChannel = max(max(linearRGB.r, linearRGB.g), linearRGB.b);
    if (maxChannel > 1.0) {
        return linearRGB;
    }
    
    // Clamp to valid range for lookup (values should be in [0, 1] after compression)
    highp vec3 lookupRGB = clamp(linearRGB, 0.0, 1.0);
    
    // Sample the LUT (LUT data is in linear space for .cube format)
    highp vec3 result = sampleExternalLut(lookupRGB.r, lookupRGB.g, lookupRGB.b);
    
    // Result is already in linear space (no conversion needed for .cube format)
    return result;
}

// Legacy function for sRGB space (kept for compatibility, but not used)
// Apply the External LUT to an RGB color in sRGB space
highp vec3 applyExternalLut(highp vec3 rgb) {
    // HDR-aware: Skip LUT for HDR values > 1.0 to preserve dynamic range
    float maxChannel = max(max(rgb.r, rgb.g), rgb.b);
    if (maxChannel > 1.0) {
        return rgb;
    }
    
    // If LUT uses sRGB encoding, convert to sRGB for lookup
    highp vec3 lookupRGB = rgb;
    if (externalLutEncoding == 1) {
        lookupRGB = vec3(gammaEncode(rgb.r), gammaEncode(rgb.g), gammaEncode(rgb.b));
    }
    
    // Clamp to valid range for lookup
    lookupRGB = clamp(lookupRGB, 0.0, 1.0);
    
    // Sample the LUT
    highp vec3 result = sampleExternalLut(lookupRGB.r, lookupRGB.g, lookupRGB.b);
    
    // If LUT uses sRGB encoding, convert result back to linear
    if (externalLutEncoding == 1) {
        result = vec3(gammaDecode(result.r), gammaDecode(result.g), gammaDecode(result.b));
    }
    
    return result;
}

// =====================================================
// MAT MODE PROCESSING
// Subtle sigmoidal curve + hue shifts for a natural look
// =====================================================

// Sigmoidal contrast curve for MAT mode
// Creates a gentle S-curve that adds depth without harshness
vec3 applySigmoidalContrast(vec3 rgb, float contrast, float midpoint) {
    // Sigmoidal contrast: smoother than gamma or polynomial
    // contrast: 0 = linear, higher = more S-curve
    // midpoint: pivot point (typically 0.5)
    // 
    // IMPORTANT: This function is designed to map [0,1] to [0,1] for SDR values.
    // For HDR values > 1.0, we preserve them unchanged to avoid artifacts.
    // The normalization formula breaks down for x > 1.0, causing incorrect
    // scaling that can produce negative values or invalid colors (black/cyan/pink).
    vec3 result;
    for (int i = 0; i < 3; i++) {
        float x = rgb[i];
        
        // For HDR values > 1.0, preserve them unchanged
        // The sigmoidal contrast is meant to adjust contrast in the [0,1] range only
        // Applying it to HDR highlights would incorrectly normalize them
        if (x > 1.0) {
            result[i] = x;  // Preserve HDR values unchanged
            continue;
        }
        
        // Normalized sigmoid function centered at midpoint
        // y = 1 / (1 + exp(-contrast * (x - midpoint)))
        // Rescaled to map [0,1] to [0,1]
        float sigmoid_mid = 1.0 / (1.0 + exp(-contrast * (x - midpoint)));
        float sigmoid_0 = 1.0 / (1.0 + exp(-contrast * (0.0 - midpoint)));
        float sigmoid_1 = 1.0 / (1.0 + exp(-contrast * (1.0 - midpoint)));
        result[i] = (sigmoid_mid - sigmoid_0) / (sigmoid_1 - sigmoid_0);
        
        // Preserve very dark values to avoid crushing shadows to pure black
        // If input is very small (> 0 but < threshold), ensure output is also > 0
        if (x > 0.0 && x < 0.01) {
            float minOutput = x * 0.1; // Preserve at least 10% of very dark input
            result[i] = max(result[i], minOutput);
        }
    }
    return result;
}

// Apply inverse sigmoidal contrast to reduce contrast (flatten the curve)
// This is used for darkening compensation to reduce highlight contrast
// To reduce contrast, we blend the sigmoidal result with the original (linear)
vec3 applyInverseSigmoidalContrast(vec3 rgb, float strength, float midpoint) {
    // Apply sigmoidal contrast as reference
    vec3 sigmoidal = applySigmoidalContrast(rgb, 3.0, midpoint);
    // Blend towards original (linear) to reduce contrast
    // Higher strength = more flattening (less contrast)
    return mix(sigmoidal, rgb, strength);
}

// MAT mode hue shifts:
// - Desaturated greens near yellow shift toward yellow
// - Yellow-orange tones shift slightly toward magenta/pink warmth
vec3 applyMATHueShifts(vec3 rgb) {
    vec3 hsv = rgb2hsv(rgb);
    float hue = hsv.x;  // 0-1 where: 0=red, 0.167=yellow, 0.333=green, 0.5=cyan, 0.667=blue, 0.833=magenta
    float sat = hsv.y;
    float val = hsv.z;
    
    // Constants for hue positions (0-1 scale)
    const float HUE_RED = 0.0;
    const float HUE_YELLOW = 0.167;
    const float HUE_GREEN = 0.333;
    const float HUE_CYAN = 0.5;
    const float HUE_MAGENTA = 0.833;
    
    // =====================================================
    // SHIFT 1: Greens already near yellow → toward yellow
    // Target: greens between pure green and yellow (hue 0.167-0.333)
    // Effect: only affects greens that are already close to yellow
    // =====================================================
    if (matGreenToYellowShift) {
        // Green-yellow region (between yellow 0.167 and green 0.333)
        float greenYellowMask = smoothstep(0.17, 0.22, hue) * smoothstep(0.35, 0.28, hue);
        
        // Proximity to yellow: stronger effect the closer to yellow (0.167) we already are
        // At hue=0.18 (very close to yellow): proximity ~1.0
        // At hue=0.33 (pure green): proximity ~0.0
        float yellowProximity = smoothstep(0.33, 0.18, hue);
        
        // Only affect desaturated greens (sat < 0.5 gets full effect, sat > 0.8 gets none)
        float desatMask = smoothstep(0.7, 0.3, sat);
        
        // Combined mask - weight by proximity to yellow
        float greenToYellowShift = greenYellowMask * desatMask * yellowProximity;
        
        // Shift toward yellow (decrease hue)
        // 0.04 = about 14 degrees toward yellow
        hue -= greenToYellowShift * 0.05;
    }
    
    // =====================================================
    // SHIFT 2: Yellows already near red/magenta → subtle warmth
    // Target: yellows and oranges (hue 0.02-0.2)
    // Effect: only affects yellows already leaning toward red/warm
    // =====================================================
    if (matYellowToWarmShift) {
        // Yellow-orange region
        float yellowMask = smoothstep(0.02, 0.08, hue) * smoothstep(0.20, 0.12, hue);
        
        // Proximity to red: stronger effect the closer to red (0.0) we already are
        // At hue=0.05 (orange-ish): proximity ~1.0
        // At hue=0.167 (pure yellow): proximity ~0.0
        float redProximity = smoothstep(0.167, 0.05, hue);
        
        // Stronger effect on more saturated yellows (the shift adds warmth)
        float satBoost = smoothstep(0.2, 0.6, sat);
        
        // Combined mask - weight by proximity to red/magenta
        float yellowToWarmShift = yellowMask * satBoost * redProximity;
        
        // Shift toward red (decrease hue) and add slight magenta by reducing saturation minimally
        // This creates warmth without making yellows look sick
        hue -= yellowToWarmShift * 0.01;  // About 3.6 degrees toward red
        
        // Very subtle desaturation to add a touch of the "magenta warmth" effect
        // This mimics the film look where yellows aren't pure but have warmth
        sat *= 1.0 - yellowToWarmShift * 0.07;
    }

    // Ensure hue stays in valid range (wrap around if needed)
    hue = fract(hue);
    
    hsv = vec3(hue, max(sat, 0.0), val);  // Only clamp saturation negatives, preserve HDR value
    return hsv2rgb(hsv);
}

// Leica-like highlight bloom/glow for dreamy aesthetic
// Creates soft highlight glow without harsh clipping
vec3 applyHighlightBloom(vec3 rgb) {
    float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
    
    // Create bloom mask - strongest in bright highlights
    // Smooth transition starting from mid-highlights (0.6) to pure white (1.0)
    float bloomMask = smoothstep(0.6, 0.95, luma);
    
    // Soft glow effect: add subtle white glow to highlights
    // Strength is gentle to maintain natural look
    vec3 bloom = vec3(bloomMask * 0.08);  // 8% white glow in brightest areas
    
    // Blend bloom with original, stronger in highlights
    return rgb + bloom * bloomMask;
}

// Selective highlight desaturation for dreamy look
// Leica images often have slightly desaturated highlights
vec3 applyHighlightDesaturation(vec3 rgb) {
    vec3 hsv = rgb2hsv(rgb);
    float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
    
    // Desaturate highlights more than shadows
    // Smooth transition from midtones (0.5) to highlights (1.0)
    float highlightMask = smoothstep(0.5, 0.9, luma);
    
    // Gentle desaturation: up to 12% reduction in brightest highlights
    float desatAmount = highlightMask * 0.12;
    hsv.y *= (1.0 - desatAmount);
    
    return hsv2rgb(hsv);
}

// Enhance shadow richness (Leica signature: deep, rich blacks with detail)
// Deepens shadows while preserving detail in dark areas
vec3 enhanceShadowRichness(vec3 rgb) {
    float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
    
    // Shadow mask: strongest in deep shadows (0.0-0.3)
    float shadowMask = smoothstep(0.3, 0.05, luma);
    
    // Gentle darkening curve that preserves detail
    // Uses a soft power curve: darker shadows get slightly darker, but not crushed
    float shadowCurve = pow(luma, 1.08);  // Slight darkening (1.08 power)
    
    // Blend original with darkened version in shadows
    float newLuma = mix(luma, shadowCurve, shadowMask * 0.4);  // 40% blend in shadows
    
    // Scale RGB proportionally to maintain color
    return rgb * (newLuma / max(luma, 0.001));
}

// Subtle vignetting for Leica-like edge darkening
// Creates gentle edge falloff without harsh transitions
vec3 applyVignette(vec3 rgb, vec2 uv) {
    // Calculate distance from center (normalized to [0, 1])
    // uv should be in [0, 1] range
    vec2 center = vec2(0.5, 0.5);
    float dist = distance(uv, center);
    
    // Normalize distance: 0 at center, ~0.707 at corners
    // Scale to make corners = 1.0
    float normalizedDist = dist / 0.707;
    
    // Vignette curve using cosine for natural optical falloff (mimics cos⁴ lens law)
    // Starts affecting at 60% from center, full effect at edges
    // Cosine provides more realistic lens vignette than linear smoothstep
    float vignetteT = smoothstep(0.6, 1.0, normalizedDist);
    // Cosine S-curve: 0 at center, 1 at edges, with smooth acceleration
    float vignetteMask = 0.5 - 0.5 * cos(vignetteT * PI);
    
    // Gentle darkening: up to 8% reduction at edges
    float darkenAmount = vignetteMask * 0.08;
    
    return rgb * (1.0 - darkenAmount);
}

// Softer highlight rolloff for dreamy aesthetic
// More gentle highlight compression than standard curves
// HDR-aware: handles values > 1.0 correctly
vec3 applySoftHighlightRolloff(vec3 rgb) {
    // Apply gentle shoulder compression to highlights
    // This creates smoother highlight transitions
    vec3 result;
    for (int i = 0; i < 3; i++) {
        float x = rgb[i];
        if (x > 0.7) {
            if (x > 1.0) {
                // HDR-aware: For values > 1.0, apply stronger compression to bring toward 1.0
                // This preserves the HDR nature while still applying the rolloff effect
                float hdrExcess = x - 1.0;
                // Compress HDR excess more aggressively (50% compression)
                float compressedHDR = 1.0 + hdrExcess * 0.5;
                // Also apply the standard rolloff to the [0.7, 1.0] portion
                float sdrMask = smoothstep(0.7, 1.0, 1.0);
                float sdrCompressed = 0.7 + (1.0 - 0.7) * (1.0 - sdrMask * 0.25);
                // Combine: apply rolloff to SDR portion, compress HDR excess
                result[i] = sdrCompressed + (compressedHDR - 1.0);
            } else {
                // Standard rolloff for values in [0.7, 1.0]
                // Uses smooth exponential curve instead of hard clipping
                float highlightMask = smoothstep(0.7, 1.0, x);
                float compressed = 0.7 + (x - 0.7) * (1.0 - highlightMask * 0.25);  // 25% compression at peak
                result[i] = mix(x, compressed, highlightMask);
            }
        } else {
            result[i] = x;
        }
    }
    return result;
}

// MAT mode: Leica-like and dreamy processing
// Combines all the aesthetic enhancements
// skipHueAndShadow: if true, skips hue shifts and shadow richness (when LUT is used)
vec3 applyMATProcessing(vec3 rgb, vec2 uv, bool skipHueAndShadow) {
    // 1. Softer highlight rolloff first (before bloom)
    rgb = applySoftHighlightRolloff(rgb);
    
    // 2. Highlight bloom for dreamy glow
    rgb = applyHighlightBloom(rgb);
    
    // 3. Selective highlight desaturation
    rgb = applyHighlightDesaturation(rgb);
    
    // 4. Enhance shadow richness (Leica signature)
    // Skip when LUT is used to avoid interfering with LUT's color grading
    if (!skipHueAndShadow) {
        rgb = enhanceShadowRichness(rgb);
    }
    
    // 5. Subtle vignetting
    rgb = applyVignette(rgb, uv);
    
    // 6. Apply hue shifts (existing color adjustments)
    // Skip when LUT is used to avoid interfering with LUT's color grading
    if (!skipHueAndShadow) {
        rgb = applyMATHueShifts(rgb);
    }
    
    return max(rgb, vec3(0.0));  // Only clamp negatives, preserve HDR values > 1.0
}

// ============================================================================
// LEICA M9 MODE PROCESSING
// Emulates the Kodak KAF-18500 CCD sensor characteristics:
// - Smooth CCD-style highlight rolloff
// - Warm "Leica red" color signature
// - Film-like tonal rendering with lifted blacks
// - Classic micro-contrast without modern digital harshness
// ============================================================================

// CCD-style highlight rolloff - smoother than CMOS, with characteristic glow
vec3 applyCCDHighlightRolloff(vec3 rgb) {
    vec3 result;
    for (int i = 0; i < 3; i++) {
        float x = rgb[i];
        if (x > 0.6) {
            if (x > 1.0) {
                // HDR values: gentle compression preserving some highlight detail
                float hdrExcess = x - 1.0;
                // CCD-like soft clipping (gentler than CMOS)
                float compressedHDR = 1.0 + hdrExcess * 0.4;
                result[i] = 0.85 + (compressedHDR - 0.6) * 0.15 / 0.4;
            } else {
                // Characteristic CCD shoulder curve
                // Smoother, more gradual rolloff than typical CMOS
                float t = (x - 0.6) / 0.4;  // 0 at 0.6, 1 at 1.0
                float shoulder = t * t * (3.0 - 2.0 * t);  // Smoothstep
                // Compress highlights with gentle S-curve
                result[i] = 0.6 + 0.4 * (t * 0.7 + shoulder * 0.3);
            }
        } else {
            result[i] = x;
        }
    }
    return result;
}

// Leica M9 "Leica red" - warm, slightly magenta-shifted reds
// The M9's CCD sensor has a distinctive rendering of reds
vec3 applyLeicaRedShift(vec3 rgb) {
    vec3 hsv = rgb2hsv(rgb);
    float hue = hsv.x;
    float sat = hsv.y;
    float val = hsv.z;
    
    // Red-orange region (hue 0-0.08 and 0.92-1.0)
    // Shift slightly toward magenta/pink for "Leica red" look
    float redMask = 0.0;
    if (hue < 0.08) {
        redMask = smoothstep(0.08, 0.0, hue);
    } else if (hue > 0.92) {
        redMask = smoothstep(0.92, 1.0, hue);
    }
    
    // Only affect saturated reds (skin tones are less saturated)
    float satMask = smoothstep(0.3, 0.6, sat);
    float combinedMask = redMask * satMask;
    
    // Shift reds slightly toward magenta (decrease hue, wrap around)
    // Also slightly boost saturation for vibrant "Leica red"
    if (combinedMask > 0.0) {
        // Shift hue toward magenta (subtract ~0.02 with wraparound)
        float hueShift = -0.015 * combinedMask;
        hsv.x = fract(hsv.x + hueShift + 1.0);
        
        // Subtle saturation boost for punch
        hsv.y = mix(hsv.y, min(hsv.y * 1.08, 1.0), combinedMask * 0.5);
    }
    
    return hsv2rgb(hsv);
}

// Film-like tonality: lifted blacks, gentle S-curve
// Emulates the tonal response of film as rendered by the M9's processing
vec3 applyFilmTonality(vec3 rgb) {
    float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
    
    // Lift shadows slightly (film-like)
    // Creates that "open shadows" look without losing depth
    float shadowLift = smoothstep(0.0, 0.25, luma);
    float liftAmount = (1.0 - shadowLift) * 0.03;  // 3% lift in deep shadows
    
    // Apply gentle S-curve for classic film contrast
    // Subtle: just enough to add dimension without harsh modern digital look
    vec3 result = rgb + vec3(liftAmount);
    
    // Gentle S-curve using sigmoid-like function
    for (int i = 0; i < 3; i++) {
        float x = result[i];
        if (x > 0.0 && x < 1.0) {
            // Very subtle S-curve: 0.5 midpoint, gentle slopes
            float centered = x - 0.5;
            float curved = centered * (1.0 + 0.15 * (1.0 - abs(centered) * 2.0));
            result[i] = curved + 0.5;
        }
    }
    
    return result;
}

// Warm cast in shadows (characteristic of the M9)
// Adds subtle warmth to shadow regions
vec3 applyWarmShadows(vec3 rgb) {
    float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
    
    // Only affect shadows
    float shadowMask = smoothstep(0.35, 0.1, luma);
    
    // Subtle warm shift: slightly increase red, decrease blue
    vec3 warmShift = vec3(0.015, 0.005, -0.01) * shadowMask;
    
    return rgb + warmShift;
}

// Classic micro-contrast without modern digital harshness
// The M9 has good micro-contrast but it's rendered naturally
vec3 applyClassicMicrocontrast(vec3 rgb, vec2 uv) {
    // Very subtle local contrast enhancement
    // This is already handled by LCE settings, so we just ensure
    // the result doesn't look over-processed
    
    // Gentle luminance-based local contrast
    float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
    
    // Midtone emphasis without harshness
    float midtoneMask = 1.0 - abs(luma - 0.5) * 2.0;
    midtoneMask = max(midtoneMask, 0.0);
    
    // Very subtle boost to midtone separation
    float contrastBoost = 1.0 + midtoneMask * 0.02;
    
    // Apply contrast around the mean
    vec3 result = (rgb - 0.5) * contrastBoost + 0.5;
    
    return max(result, vec3(0.0));
}

// Desaturate highlights (CCD characteristic - less colorful near clipping)
vec3 applyCCDHighlightDesaturation(vec3 rgb) {
    float luma = dot(rgb, vec3(0.299, 0.587, 0.114));
    
    // CCD sensors tend to desaturate more gracefully in highlights
    float highlightMask = smoothstep(0.65, 0.95, luma);
    
    // Gentle desaturation: up to 15% in brightest areas
    vec3 desat = vec3(luma);
    return mix(rgb, desat, highlightMask * 0.15);
}

// Main Leica M9 processing function
vec3 applyLeicaM9Processing(vec3 rgb, vec2 uv) {
    // 1. CCD-style highlight rolloff (before other processing)
    rgb = applyCCDHighlightRolloff(rgb);
    
    // 2. CCD highlight desaturation
    rgb = applyCCDHighlightDesaturation(rgb);
    
    // 3. Film-like tonality (lifted blacks, gentle S-curve)
    rgb = applyFilmTonality(rgb);
    
    // 4. Warm shadows (M9 characteristic)
    rgb = applyWarmShadows(rgb);
    
    // 5. Leica red color signature
    rgb = applyLeicaRedShift(rgb);
    
    // 6. Classic micro-contrast
    rgb = applyClassicMicrocontrast(rgb, uv);
    
    // Smooth rolloff to zero instead of hard clamp to prevent banding
    // Preserve HDR > 1.0
    return smoothMaxVec3(rgb, 0.0, 0.001);  // 0.1% transition zone - only affects near-zero values
}

float saturateToneMap(float inSat, bool isHDR) {
    if (inSat < 0.001f) {
        return inSat;
    }
    // For HDR, skip the saturation boost - HDR processing already tends to preserve saturation
    // For SDR, apply gentle boost to compensate for tone curve compression
    if (isHDR) {
        return inSat;  // No boost for HDR
    } else {
        return max(inSat, 1.03f * pow(inSat, 1.02f));
    }
}

vec3 saturate(vec3 rgb, bool isHDR) {
    float maxv = max(max(rgb.r, rgb.g), rgb.b);
    float minv = min(min(rgb.r, rgb.g), rgb.b);
    if (maxv > minv) {
        vec3 hsv = rgb2hsv(rgb);
        // Assume saturation map is either constant or has 8+1 values, where the last wraps around
        float f = texture(saturation, vec2(hsv.x * (16.f / 18.f) + (1.f / 18.f), 0.5f)).x;
        hsv.y = sigmoid(saturateToneMap(hsv.y, isHDR) * f, satLimit);
        
        // Adaptive saturation: reduce brightness in highly saturated areas to prevent clipping
        // adaptiveSaturation.x = strength, adaptiveSaturation.y = saturation power
        // Strengthened: increased cap from 0.1 to 0.15 for better oversaturation prevention
        float adaptStrength = adaptiveSaturation.x * (hsv.z * (1.f - hsv.z))
            * pow(hsv.y, adaptiveSaturation.y);
        hsv.z = mix(hsv.z, 0.5f, min(adaptStrength, 0.15f));
        
        rgb = hsv2rgb(hsv);
    }
    return rgb;
}

// Apply gamma correction to each color channel in RGB pixel
// Use highp precision to prevent banding in smooth gradients
highp vec3 gammaCorrectPixel(highp vec3 rgb) {
    return vec3(gammaEncode(rgb.r), gammaEncode(rgb.g), gammaEncode(rgb.b));
}

uint hash(uint x) {
    x += (x << 10u);
    x ^= (x >> 6u);
    x += (x << 3u);
    x ^= (x >> 11u);
    x += (x << 15u);
    return x;
}

int hash(int x) {
    return int(hash(uint(x)) >> 1);
}

ivec2 hash(ivec2 xy) {
    int hashTogether = hash(xy.x ^ xy.y);
    return ivec2(hash(xy.x ^ hashTogether), hash(xy.y ^ hashTogether));
}

/* Dither in linear space before gamma encoding - breaks up quantization early */
highp vec3 ditherLinear(highp vec3 rgb, ivec2 xy) {
    int dither = int(texelFetch(ditherTex, hash(xy) % ditherSize, 0).x);
    highp float noise = float(dither >> 8) / 255.f; // [0, 1]
    /* Apply dither in linear space: ±0.5/255 = ±0.196% to break up quantization before gamma */
    noise = (noise - 0.5f) / 255.f; // At most half a RGB value of noise
    return max(rgb + noise, vec3(0.0)); // Clamp negatives to prevent artifacts
}

/* Enhanced dithering after gamma encoding - prevents banding in final output */
highp vec3 dither(highp vec3 rgb, ivec2 xy) {
    int dither = int(texelFetch(ditherTex, hash(xy) % ditherSize, 0).x);
    highp float noise = float(dither >> 8) / 255.f; // [0, 1]
    noise = (noise - 0.5f) / 255.f; // At most half a RGB value of noise.
    
    // Only clamp negatives - preserve HDR values > 1.0
    // SDR output will clamp when reading as GL_UNSIGNED_BYTE anyway
    // HDR output needs values > 1.0 preserved for proper gain map generation
    return max(rgb + noise, 0.f);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy) + outOffset;
    xy.y += yOffset;
    
    // DEBUG: Check if outputHDR is set (DISABLED - working)
    // Green = HDR mode, Blue = SDR mode
    // if (outputHDR > 0) { color = vec4(0.0, 1.0, 0.0, 1.0); return; }

    highp vec3 proPhoto;
    
    if (inputIsRgb > 0) {
        // Input is already RGB from LateExposureFusion (xyY => RGB conversion + fusion already applied)
        // Skip xyY processing and conversion, use RGB directly
        vec4 rgbEncoded = texelFetch(highRes, xy, 0);
        proPhoto = rgbEncoded.rgb;
    } else {
        // Normal path: process xyY and convert to RGB
        // Sharpen and denoise value
        vec3 intermediate = processPatch(xy);
        
        // DEBUG: Check intermediate Y values - show as red intensity
        // If bright red in highlights, HDR Y values are present
        // color = vec4(intermediate.z / 10.0, 0.0, 0.0, 1.0); return;
        
        // DEBUG: Show chromaticity x as color (DISABLED)
        // color = vec4(intermediate.x, intermediate.x, intermediate.x, 1.0); return;

        // Convert to XYZ space (use highp for precision)
        // Validate xyY chromaticity coordinates before conversion
        // Invalid chromaticity (x + y > 1.0 or y = 0) can cause negative Z in xyYtoXYZ
        // This is the root cause of black/cyan/magenta artifacts
        highp vec3 validXYY = intermediate;
        
        // Apply color matching correction (shift chromaticity toward JPEG's average)
        // DISABLED: Color correction is now applied only to the ACES result in blendedHistogramCompress()
        // This prevents double-correction and allows histogram match to keep its implicit JPEG color
        /*
        if (histogramMatchApplied && (colorMatchCorrection.x != 0.0 || colorMatchCorrection.y != 0.0)) {
            // Apply chromaticity shift with distance-based weighting
            // Apply more correction to neutral colors, less to saturated colors
            // Distance from white point (D65: 0.3127, 0.3290)
            float distFromNeutral = length(vec2(validXYY.x - 0.3127, validXYY.y - 0.3290));
            // Weight: 1.0 for neutral colors, decreasing for saturated
            float correctionWeight = max(0.0, 1.0 - distFromNeutral * 2.5);
            
            validXYY.x += colorMatchCorrection.x * correctionWeight;
            validXYY.y += colorMatchCorrection.y * correctionWeight;
        }
        */
        
        if (validXYY.x + validXYY.y > 1.0) {
            // Normalize chromaticity to valid range (x + y should be <= 1.0)
            // Scale to just below 1.0 to prevent edge cases
            float sum = validXYY.x + validXYY.y;
            validXYY.x = validXYY.x / sum * 0.99;
            validXYY.y = validXYY.y / sum * 0.99;
        }
        if (validXYY.y <= 0.0) {
            // Use D65 white point chromaticity as fallback (standard illuminant)
            validXYY.x = 0.3127;
            validXYY.y = 0.3290;
        }
        highp vec3 XYZ = xyYtoXYZ(validXYY);

        // Convert to ProPhoto space (use highp for precision)
        proPhoto = XYZtoProPhoto * XYZ;
    }

    // Apply user color transform (RGB mixing matrix) in ProPhoto space
    // This happens before baseline exposure, so values are in reasonable range
    // No HDR clipping issues since we're before all HDR processing
    // Color transforms should be applied in linear color space (ProPhoto is linear)
    proPhoto = colorTransform * proPhoto;
    // Only clamp negatives - values should be in reasonable range already
    proPhoto = max(proPhoto, vec3(0.0));
    
    // DEBUG: ProPhoto RGB (DISABLED)
    // color = vec4(pow(proPhoto, vec3(1.0/2.2)), 1.0); return;

    // Baseline Exposure Note:
    // Baseline exposure is applied in PreProcess as a simple linear multiply.
    // HDR values flow through the entire pipeline without compression.
    // The only difference between SDR and HDR output is whether we apply
    // gammaACESFusion compression at the end (SDR) or preserve full range (HDR).
    // This simplifies UHDR gain map creation: gain = uncompressed / compressed
    
    // Apply tone exposure BEFORE HDR compression to preserve full dynamic range
    // This allows exposure to work on the full range, then HDR compression smooths it
    // The curve prevents clipping in [0,1] range while preserving HDR highlights > 1.0
    // Work in ProPhoto space to avoid color space conversion issues with HDR values
    if (abs(toneExposure - 1.0) > 0.001) {
        proPhoto = applyExposureCurve(proPhoto, toneExposure);
        
        // Apply sigmoidal contrast compensation to restore uniform perceived contrast
        // This compensates for the contrast changes introduced by the exposure curve
        // Similar to compensating for gamma correction: when applying gamma to brighten,
        // we apply sigmoidal contrast to restore perceived contrast
        float contrastCompensation = calculateExposureContrastCompensation(toneExposure);
        if (abs(contrastCompensation) > 0.001) {
            if (contrastCompensation > 0.0) {
                // Brightening: increase contrast to compensate for highlight compression
                // Apply sigmoidal contrast to restore perceived contrast
                proPhoto = applySigmoidalContrast(proPhoto, contrastCompensation, 0.5);
            } else {
                // Darkening: reduce contrast to compensate for highlight expansion
                // The power curve expands highlights, so we flatten the curve slightly
                float reductionStrength = -contrastCompensation;  // Convert to positive
                proPhoto = applyInverseSigmoidalContrast(proPhoto, reductionStrength, 0.5);
            }
        }
    }
    
    // Apply HDR compression BEFORE color space conversion
    // 
    // CRITICAL: The ProPhoto → sRGB matrix can produce NEGATIVE VALUES when 
    // input values exceed 1.0. This causes:
    // - Very bright highlights → all channels negative → BLACK after clamping
    // - Less bright HDR → red channel negative first → CYAN (green + blue)
    //
    // Compression is needed when any channel exceeds 1.0, regardless of source:
    // - High baseline exposure (e.g., 16x for 4 EV)
    // - Exposure fusion gamma curves pushing values up
    // - Tone exposure adjustments
    // - Sigmoidal contrast boosting highlights
    //
    // For SDR output: compress to [0, 1] before matrix conversion
    // For HDR output: apply soft gamut mapping to prevent extreme negatives
    
    // Ensure no negative values before compression
    // Negatives can come from XYZ → ProPhoto conversion for out-of-gamut colors
    proPhoto = max(proPhoto, vec3(0.0));
    
    float maxChannel = max(max(proPhoto.r, proPhoto.g), proPhoto.b);
    
    // DEBUG: Check for NaN in proPhoto before compression
    // if (proPhoto.r != proPhoto.r || proPhoto.g != proPhoto.g || proPhoto.b != proPhoto.b) {
    //     color = vec4(1.0, 0.0, 0.5, 1.0); return; // Pink = NaN in proPhoto
    // }
    
    // =====================================================================
    // HDR vs SDR: Optimized - skip compression when outputHDR=1
    // =====================================================================
    // Optimization: When outputHDR=1, skip compression entirely so proPhoto
    // remains uncompressed. This eliminates the need for proPhotoUncompressed
    // and the gain calculation in the HDR output path.
    
    // Store uncompressed/expanded value for HDR output (only needed for SDR pass)
    highp vec3 proPhotoUncompressed;
    
    if (outputHDR == 0) {
        // SDR pass: store uncompressed value before compression (needed for gain calculation if HDR is captured)
        if (baselineExposure > 1.0) {
            // Natural HDR: store original before compression
            proPhotoUncompressed = max(proPhoto, vec3(0.0));
        } else if (syntheticHdrHeadroom > 1.0) {
            // Synthetic HDR: expand highlights to create headroom for UHDR
            proPhotoUncompressed = syntheticHdrExpand(max(proPhoto, vec3(0.0)), syntheticHdrHeadroom);
        } else {
            // No HDR: SDR and HDR will be identical
            proPhotoUncompressed = max(proPhoto, vec3(0.0));
        }
    } else {
        // HDR pass: proPhotoUncompressed not needed (proPhoto will remain uncompressed)
        // Initialize to avoid undefined variable (won't be used)
        proPhotoUncompressed = vec3(0.0);
    }
    
    // Apply HDR compression only for SDR output (outputHDR == 0) and when inputIsRgb <= 0
    // When outputHDR > 0, skip compression to keep proPhoto uncompressed
    // When inputIsRgb > 0, compression was already applied by LateExposureFusion
    if (outputHDR == 0) {
        if (inputIsRgb <= 0) {
            // Input is xyY - apply compression methods here
            bool isHDR = baselineExposure > 1.0 && (baselineExposureCompression == 0 || baselineExposureCompression == 17);
            // Apply HDR compression only when needed (baselineExposure > 1.0 means HDR content)
            // Soft shoulder: preserves shadow/midtone contrast, only compresses highlights
            // HDR values > 1.0 are compressed smoothly to fit in [0, 1]
            if (isHDR && hdrCompressionMethod == 5) {
                // Late Exposure Fusion (hdrCompressionMethod == 5) - should not reach here when inputIsRgb <= 0
                // Fall back to gammaACESFusionCompress
                proPhoto = gammaACESFusionCompress(proPhoto, maxChannel);
            } else if (isHDR && histogramMatchApplied > 0 && baselineExposureCompression == 15) {
                // Histogram matching (baselineExposureCompression == 15) - original form in RGB space
                // Only apply when histogramMatchApplied is set and inputIsRgb <= 0
                proPhoto = histogramMatchCompress(proPhoto, histMatchMaxHdr);
            } else if (isHDR) {
                // Apply HDR compression based on selected method
                // Note: hdrCompressionMethod == 5 (Late Exposure Fusion) is handled above
                if (hdrCompressionMethod == 0) {
                    // Reinhard
                    proPhoto = reinhardCompress_GammaFusion(proPhoto);
                } else if (hdrCompressionMethod == 1) {
                    // ACES Filmic
                    proPhoto = acesFilmicSoftCompress_GammaFusion(proPhoto, maxChannel);
                } else if (hdrCompressionMethod == 2) {
                    // Uncharted 2
                    proPhoto = applyHDRCompressionVec3(proPhoto, 0.85, 2);
                } else if (hdrCompressionMethod == 3) {
                    // Improved Rational
                    proPhoto = applyHDRCompressionVec3(proPhoto, 0.85, 3);
                } else if (hdrCompressionMethod == 4) {
                    // Gamma+ACES Fusion (default)
                    proPhoto = gammaACESFusionCompress(proPhoto, maxChannel);
                } else {
                    // Fallback to Gamma+ACES Fusion
                    proPhoto = gammaACESFusionCompress(proPhoto, maxChannel);
                }
            }
            
            // Smooth rolloff instead of hard clamp to prevent banding (only for SDR)
            proPhoto = smoothClampVec3(proPhoto, 0.0, 1.0, 0.001);  // 0.1% transition zone - only affects extremes
        } else {
            // Input is RGB from LateExposureFusion - compression was already applied
            // Just clamp for SDR output
            proPhoto = smoothClampVec3(proPhoto, 0.0, 1.0, 0.001);
        }
    } else {
        // HDR pass: apply synthetic expansion if needed, but skip compression
        if (syntheticHdrHeadroom > 1.0) {
            // Synthetic HDR: expand highlights to create headroom for UHDR
            proPhoto = syntheticHdrExpand(max(proPhoto, vec3(0.0)), syntheticHdrHeadroom);
        }
        // Don't clamp proPhoto for HDR - we need to preserve values > 1.0
    }
    
    // Apply External LUT file in ProPhoto (linear) space BEFORE sRGB conversion
    // This is the correct place for .cube LUTs which expect linear RGB input
    // Applied before DNG Profile LookTable to allow external LUT to override profile
    if (hasExternalLut) {
        proPhoto = applyExternalLutToLinear(proPhoto);
    }

    // Convert to sRGB space (use highp for precision)
    // For SDR: values are compressed and clamped, so should be in [0, 1] range
    // For HDR: values may exceed 1.0 (uncompressed), which is fine for HDR output
    highp vec3 sRGB = proPhotoToSRGB * proPhoto;
    
    // Clamp negatives to prevent artifacts
    sRGB = max(sRGB, vec3(0.0));
    
    // Apply LCE (matches convert_uraw script order)
    // For SDR: restores local contrast that was flattened by HDR compression
    // For HDR: applies local contrast enhancement to uncompressed values
    sRGB = applyLCEToRGB(sRGB, xy);
    
    // Apply HDR-specific sigmoidal contrast to compensate for contrast flattening in HDR fusion
    // The contrast strength is already scaled based on baseline exposure in Java code:
    // - Low baseline (0.99 EV): very gentle contrast (~1.36)
    // - High baseline (6 EV): stronger contrast (~3.16)
    // Since it scales appropriately, we can apply it whenever fusion is enabled
    // Note: isNightMode is available for future night mode processing (referenced to prevent optimization)
    // NOTE: Sigmoidal contrast is skipped when matching embedded JPEG (histogramMatchApplied),
    // as the histogram matching already handles tone mapping to match the reference.
    // NOTE: Sigmoidal contrast is independent of tone curve - it compensates for HDR fusion contrast loss,
    // while tone curve handles tone mapping. They serve different purposes and shouldn't interfere.
    if (hdrSigmoidalContrast > 0.0) {
        // Reference isNightMode to prevent compiler optimization (future: implement night mode processing)
        if (isNightMode < 0) {}  // Dummy reference to prevent optimization
        // Blend sigmoidal contrast with original to reduce strength and prevent artifacts
        // This prevents the "negative sharpening" or extreme HDR look
        vec3 sigmoidalResult = applySigmoidalContrast(sRGB, hdrSigmoidalContrast, hdrSigmoidalMidpoint);
        // Apply at full strength - sigmoidal contrast is for HDR fusion compensation,
        // independent of tone curve (which handles tone mapping)
        sRGB = mix(sRGB, sigmoidalResult, 1.0);
    }
    
    // DEBUG: sRGB after sigmoidal contrast (DISABLED - was correct)
    // color = vec4(pow(clamp(sRGB, 0.0, 1.0), vec3(1.0/2.2)), 1.0); return;
    
    // Apply HDR-specific shadow lowering curve
    // Strength is controlled by Java code based on baseline exposure, so safe to apply when > 0
    if (hdrShadowAdjust > 0.0) {
        sRGB = applyHDRShadowAdjust(sRGB, hdrShadowAdjust);
    }
    
    // Clamp negatives only - preserve shadow detail, don't clip highlights yet
    // We'll clamp to [0,1] later after all tone adjustments
    sRGB = max(sRGB, vec3(0.0));

    // Apply DNG Profile HueSatMap if available
    // This applies the camera manufacturer's color signature
    if (hasProfileHueSatMap) {
        sRGB = applyHueSatMap(sRGB);
        // DEBUG: Check after HueSatMap
        // DEBUG: if (sRGB.r < 0.0 || sRGB.g < 0.0 || sRGB.b < 0.0) { color = vec4(0.8, 0.2, 0.0, 1.0); return; } // Dark orange = neg after HueSatMap
    }
    
    // Note: Profile/User ToneCurve is applied in tonemap() below
    // to avoid double-application
    
    // Apply DNG Profile LookTable if available
    // This is a 3D LUT for creative color grading
    if (hasProfileLookTable) {
        sRGB = applyLookTable(sRGB);
        // DEBUG: Check after LookTable
        // DEBUG: if (sRGB.r < 0.0 || sRGB.g < 0.0 || sRGB.b < 0.0) { color = vec4(0.8, 0.0, 0.2, 1.0); return; } // Dark red = neg after LookTable
    }
    
    // Note: External LUT is now applied in ProPhoto (linear) space before sRGB conversion
    // This ensures correct color space handling for .cube LUTs

    // Apply Lightroom-style tone adjustments AFTER compression (SDR) or directly (HDR) and color transform
    // This matches the old working version order
    sRGB = applyToneAdjustments(sRGB);

    // Apply per-hue saturation map (separate from global saturation slider)
    // Skip saturation boost when fusion is enabled (HDR processing already preserves saturation)
    // bool skipSaturationBoost = hdrSigmoidalContrast > 0.0;  // If fusion compensation is active, skip boost
    // sRGB = saturate(sRGB, skipSaturationBoost);

    // Apply the polynomial tonemap curve for final contrast
    sRGB = tonemap(sRGB);

    // Apply MAT mode processing if enabled (before separate highlight/shadow adjustments)
    // Leica-like and dreamy aesthetic: bloom, desaturation, shadow richness, vignette, hue shifts
    if (matMode) {
        // Apply subtle sigmoidal contrast
        // sRGB = applySigmoidalContrast(sRGB, 3.5, 0.5);
        
        // Calculate normalized UV coordinates for vignette (0-1 range)
        // Use gl_FragCoord relative to output dimensions
        // Note: We approximate using intermediate dimensions as proxy for output
        vec2 uv = vec2(gl_FragCoord.xy) / vec2(float(intermediateWidth), float(intermediateHeight));
        
        // Apply comprehensive MAT processing (bloom, desaturation, shadows, vignette, hue shifts)
        // Skip hue shifts and shadow richness when external LUT is used to avoid interference
        sRGB = applyMATProcessing(sRGB, uv, hasExternalLut);
    }
    
    // Apply Leica M9 mode processing if enabled
    // Emulates Kodak KAF-18500 CCD sensor: warm reds, film tonality, classic rendering
    if (leicaM9Mode) {
        vec2 uv = vec2(gl_FragCoord.xy) / vec2(float(intermediateWidth), float(intermediateHeight));
        sRGB = applyLeicaM9Processing(sRGB, uv);
    }
    
    // Apply reference preview tone matching AFTER all tone mapping
    // NOTE: Histogram matching is now done in xyY space by HistogramMatch stage
    // before ToneMap, so the intermediate xyY data already has histogram matching applied.
    // No additional tone matching is needed here.

    // Apply dithering in linear space BEFORE gamma encoding
    // This breaks up quantization early, preventing banding in smooth gradients
    sRGB = ditherLinear(sRGB, xy);

    // DEBUG: Check for negatives right before gamma encoding
    // DEBUG: ORANGE = negatives exist before gamma encoding
    // if ((sRGB.r < 0.0 || sRGB.g < 0.0 || sRGB.b < 0.0) && outputHDR == 0) { 
    //     color = vec4(1.0, 0.5, 0.0, 1.0); return; 
    // }
    
    // Gamma correct
    highp vec3 gammaEncoded = gammaCorrectPixel(sRGB);
    
    // Output HDR or SDR version based on uniform flag
    // HDR version: gamma-encoded but not clamped (may exceed 1.0)
    // SDR version: gamma-encoded and clamped to [0,1] with dithering
    
    // DEBUG: Check for extreme values - very high values (> 10) could cause issues
    // Output bright green where values are extremely high
    float maxGamma = max(max(gammaEncoded.r, gammaEncoded.g), gammaEncoded.b);
    // DEBUG: if (maxGamma > 10.0 && outputHDR == 0) { color = vec4(0.0, 1.0, 0.0, 1.0); return; } // Green = extreme values
    
    // DEBUG: Check for NaN - NaN causes undefined behavior
    if (gammaEncoded.r != gammaEncoded.r || gammaEncoded.g != gammaEncoded.g || gammaEncoded.b != gammaEncoded.b) {
        // color = vec4(1.0, 1.0, 0.0, 1.0); return; // Yellow = NaN detected
    }
    
    if (outputHDR > 0) {
        // HDR output: proPhoto is already uncompressed (compression was skipped), so no gain calculation needed
        // Convert gamma-encoded to linear and log-encode directly
        highp vec3 linearHDR = vec3(
            gammaDecode(gammaEncoded.r),
            gammaDecode(gammaEncoded.g),
            gammaDecode(gammaEncoded.b)
        );
        
        // Clamp negatives only - DON'T clamp to 1.0, we need HDR values!
        linearHDR = max(linearHDR, vec3(0.0));
        
        // Apply log encoding to preserve HDR values > 1.0 in 8-bit storage
        // Formula: log2(1 + x) / log2(1 + maxHDR) maps [0, maxHDR] to [0, 1]
        // Using maxHDR = 16.0 (4 stops above SDR white)
        // log2(17) ≈ 4.087
        const float LOG2_17 = 4.087462841;  // log2(1 + 16)
        const float INV_LOG2_17 = 1.0 / LOG2_17;
        
        // Log encode each channel: log2(1 + x) / log2(17)
        highp vec3 hdrOutput;
        hdrOutput.r = log2(1.0 + linearHDR.r) * INV_LOG2_17;
        hdrOutput.g = log2(1.0 + linearHDR.g) * INV_LOG2_17;
        hdrOutput.b = log2(1.0 + linearHDR.b) * INV_LOG2_17;
        
        // Now values are in [0, 1] range with HDR info preserved
        // SDR white (1.0) maps to ~0.244, HDR max (16.0) maps to 1.0
        color = vec4(hdrOutput, 1.0);
    } else {
        // SDR output: smooth rolloff to [0,1] instead of hard clamp to prevent banding
        color = vec4(smoothClampVec3(gammaEncoded, 0.0, 1.0, 0.001), 1.0);  // 0.1% transition zone - only affects extremes
    }
}
