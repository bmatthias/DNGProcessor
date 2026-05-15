#version 300 es

precision highp float;  // Use highp for precision to prevent banding in smooth gradients
precision mediump usampler2D;

#include sigmoid

uniform usampler2D rawBuffer;  // RGB16 input texture
uniform int rawWidth;
uniform int rawHeight;

uniform sampler2D gainMap;

// Sensor and picture variables
uniform vec4 blackLevel;  // Black level (same for all RGB channels)
uniform float whiteLevel; // White level of sensor

// DNG tone mapping hints (passed through, not applied here)
uniform float linearResponseLimit;
uniform float baselineExposure;
uniform int baselineExposureCompression;  // Compression method: 0=None, 1=Reinhard, 2=ACES Filmic, 3=Uncharted 2, 4=Improved Rational, 5=Gradient Domain, 6=Hejl-Dawson, 7=Modified ACES, 8=Reinhard-Jodie, 9=Lottes, 10=Gamma-Based, 11=Gamma+ACES Fusion, 12=Exposure Slider, 13=Sigmoidal, 14=Piecewise, 15=Histogram Match, 16=LibRaw exp_bef, 17=Exposure Fusion (HDR)

// Reference Preview Image (embedded JPEG from DNG) for histogram matching
uniform sampler2D histMatchLut;     // Histogram matching LUT (maps gamma-encoded luminance to target)
uniform bool hasReferencePreview;   // Whether a preview is available

// Out - normalized RGB, NO clipping, preserves full highlight range
// Note: must be vec4 because RGB16F is not color-renderable in GLES 3.0
out vec4 normalizedRgb;

// Helper function: simplified sigmoidal contrast for inline use
// Returns normalized sigmoid: maps [0,1] to [0,1] with S-curve
vec3 applySigmoidalContrastInline(vec3 x, float contrast, float midpoint) {
    vec3 result;
    for (int i = 0; i < 3; i++) {
        float val = x[i];
        // Normalized sigmoid function centered at midpoint
        float sigmoid_mid = 1.0 / (1.0 + exp(-contrast * (val - midpoint)));
        float sigmoid_0 = 1.0 / (1.0 + exp(-contrast * (0.0 - midpoint)));
        float sigmoid_1 = 1.0 / (1.0 + exp(-contrast * (1.0 - midpoint)));
        result[i] = (sigmoid_mid - sigmoid_0) / (sigmoid_1 - sigmoid_0);
        
        // Preserve very dark values to avoid crushing shadows
        if (val > 0.0 && val < 0.01) {
            float minOutput = val * 0.1;
            result[i] = max(result[i], minOutput);
        }
    }
    return result;
}

// Enhanced Reinhard with sigmoidal contrast - preserves highlights
// This approach:
// 1. Uses linear multiplication (avoids pow() precision issues that cause banding)
// 2. Uses standard Reinhard compression (guarantees y ≤ 1.0, prevents clipping)
// 3. Applies subtle sigmoidal contrast boost with highlight protection
// 4. Uses full baselineExposure multiplier and compresses to [0, 1]
vec3 applyBaselineExposureReinhard(vec3 rgb, float baselineExposure) {
    // Multiply by baselineExposure (NO input clamping - preserves highlights)
    vec3 multiplied = rgb * baselineExposure;
    
    // Apply standard Reinhard compression: x / (1 + x)
    vec3 reinhard = multiplied / (vec3(1.0) + multiplied);
    return reinhard;
    
    /*
    // Apply subtle sigmoidal contrast with highlight protection
    // Work in luminance space to preserve color
    float luma = dot(reinhard, vec3(0.2126, 0.7152, 0.0722));
    vec3 chroma = reinhard / max(vec3(luma), vec3(0.0001));
    
    // Calculate highlight protection (reduce effect above 0.7)
    float highlightProtection = smoothstep(0.7, 0.95, luma);
    
    // Apply sigmoidal contrast with subtle strength
    float contrastStrength = 2.0; // Subtle contrast (adjustable: 1.5-3.0)
    vec3 lumaVec = vec3(luma);
    vec3 sigmoidalLuma = applySigmoidalContrastInline(lumaVec, contrastStrength, 0.5);
    
    // Blend sigmoidal with original based on highlight protection
    // Full effect in mid-tones, reduced in highlights
    float enhancedLuma = mix(sigmoidalLuma.r, luma, highlightProtection * 0.7);
    
    // Reconstruct RGB
    vec3 result = vec3(enhancedLuma) * chroma;
    
    // Ensure output is strictly in [0, 1] range
    return result;*/
}

// Exposure slider approach: uses full baselineExposure multiplier
// This provides better midtone brightening than the sqrt approach
// Formula: y = (x * exposure) / (1.0 + x * (exposure - 1.0))
vec3 applyBaselineExposureSlider(vec3 rgb, float baselineExposure) {
    // Use exposure slider approach: y = (x * exposure) / (1.0 + x * (exposure - 1.0))
    // This provides better midtone brightening than sqrt(exposure) approach
    // The curve naturally compresses highlights while preserving shadow detail
    // NO input clamping - preserves highlights
    vec3 result = (rgb * baselineExposure) / (vec3(1.0) + rgb * (baselineExposure - 1.0));
    
    // Ensure output is strictly in [0, 1] range
    return result;
}

// ACES Filmic tone mapping - better contrast preservation than Reinhard
// ACES RRT + ODT approximation for natural highlight roll-off
// NO input clamping - preserves highlights
vec3 applyBaselineExposureACESFilmic(vec3 rgb, float baselineExposure) {
    vec3 x = rgb * baselineExposure;
    
    // ACES RRT + ODT approximation (simplified)
    // These constants approximate the ACES Reference Rendering Transform
    float a = 2.51;
    float b = 0.03;
    float c = 2.43;
    float d = 0.59;
    float e = 0.14;
    
    // ACES filmic curve: (x * (a * x + b)) / (x * (c * x + d) + e)
    vec3 result = (x * (a * x + b)) / (x * (c * x + d) + e);
    
    // Normalize so that x=baselineExposure maps to y=1.0
    // Maps full range to [0, 1.0]
    float maxX = baselineExposure;
    float maxY = (maxX * (a * maxX + b)) / (maxX * (c * maxX + d) + e);
    float targetMax = 1.0;  // Map full range to [0, 1.0]
    
    if (maxY > 0.001) {
        result = result * (targetMax / maxY);
    }
    
    return result;
}

// Uncharted 2 tone mapping - film-like response with configurable toe/shoulder
// HANDLES HDR INPUT: Normalizes input to preserve color ratios in highlights
vec3 applyBaselineExposureUncharted2(vec3 rgb, float baselineExposure) {
    // Handle HDR input: normalize to preserve color ratios
    // This prevents highlight color loss where all channels converge to asymptote
    float maxChannel = max(max(rgb.r, rgb.g), rgb.b);
    float hdrScale = max(maxChannel, 1.0);
    vec3 normalizedRgb = rgb / hdrScale;  // Now in [0,1] with preserved color ratios
    
    vec3 x = normalizedRgb * baselineExposure;
    
    // Uncharted 2 parameters
    float A = 0.15;  // Shoulder strength
    float B = 0.50;  // Linear strength
    float C = 0.10;  // Linear angle
    float D = 0.20;  // Toe strength
    float E = 0.02;  // Toe numerator
    float F = 0.30;  // Toe denominator
    
    // Uncharted 2 curve: ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F
    vec3 result = ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - vec3(E / F);
    
    // Normalize to [0, 1] range using standard white point
    float whiteScale = ((11.2 * (A * 11.2 + C * B) + D * E) / (11.2 * (A * 11.2 + B) + D * F)) - E / F;
    result = result / whiteScale;
    
    // For HDR highlights, blend toward white based on compressed excess
    if (hdrScale > 1.0) {
        float excess = hdrScale - 1.0;
        float compressedExcess = excess / (1.0 + excess);  // Reinhard on excess
        result = mix(result, vec3(1.0), compressedExcess * 0.3);
    }
    
    return result;
}

// Improved Rational compression - better than old smooth method
// Uses full exposure value and better parameter tuning
// NO input clamping - preserves highlights
vec3 applyBaselineExposureImprovedRational(vec3 rgb, float baselineExposure) {
    vec3 multiplied = rgb * baselineExposure;  // Use full exposure, not sqrt
    
    // Rational function: x / (a + (1-a)*x)
    // Adjust 'a' based on exposure for better contrast preservation
    float a = 1.0 / (1.0 + baselineExposure * 0.5);  // Better than sqrt-based
    
    // This rational function provides smooth compression
    // It brightens shadows more while compressing highlights smoothly
    vec3 result = multiplied / (a + (1.0 - a) * multiplied);
    
    return result;
}

// Hejl-Dawson tone mapping - better balance than ACES, preserves highlights better
// Provides good contrast preservation with softer highlight roll-off than ACES
// NO input clamping - preserves highlights
vec3 applyBaselineExposureHejlDawson(vec3 rgb, float baselineExposure) {
    vec3 x = rgb * baselineExposure;
    
    // Hejl-Dawson curve: (x - 0.004) * (6.2 * (x - 0.004) + 0.5) / ((x - 0.004) * (6.2 * (x - 0.004) + 1.7) + 0.06)
    vec3 xx = max(x - vec3(0.004), vec3(0.0));
    vec3 result = (xx * (vec3(6.2) * xx + vec3(0.5))) / (xx * (vec3(6.2) * xx + vec3(1.7)) + vec3(0.06));
    
    // Normalize so that x=baselineExposure maps to y=1.0
    // Maps full range to [0, 1.0]
    float maxX = baselineExposure;
    vec3 maxXX = max(vec3(maxX - 0.004), vec3(0.0));
    vec3 maxY = (maxXX * (vec3(6.2) * maxXX + vec3(0.5))) / (maxXX * (vec3(6.2) * maxXX + vec3(1.7)) + vec3(0.06));
    float targetMax = 1.0;  // Map full range to [0, 1.0]
    
    if (maxY.r > 0.001) {
        result = result * (targetMax / maxY.r);
    }
    
    return result;
}

// Modified ACES Filmic with softer shoulder for better highlight preservation
// Uses reduced 'a' and 'c' values and increased 'd' and 'e' for softer compression
// HANDLES HDR INPUT: Normalizes input to preserve color ratios in highlights
vec3 applyBaselineExposureACESFilmicSoft(vec3 rgb, float baselineExposure) {
    // Handle HDR input: normalize to preserve color ratios
    // This prevents highlight color loss where all channels converge to a/c asymptote
    float maxChannel = max(max(rgb.r, rgb.g), rgb.b);
    float hdrScale = max(maxChannel, 1.0);
    vec3 normalizedRgb = rgb / hdrScale;  // Now in [0,1] with preserved color ratios
    
    vec3 x = normalizedRgb * baselineExposure;
    
    // Modified ACES with softer shoulder - reduced 'a' and 'c' values
    float a = 2.0;   // Reduced from 2.51 for softer shoulder
    float b = 0.03;
    float c = 2.0;   // Reduced from 2.43 for softer shoulder
    float d = 0.65;  // Increased from 0.59 for better highlight roll-off
    float e = 0.18;  // Increased from 0.14 for softer compression
    
    // ACES filmic curve: (x * (a * x + b)) / (x * (c * x + d) + e)
    vec3 result = (x * (a * x + b)) / (x * (c * x + d) + e);
    
    // Normalize so that x=baselineExposure maps to y=1.0
    // Maps full range to [0, 1.0]
    float maxX = baselineExposure;
    float maxY = (maxX * (a * maxX + b)) / (maxX * (c * maxX + d) + e);
    float targetMax = 1.0;  // Map full range to [0, 1.0]
    
    if (maxY > 0.001) {
        result = result * (targetMax / maxY);
    }
    
    // For HDR highlights, blend toward white based on compressed excess
    if (hdrScale > 1.0) {
        float excess = hdrScale - 1.0;
        float compressedExcess = excess / (1.0 + excess);  // Reinhard on excess
        result = mix(result, vec3(1.0), compressedExcess * 0.3);
    }
    
    return result;
}

// Reinhard-Jodie - preserves more contrast than standard Reinhard
// Blends between standard Reinhard and a contrast-enhanced version based on luminance
// NO input clamping - preserves highlights
vec3 applyBaselineExposureReinhardJodie(vec3 rgb, float baselineExposure) {
    vec3 x = rgb * baselineExposure;
    
    // Standard Reinhard
    vec3 reinhard = x / (vec3(1.0) + x);
    
    // Jodie variant: adds contrast boost in mid-tones
    // Use a softer compression for the contrast boost: x / (1.0 + x * 0.5)
    vec3 contrastBoost = pow(x / (vec3(1.0) + x * 0.5), vec3(1.1));
    
    // Blend based on luminance - more contrast in mid-tones
    vec3 luma = vec3(dot(x, vec3(0.2126, 0.7152, 0.0722)));
    vec3 blendFactor = smoothstep(vec3(0.1), vec3(0.9), luma);
    vec3 result = mix(reinhard, contrastBoost, blendFactor * 0.4);
    
    // Normalize to ensure proper range
    // When rgb=1.0, x=baselineExposure
    float maxX = baselineExposure;
    float maxReinhard = maxX / (1.0 + maxX);
    float maxBoost = pow(maxX / (1.0 + maxX * 0.5), 1.1);
    float maxResult = mix(maxReinhard, maxBoost, 0.4);
    
    // Normalize so that maxInput maps to 1.0
    if (maxResult > 0.001) {
        result = result * (1.0 / maxResult);
    }
    
    return result;
}

// Lottes tone mapping - modern balanced approach with configurable parameters
// Provides good contrast preservation with natural highlight roll-off
// NO input clamping - preserves highlights
vec3 applyBaselineExposureLottes(vec3 rgb, float baselineExposure) {
    vec3 x = rgb * baselineExposure;
    
    // Lottes parameters
    float a = 1.6;
    float d = 0.977;
    float hdrMax = baselineExposure;
    float midIn = 0.18;
    float midOut = 0.267;
    
    // Lottes curve: pow(x, a) / (pow(x, a*d) * b + c)
    // Calculate b and c based on parameters (scalar values)
    float b = (-pow(midIn, a) + pow(hdrMax, a) * midOut) / 
              ((pow(hdrMax, a * d) - pow(midIn, a * d)) * midOut);
    float c = (pow(hdrMax, a * d) * pow(midIn, a) - 
               pow(hdrMax, a) * pow(midIn, a * d) * midOut) / 
              ((pow(hdrMax, a * d) - pow(midIn, a * d)) * midOut);
    
    vec3 result = pow(x, vec3(a)) / (pow(x, vec3(a * d)) * b + c);
    
    // Normalize to ensure output is in [0, 1] range
    // When x=hdrMax, result maps to 1.0
    float maxX = hdrMax;
    float maxResult = pow(maxX, a) / (pow(maxX, a * d) * b + c);
    float targetMax = 1.0;  // Map full range to [0, 1.0]
    
    if (maxResult > 0.001) {
        result = result * (targetMax / maxResult);
    }
    
    return result;
}

// Gamma-based baseline exposure compression (inspired by convert_uraw script)
// Converts baseline exposure to gamma, applies gamma correction, then compensates with sigmoidal contrast
// This approach matches the convert_uraw script's method:
// 1. Calculate gamma directly from baseline exposure (no mean assumption)
// 2. Apply gamma correction: pow(x, 1/gamma)
// 3. Calculate sigmoidal contrast slope to compensate for contrast loss from gamma (like script)
// 4. Apply sigmoidal contrast
// HANDLES HDR INPUT: Normalizes input, processes, then restores scale
// This preserves color relationships in HDR highlights
vec3 applyBaselineExposureGammaBased(vec3 rgb, float baselineExposure) {
    // Handle HDR input: normalize to [0,1] while preserving color ratios
    // This prevents highlight clipping to gray
    float maxChannel = max(max(rgb.r, rgb.g), rgb.b);
    float hdrScale = max(maxChannel, 1.0);  // Scale factor for HDR (1.0 if already in [0,1])
    vec3 normalizedInput = rgb / hdrScale;  // Now in [0,1] range with preserved ratios
    
    // Calculate gamma from baselineExposure multiplier to produce equivalent exposure lifting
    // baselineExposure is a multiplier (2^EV), not EV directly
    // We want pow(x, 1/gamma) to produce equivalent brightness to x * baselineExposure
    // 
    // Matching at mid-tone (x = 0.5): 0.5 * baselineExposure = pow(0.5, 1/gamma)
    // Solving: baselineExposure = pow(0.5, 1/gamma - 1) = 2^(1 - 1/gamma)
    // Therefore: log2(baselineExposure) = 1 - 1/gamma
    // Rearranging: 1/gamma = 1 - log2(baselineExposure)
    // So: gamma = 1 / (1 - log2(baselineExposure))
    // 
    // For baselineExposure = 1.0 (EV = 0): gamma = 1/(1-0) = 1.0 (correct, no change)
    // For baselineExposure = 2.0 (EV = 1): gamma = 1/(1-1) = inf (use large value)
    // For baselineExposure = 4.0 (EV = 2): gamma = 1/(1-2) = -1 (invalid)
    // 
    // Better approach: Convert multiplier to EV first, then use gamma = 1 + EV
    // EV = log2(baselineExposure), so gamma = 1 + log2(baselineExposure)
    // This gives:
    // - baselineExposure = 1.0 (EV=0): gamma = 1.0 → pow(x, 1.0) = x (correct)
    // - baselineExposure = 2.0 (EV=1): gamma = 2.0 → pow(x, 0.5) ≈ x * 1.41 (close to x * 2)
    // - baselineExposure = 8.0 (EV=3): gamma = 4.0 → pow(x, 0.25) ≈ x * 1.19 (close to x * 8)
    float ev = log2(baselineExposure);
    float cgamma_highlights = 1.0 + ev;
    
    // Clamp to reasonable range [0.5, 3.0]
    cgamma_highlights = clamp(cgamma_highlights, 0.5, 3.0);
    
    // Apply gamma correction: pow(x, 1/gamma)
    // This brightens the image similar to baseline exposure
    vec3 gammaCorrected = pow(normalizedInput, vec3(1.0 / cgamma_highlights));
    
    // Calculate sigmoidal contrast slope to compensate for contrast loss from gamma
    // Uses shared function with 1.8 factor, min/max constraints
    float contrastStrength = 1.0;
    if (cgamma_highlights > 1.0) {
        contrastStrength = calculateSigmoidalContrastFromGamma(cgamma_highlights);
        // Apply 3.3 multiplier
        contrastStrength *= 3.3;
    }
    contrastStrength = min(contrastStrength, 6.0);
    
    // Apply sigmoidal contrast with calculated strength
    // Use midpoint of 0.5 (middle gray)
    float midpoint = 0.5;
    vec3 result = applySigmoidalContrastInline(gammaCorrected, contrastStrength, midpoint);
    
    // For HDR highlights, compress the excess brightness
    // Instead of restoring full HDR scale, use Reinhard on the excess
    if (hdrScale > 1.0) {
        // Calculate how much brighter this pixel was (excess above 1.0 in original)
        float excess = hdrScale - 1.0;
        // Compress the excess using Reinhard: excess / (1 + excess)
        float compressedExcess = excess / (1.0 + excess);
        // Blend the result slightly toward white based on compressed excess
        // This gives bright highlights a gentle push without going fully white
        result = mix(result, vec3(1.0), compressedExcess * 0.3);
    }
    
    return result;
}

// ============================================================================
// NEW TONE MAPPING METHODS - WORK ON CLAMPED [0,1] INPUT
// ============================================================================
// These methods assume input is already clamped to [0,1] and work entirely
// in that space, preserving both contrast and highlights.

// Option 1: Sigmoidal Curve Approach
// Uses sigmoidal contrast to brighten and compress, similar to gamma-based
// but with a different approach to the brightening step
vec3 applyBaselineExposureSigmoidal(vec3 rgb, float baselineExposure) {
    // Input should be clamped to [0,1] before calling this function
    
    // First, brighten the image using a power curve
    // Higher baselineExposure = more brightening needed
    float brightenExponent = 1.0 / baselineExposure;
    brightenExponent = clamp(brightenExponent, 0.33, 1.0);  // Limit to reasonable range
    vec3 brightened = pow(rgb, vec3(brightenExponent));
    
    // Then apply sigmoidal contrast to restore contrast and compress highlights smoothly
    // Adjust contrast strength based on baselineExposure
    float contrastStrength = 2.0 + (baselineExposure - 1.0) * 1.5;
    contrastStrength = clamp(contrastStrength, 2.0, 6.0);
    float midpoint = 0.5;
    
    vec3 result = applySigmoidalContrastInline(brightened, contrastStrength, midpoint);
    
    return clamp(result, vec3(0.0), vec3(1.0));
}

// Option 2: Piecewise Linear with Smooth Shoulder
// Linear in mid-tones (preserves contrast), smooth compression in highlights
vec3 applyBaselineExposurePiecewise(vec3 rgb, float baselineExposure) {
    // Input should be clamped to [0,1] before calling this function
    
    // Brighten using power curve
    float brightenExponent = 1.0 / baselineExposure;
    brightenExponent = clamp(brightenExponent, 0.33, 1.0);
    vec3 brightened = pow(rgb, vec3(brightenExponent));
    
    // Apply piecewise curve: linear in mid-tones, smooth compression in highlights
    vec3 result;
    for (int i = 0; i < 3; i++) {
        float x = brightened[i];
        
        // Define transition point (where we switch from linear to compression)
        float transition = 0.7;
        
        if (x <= transition) {
            // Linear region: preserve contrast
            // Scale linearly to use most of the [0, transition] range
            result[i] = x * (0.95 / transition);  // Map [0, transition] to [0, 0.95]
        } else {
            // Compression region: smooth shoulder
            // Map [transition, 1.0] to [0.95, 1.0] using smooth curve
            float excess = x - transition;
            float maxExcess = 1.0 - transition;
            
            // Use smooth compression curve (sigmoid-like)
            float compressedExcess = excess / (1.0 + excess / maxExcess);
            result[i] = 0.95 + (compressedExcess / maxExcess) * 0.05;
        }
    }
    
    return clamp(result, vec3(0.0), vec3(1.0));
}

// Maximum HDR value - use 1024 (2^10) for reliable Float16 precision
// This aligns with Float16's 10-bit mantissa for best accuracy
const float HDR_MAX = 1024.0;

// Compress HDR excess: keeps [0,1] unchanged, compresses (1, ∞) → (1, max]
// Uses Reinhard-style compression on the excess above 1.0
vec3 compressHDRExcess(vec3 linear, float maxVal) {
    vec3 result;
    for (int i = 0; i < 3; i++) {
        float v = linear[i];
        if (v <= 1.0) {
            result[i] = v;  // SDR: unchanged
        } else {
            // Compress excess: (1, ∞) → (1, max]
            // excess / (1 + excess) maps (0, ∞) → (0, 1)
            // Scale by (max-1) and add 1 to get (1, max]
            float excess = v - 1.0;
            float compressedExcess = (maxVal - 1.0) * excess / (1.0 + excess);
            result[i] = 1.0 + compressedExcess;
        }
    }
    return result;
}

// Gamma+ACES Fusion: Combines three compression methods
// - Reinhard as base (balanced, most of the image)
// - ACES blended in to improve contrast (mid-tones/darker areas)
// - Gamma blended in to improve highlight retention (bright areas)
vec3 applyBaselineExposureGammaACESFusion(vec3 rgb, float baselineExposure) {
    // Apply all three methods
    vec3 reinhardResult = applyBaselineExposureReinhard(rgb, baselineExposure);
    vec3 acesResult = applyBaselineExposureACESFilmicSoft(rgb, baselineExposure);
    vec3 gammaResult = applyBaselineExposureGammaBased(rgb, baselineExposure);
    
    // Calculate luminance of input
    float inputLuminance = dot(reinhardResult, vec3(0.2126, 0.7152, 0.0722));
    
    // ACES weight: higher in mid-tones/darker areas where contrast is needed
    // Peak around 0.3-0.5 luminance for contrast boost
    float acesWeight = 0.0;
    if (inputLuminance < 0.5) {
        // Boost contrast in mid-tones and darker areas
        acesWeight = smoothstep(0.5, 0.2, inputLuminance) * 0.5;  // Max 50% ACES
    }
    
    // Gamma weight: higher in bright areas where highlight retention is needed
    // Peak in highlights (above 0.5 luminance)
    float gammaWeight = 0.0;
    if (inputLuminance > 0.5) {
        // Preserve highlights in bright areas
        gammaWeight = smoothstep(0.5, 1.0, inputLuminance) * 0.5;  // Max 50% Gamma
    }
    
    // Adjust Reinhard weight to accommodate ACES and Gamma
    // Ensure weights sum to 1.0
    float totalAdjustment = acesWeight + gammaWeight;

    float reinhardWeight = 1.0 - totalAdjustment;
    
    // Blend the three results
    vec3 result = reinhardResult * reinhardWeight + 
                  acesResult * acesWeight + 
                  gammaResult * gammaWeight;
    
    return result;
}

// Mertens-style exposure fusion: blends Gamma, Reinhard, and ACES RGB frames using full Mertens weights
// This avoids the division issue by blending RGB directly instead of scaling by luma ratio
// Parameters:
//   rgb: Original RGB input (can be HDR)
//   baselineExposure: Exposure multiplier
//   xy: Pixel coordinates (for neighbor access to compute contrast)
// Returns: Blended RGB using Mertens weights (contrast + saturation + well-exposedness)
vec3 applyBaselineExposureGammaACESMertensFusion(vec3 rgb, float baselineExposure, ivec2 xy) {
    // Create 3 tone-mapped RGB frames
    vec3 gammaRgb = applyBaselineExposureGammaBased(rgb, baselineExposure);
    vec3 reinhardRgb = applyBaselineExposureReinhard(rgb, baselineExposure);
    vec3 acesRgb = applyBaselineExposureACESFilmicSoft(rgb, baselineExposure);
    
    // Compute luma for each frame (for well-exposedness and contrast)
    float gammaLuma = dot(gammaRgb, vec3(0.2126, 0.7152, 0.0722));
    float reinhardLuma = dot(reinhardRgb, vec3(0.2126, 0.7152, 0.0722));
    float acesLuma = dot(acesRgb, vec3(0.2126, 0.7152, 0.0722));
    
    // Compute contrast using Laplacian on original RGB luma (simplified but effective)
    // For contrast computation, we use original luma as a proxy since contrast is about
    // detecting edges/relative differences, which are preserved in the original
    // Get neighbor coordinates
    ivec2 left = ivec2(max(xy.x - 1, 0), xy.y);
    ivec2 right = ivec2(min(xy.x + 1, rawWidth - 1), xy.y);
    ivec2 top = ivec2(xy.x, max(xy.y - 1, 0));
    ivec2 bottom = ivec2(xy.x, min(xy.y + 1, rawHeight - 1));
    
    // Get neighbor raw values
    uvec3 rawLeft = texelFetch(rawBuffer, left, 0).rgb;
    uvec3 rawRight = texelFetch(rawBuffer, right, 0).rgb;
    uvec3 rawTop = texelFetch(rawBuffer, top, 0).rgb;
    uvec3 rawBottom = texelFetch(rawBuffer, bottom, 0).rgb;
    
    // Compute original luma for center and neighbors (for contrast computation)
    float originalLuma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    float originalLumaLeft = dot(vec3(rawLeft) / whiteLevel, vec3(0.2126, 0.7152, 0.0722));
    float originalLumaRight = dot(vec3(rawRight) / whiteLevel, vec3(0.2126, 0.7152, 0.0722));
    float originalLumaTop = dot(vec3(rawTop) / whiteLevel, vec3(0.2126, 0.7152, 0.0722));
    float originalLumaBottom = dot(vec3(rawBottom) / whiteLevel, vec3(0.2126, 0.7152, 0.0722));
    
    // Compute contrast from original luma (Laplacian: |4*center - sum(neighbors)|)
    // This is a good approximation since contrast measures local variation,
    // which is preserved regardless of tone mapping
    float originalContrast = abs(4.0 * originalLuma - (originalLumaLeft + originalLumaRight + originalLumaTop + originalLumaBottom));
    
    // Use the same contrast value for all three frames (reasonable approximation)
    // The relative differences between frames are captured by well-exposedness
    float contrastGamma = originalContrast;
    float contrastReinhard = originalContrast;
    float contrastAces = originalContrast;
    
    // Add epsilon to avoid zero weights
    const float contrastEps = 0.001;
    contrastGamma += contrastEps;
    contrastReinhard += contrastEps;
    contrastAces += contrastEps;
    
    // Compute saturation from original RGB
    // Saturation = distance from gray line (standard deviation of RGB channels)
    float rgbMean = (rgb.r + rgb.g + rgb.b) / 3.0;
    vec3 chroma = rgb - vec3(rgbMean);
    float saturation = length(chroma) + 0.01;  // Add epsilon
    
    // Compute well-exposedness weights (Gaussian centered at 0.5)
    float sigma = 0.2;
    float wellExpGamma = exp(-pow(gammaLuma - 0.5, 2.0) / (2.0 * sigma * sigma));
    float wellExpReinhard = exp(-pow(reinhardLuma - 0.5, 2.0) / (2.0 * sigma * sigma));
    float wellExpAces = exp(-pow(acesLuma - 0.5, 2.0) / (2.0 * sigma * sigma));
    
    // Mertens formula: W = C^alpha * S^beta * E^gamma
    // Using exponents = 1.0 (standard Mertens)
    float alpha = 1.0;
    float beta = 1.0;
    float gamma = 1.0;
    
    float wGamma = pow(contrastGamma, alpha) * pow(saturation, beta) * pow(wellExpGamma, gamma);
    float wReinhard = pow(contrastReinhard, alpha) * pow(saturation, beta) * pow(wellExpReinhard, gamma);
    float wAces = pow(contrastAces, alpha) * pow(saturation, beta) * pow(wellExpAces, gamma);
    
    // Normalize weights to sum to 1.0 (ensure exact sum to avoid dithering)
    float wTotal = wGamma + wReinhard + wAces;
    if (wTotal < 0.001) {
        // Fallback to equal weights
        wGamma = wReinhard = wAces = 1.0 / 3.0;
    } else {
        float invTotal = 1.0 / wTotal;
        wGamma *= invTotal;
        wReinhard *= invTotal;
        wAces = 1.0 - wGamma - wReinhard;  // Ensure exact sum
    }
    
    // Blend RGB frames directly (no division needed!)
    vec3 result = wGamma * gammaRgb + wReinhard * reinhardRgb + wAces * acesRgb;
    
    return result;
}

// Gradient-aware baseline exposure compression
// Computes gradients from neighboring normalized RGB values and applies gradient-aware compression
vec3 applyBaselineExposureGradientDomain(vec3 rgb, float baselineExposure, ivec2 xy, 
        float gain, float bl, float whiteLevel) {
    // Sample neighbors from rawBuffer
    ivec2 rightXY = ivec2(min(xy.x + 1, rawWidth - 1), xy.y);
    ivec2 leftXY = ivec2(max(xy.x - 1, 0), xy.y);
    ivec2 topXY = ivec2(xy.x, min(xy.y + 1, rawHeight - 1));
    ivec2 bottomXY = ivec2(xy.x, max(xy.y - 1, 0));
    
    // Get raw RGB values for neighbors
    uvec3 rightRaw = texelFetch(rawBuffer, rightXY, 0).rgb;
    uvec3 leftRaw = texelFetch(rawBuffer, leftXY, 0).rgb;
    uvec3 topRaw = texelFetch(rawBuffer, topXY, 0).rgb;
    uvec3 bottomRaw = texelFetch(rawBuffer, bottomXY, 0).rgb;
    
    // Normalize neighbors (using same gain/blacklevel as center)
    float range = whiteLevel - bl;
    vec3 rightNorm = gain * (vec3(rightRaw) - bl) / range;
    vec3 leftNorm = gain * (vec3(leftRaw) - bl) / range;
    vec3 topNorm = gain * (vec3(topRaw) - bl) / range;
    vec3 bottomNorm = gain * (vec3(bottomRaw) - bl) / range;
    
    rightNorm = max(rightNorm, vec3(0.0));
    leftNorm = max(leftNorm, vec3(0.0));
    topNorm = max(topNorm, vec3(0.0));
    bottomNorm = max(bottomNorm, vec3(0.0));
    
    // Convert to luminance for gradient computation
    float centerLuma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    float rightLuma = dot(rightNorm, vec3(0.2126, 0.7152, 0.0722));
    float leftLuma = dot(leftNorm, vec3(0.2126, 0.7152, 0.0722));
    float topLuma = dot(topNorm, vec3(0.2126, 0.7152, 0.0722));
    float bottomLuma = dot(bottomNorm, vec3(0.2126, 0.7152, 0.0722));
    
    // Compute gradients
    float gradX = (rightLuma - leftLuma) * 0.5;
    float gradY = (topLuma - bottomLuma) * 0.5;
    float gradMag = sqrt(gradX * gradX + gradY * gradY);
    
    // Apply baseline exposure to center value
    vec3 multiplied = rgb * baselineExposure;
    
    // Compression threshold - larger gradients get compressed more
    float threshold = 0.1 * baselineExposure;
    float compressedGradMag = gradMag / (threshold + gradMag);
    
    // Apply Reinhard compression as base
    vec3 compressed = multiplied / (vec3(1.0) + multiplied);
    
    // Blend between compressed and original based on gradient strength
    // Strong gradients (edges): preserve more original detail
    // Weak gradients (smooth): use more compression
    float edgeStrength = min(gradMag * 10.0, 1.0);  // Normalize gradient magnitude
    vec3 result = mix(compressed, multiplied, edgeStrength * 0.3);  // Blend up to 30% original at edges
    
    // Normalize to ensure [0, 1] output
    // When rgb=1.0, multiplied=baselineExposure, compressed≈1.0 (for large baselineExposure)
    // The blended result might exceed 1.0, so we need to normalize
    // Compute what the maximum would be: when rgb=1.0, multiplied=baselineExposure
    float maxInput = baselineExposure;
    float maxCompressed = maxInput / (1.0 + maxInput);
    float maxBlended = mix(maxCompressed, maxInput, edgeStrength * 0.3);
    
    // Normalize so that maxInput maps to 1.0
    if (maxBlended > 0.001) {
        result = result / maxBlended;
    }
    
    return result;
}

// ============================================================================
// HISTOGRAM MATCHING BASELINE EXPOSURE COMPRESSION
// ============================================================================
// Uses the embedded JPEG preview as a reference to guide baseline exposure compression.
// 
// IMPORTANT: Space conversion considerations:
// - Input: Linear RGB values after baseline exposure multiplication [0, baselineExposure+]
// - Reference JPEG: Gamma-encoded sRGB, already tone-mapped/compressed [0, 1]
// - LUT: Maps gamma-encoded luminance [0, 1] to target gamma-encoded luminance [0, 1]
//
// The approach:
// 1. First normalize linear values to [0, 1] by dividing by baselineExposure
// 2. Convert to gamma space (to match the reference JPEG's encoding)
// 3. Look up target luminance from LUT (both input and output in gamma space)
// 4. Apply ratio adjustment while preserving color ratios
// 5. Result is in [0, 1] gamma space - convert back to linear if needed
//
// Note: The result stays in [0, 1] because we're matching the distribution of the
// already-compressed reference JPEG, effectively applying the camera's tone mapping.

// Simple gamma encode (approximate sRGB)
float gammaEncode(float linear) {
    return pow(max(linear, 0.0), 1.0 / 2.2);
}

// Simple gamma decode (approximate sRGB)  
float gammaDecode(float encoded) {
    return pow(max(encoded, 0.0), 2.2);
}

// Mertens-style well-exposedness weight
// Values near 0.5 (mid-gray) are considered "well exposed"
// Reused from stage4_5_merge_3frame.glsl for consistency
float wellExposednessWeight(float luma, float sigma) {
    float diff = luma - 0.5;
    return exp(-(diff * diff) / (2.0 * sigma * sigma));
}

vec3 applyBaselineExposureHistogramMatch(vec3 rgb, float baselineExposure) {
    // Step 1: Multiply by baseline exposure (linear brightening)
    vec3 multiplied = rgb * baselineExposure;
    
    // Step 2: Calculate luminance in linear space
    float linearLuma = dot(multiplied, vec3(0.2126, 0.7152, 0.0722));
    
    // Avoid division by zero for very dark pixels
    if (linearLuma < 0.0001) {
        // For very dark pixels, just return the multiplied value normalized
        return multiplied / baselineExposure;
    }
    
    // Step 3: Normalize luminance to [0, 1] range
    // After baselineExposure multiplication, "normal white" (input=1.0) is at baselineExposure
    // So we normalize by dividing by baselineExposure
    float normalizedLuma = linearLuma / baselineExposure;
    normalizedLuma = clamp(normalizedLuma, 0.0, 1.0);  // Clamp for LUT lookup
    
    // Step 4: Convert to gamma space to match the reference JPEG's encoding
    // The reference JPEG is gamma-encoded sRGB, so we need to match in that space
    float gammaLuma = gammaEncode(normalizedLuma);
    
    // Step 5: Look up target luminance from histogram matching LUT
    // The LUT maps gamma-encoded input luminance to gamma-encoded target luminance
    // that matches the reference JPEG's distribution
    float targetGammaLuma = texture(histMatchLut, vec2(gammaLuma, 0.5)).r;
    
    // Step 6: Calculate adjustment ratio in gamma space
    float gammaRatio = 1.0;
    if (gammaLuma > 0.0001) {
        gammaRatio = targetGammaLuma / gammaLuma;
        // Limit extreme adjustments to prevent artifacts
        gammaRatio = clamp(gammaRatio, 0.3, 3.0);
    }
    
    // Step 7: Apply the adjustment in gamma space
    // Convert the normalized linear RGB to gamma space
    vec3 normalizedRGB = multiplied / baselineExposure;
    normalizedRGB = clamp(normalizedRGB, vec3(0.0), vec3(1.0));
    vec3 gammaRGB = vec3(
        gammaEncode(normalizedRGB.r),
        gammaEncode(normalizedRGB.g),
        gammaEncode(normalizedRGB.b)
    );
    
    // Apply the luminance ratio to preserve color while matching distribution
    vec3 adjustedGammaRGB = gammaRGB * gammaRatio;
    
    // Step 8: Convert back to linear space
    // The result is now in [0, 1] range matching the reference JPEG's distribution
    vec3 result = vec3(
        gammaDecode(adjustedGammaRGB.r),
        gammaDecode(adjustedGammaRGB.g),
        gammaDecode(adjustedGammaRGB.b)
    );
    
    // Clamp to [0, 1] - we're matching the compressed JPEG, so values should be in this range
    return clamp(result, vec3(0.0), vec3(1.0));
}

// ============================================================================
// EXPOSURE FUSION - HDR OUTPUT
// ============================================================================
// Fuses three virtual exposures similar to stage4_1_doubleexpose.glsl:
// - Underexposed: original input (preserves highlights, no modification)
// - Overexposed: input multiplied by baselineExposure (reveals shadows)
// - Center: GammaACESFusion tone-mapped (balanced color/contrast)
//
// Unlike other compression methods, this outputs HDR values that can exceed 1.0
// This preserves the full dynamic range from baselineExposure multiplication
// while using exposure fusion to blend highlight and shadow detail.
vec3 applyBaselineExposureExposureFusion(vec3 rgb, float baselineExposure) {
    // === Create three exposures ===
    
    // 1. Underexposed: original input (preserves highlights)
    // This is the "dark" exposure that preserves highlight detail
    // No modification - this is the baseline that preserves the original dynamic range
    vec3 under = rgb;
    
    // 2. Overexposed: linear multiplication by baselineExposure
    // This brightens the image to reveal shadow detail
    // Full brightness, may have extreme values in highlights
    vec3 over = rgb * baselineExposure;
    
    // 3. Center: tone-mapped using GammaACESFusion
    // Balanced exposure with good contrast, typically in [0,1] range
    // This provides a well-exposed mid-tone reference
    vec3 center = applyBaselineExposureGammaACESFusion(rgb, baselineExposure);
    
    // === Calculate luminance for each exposure ===
    // Use luminance for weight calculation (same as stage4_5_merge_3frame.glsl)
    float underLuma = dot(under, vec3(0.2126, 0.7152, 0.0722));
    float overLuma = dot(over, vec3(0.2126, 0.7152, 0.0722));
    float centerLuma = dot(center, vec3(0.2126, 0.7152, 0.0722));
    
    // === Mertens-style well-exposedness weights ===
    // Reuse the exact same logic as stage4_5_merge_3frame.glsl
    // Values near 0.5 (mid-gray) are considered "well exposed"
    float sigma = 0.25;
    
    // Calculate well-exposedness weights for each frame
    float wUnder = wellExposednessWeight(underLuma, sigma);
    float wCenter = wellExposednessWeight(centerLuma, sigma);
    float wOver = wellExposednessWeight(overLuma, sigma);
    
    // === Region-specific weight adjustments ===
    // Reuse the exact same logic as stage4_5_merge_3frame.glsl
    // Boost weights based on the ORIGINAL luminance (before any modifications)
    // - Highlights (bright original): boost underexposed weight (preserves detail)
    // - Shadows (dark original): boost overexposed weight (reveals detail)
    // - Mid-tones: center exposure is the reference
    float inputLuma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    
    // Boost underexposed weight in highlight regions
    // Under preserves highlight detail (it's the original input)
    if (inputLuma > 0.5) {
        float highlightBoost = smoothstep(0.5, 1.0, inputLuma);
        wUnder *= 1.0 + highlightBoost * 1.5;  // Up to 2.5x boost in bright highlights
    }
    
    // Boost overexposed weight in shadow regions
    // Over reveals shadow detail (multiplied by baselineExposure)
    if (inputLuma < 0.3) {
        float shadowBoost = smoothstep(0.3, 0.0, inputLuma);
        wOver *= 1.0 + shadowBoost * 1.5;  // Up to 2.5x boost in deep shadows
    }
    
    // Center is our balanced reference - slight boost for stability
    wCenter *= 1.2;
    
    // Ensure minimum weights to avoid division issues
    float minWeight = 0.01;
    wUnder = max(wUnder, minWeight);
    wCenter = max(wCenter, minWeight);
    wOver = max(wOver, minWeight);
    
    // Normalize weights to sum to 1.0
    float wTotal = wUnder + wCenter + wOver;
    wUnder /= wTotal;
    wCenter /= wTotal;
    wOver /= wTotal;
    
    // === Weighted blend of the three exposures ===
    vec3 result = under * wUnder + over * wOver + center * wCenter;
    
    // === HDR output ===
    // Don't clamp to [0,1] - preserve full dynamic range
    // Only clamp negatives
    return max(result, vec3(0.0));
}

// LibRaw exp_bef compression method
// Implements LibRaw's exposure correction with highlight preservation
// Uses cubic curve compression similar to LibRaw's exp_bef function
// This method applies exposure shift with smooth highlight compression
vec3 applyBaselineExposureLibRawExpBef(vec3 rgb, float baselineExposure) {
    // NO input clamping - preserves highlights
    float shift = baselineExposure;
    float smoothness = 0.0;  // LibRaw's exp_preser parameter (0.0 = no preservation, 1.0 = full)
    
    // Clamp shift to LibRaw's limits [0.25, 8.0]
    shift = clamp(shift, 0.25, 8.0);
    smoothness = clamp(smoothness, 0.0, 1.0);
    
    vec3 x = rgb;
    vec3 result;
    
    if (shift <= 1.0) {
        // Simple linear scaling for darkening
        result = x * shift;
    } else {
        // Cubic compression curve for lightening (shift > 1.0)
        float TBLN = 1.0;  // Normalized maximum (we work in [0,1] space)
        
        float cstops = log(shift) / log(2.0);
        float room = cstops * 2.0;
        float roomlin = pow(2.0, room);
        float x2 = TBLN;
        float x1 = (x2 + 1.0) / roomlin - 1.0;
        float y1 = x1 * shift;
        float y2 = x2 * (1.0 + (1.0 - smoothness) * (shift - 1.0));
        
        // Cubic curve parameters
        float sq3x = pow(x1 * x1 * x2, 1.0 / 3.0);
        float B = (y2 - y1 + shift * (3.0 * x1 - 3.0 * sq3x)) / 
                  (x2 + 2.0 * x1 - 3.0 * sq3x);
        float A = (shift - B) * 3.0 * pow(x1 * x1, 1.0 / 3.0);
        float CC = y2 - A * pow(x2, 1.0 / 3.0) - B * x2;
        
        // Apply curve per channel
        for (int i = 0; i < 3; i++) {
            float Y;
            if (x[i] < x1) {
                // Linear region
                Y = x[i] * shift;
            } else {
                // Cubic compression region
                Y = A * pow(x[i], 1.0 / 3.0) + B * x[i] + CC;
            }
            result[i] = clamp(Y, 0.0, 1.0);
        }
    }
    
    return result;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);

    // Read RGB values from 16-bit texture
    uvec3 rawRgb = texelFetch(rawBuffer, xy, 0).rgb;
    vec3 rgb = vec3(rawRgb);

    // Apply gain map for lens shading correction
    vec2 xyInterp = vec2(float(xy.x) / float(rawWidth), float(xy.y) / float(rawHeight));
    vec4 gains = texture(gainMap, xyInterp);
    float gain = (gains.x + gains.y + gains.z + gains.w) * 0.25;

    // Apply black level subtraction and normalization
    float bl = blackLevel.x;
    float range = whiteLevel - bl;

    // Normalize each channel with the averaged gain
    // Output can exceed 1.0 for HDR headroom when baseline exposure is applied
    rgb = gain * (rgb - bl) / range;
    
    // Apply baseline exposure with compression
    if (baselineExposure > 1.0) {
        // Select compression method based on preference
        // 0=None, 1=Reinhard, 2=ACES Filmic, 3=Uncharted 2, 4=Improved Rational,
        // 5=Gradient Domain, 6=Hejl-Dawson, 7=Modified ACES, 8=Reinhard-Jodie,
        // 9=Lottes, 10=Gamma-Based, 11=Gamma+ACES Fusion, 12=Exposure Slider,
        // 13=Sigmoidal, 14=Piecewise, 15=Histogram Match, 16=LibRaw exp_bef,
        // 17=Exposure Fusion (HDR output)
        if (baselineExposureCompression == 0) {
            // None: no compression, just multiply (may exceed 1.0 to preserve highlights)
            rgb = rgb * baselineExposure;
        } else if (baselineExposureCompression == 1) {
            rgb = applyBaselineExposureReinhard(rgb, baselineExposure);
        } else if (baselineExposureCompression == 2) {
            rgb = applyBaselineExposureACESFilmic(rgb, baselineExposure);
        } else if (baselineExposureCompression == 3) {
            rgb = applyBaselineExposureUncharted2(rgb, baselineExposure);
        } else if (baselineExposureCompression == 4) {
            rgb = applyBaselineExposureImprovedRational(rgb, baselineExposure);
        } else if (baselineExposureCompression == 5) {
            rgb = applyBaselineExposureGradientDomain(rgb, baselineExposure, xy, gain, bl, whiteLevel);
        } else if (baselineExposureCompression == 6) {
            rgb = applyBaselineExposureHejlDawson(rgb, baselineExposure);
        } else if (baselineExposureCompression == 7) {
            rgb = applyBaselineExposureACESFilmicSoft(rgb, baselineExposure);
        } else if (baselineExposureCompression == 8) {
            rgb = applyBaselineExposureReinhardJodie(rgb, baselineExposure);
        } else if (baselineExposureCompression == 9) {
            rgb = applyBaselineExposureLottes(rgb, baselineExposure);
        } else if (baselineExposureCompression == 10) {
            rgb = applyBaselineExposureGammaBased(rgb, baselineExposure);
        } else if (baselineExposureCompression == 11) {
            rgb = applyBaselineExposureGammaACESMertensFusion(rgb, baselineExposure, xy);
        } else if (baselineExposureCompression == 12) {
            rgb = applyBaselineExposureSlider(rgb, baselineExposure);
        } else if (baselineExposureCompression == 13) {
            rgb = applyBaselineExposureSigmoidal(rgb, baselineExposure);
        } else if (baselineExposureCompression == 14) {
            rgb = applyBaselineExposurePiecewise(rgb, baselineExposure);
        } else if (baselineExposureCompression == 15) {
            // Histogram matching - uses embedded JPEG preview to guide compression
            if (hasReferencePreview) {
                rgb = applyBaselineExposureHistogramMatch(rgb, baselineExposure);
            } else {
                // Fallback to Gamma+ACES Fusion if no preview available
                rgb = applyBaselineExposureGammaACESFusion(rgb, baselineExposure);
            }
        } else if (baselineExposureCompression == 16) {
            rgb = applyBaselineExposureLibRawExpBef(rgb, baselineExposure);
        } else if (baselineExposureCompression == 17) {
            // Exposure Fusion: will be handled by EarlyExposureFusion stage
            // Return original RGB (no baseline exposure) - fusion will create frames and blend
            // EarlyExposureFusion supports both Mertens-style and Laplacian pyramid blending
            // rgb stays unchanged (original input)
            // keep this for debugging purposes:
            // rgb = applyBaselineExposureExposureFusion(rgb, baselineExposure);
        } else {
            // Fallback to Gamma+ACES Fusion for unknown values
            rgb = applyBaselineExposureGammaACESFusion(rgb, baselineExposure);
        }
    } else if (baselineExposure < 1.0) {
        // Darkening: use power curve (darkens highlights more than shadows)
        // Use full baselineExposure value for the curve exponent
        float curveExponent = baselineExposure;
        // Clamp to [0, 1] for power curve to avoid precision issues
        rgb = pow(clamp(rgb, vec3(0.0), vec3(1.0)), vec3(curveExponent));
    }
    
    // Clamp negatives
    rgb = max(rgb, vec3(0.0));
    
    // KEY INSIGHT: If ANY channel is Inf OR NaN, treat entire pixel as maximum brightness
    // This prevents colored artifacts from partial channel issues
    // (e.g., NaN in R channel would otherwise become 0, giving blue highlights)
    if (isinf(rgb.r) || isinf(rgb.g) || isinf(rgb.b) ||
        isnan(rgb.r) || isnan(rgb.g) || isnan(rgb.b)) {
        // After Reinhard compression, values should be in [0,1], so output with alpha=1.0
        // to avoid HDR encoding quantization when values are already compressed
        normalizedRgb = vec4(1.0, 1.0, 1.0, 1.0);
        return;
    }
    
    // HDR ENCODING: Only encode if values exceed 1.0 to avoid quantization
    // If values are already in [0,1], skip encoding to preserve precision
    float maxChannel = max(max(rgb.r, rgb.g), rgb.b);
    
    if (maxChannel <= 1.0) {
        // Values already in [0,1] - no encoding needed, just pass through
        // Use alpha=1.0 as marker that no scaling was applied
        normalizedRgb = vec4(rgb, 1.0);
    } else {
        // HDR values - encode with per-pixel scaling
        float scaleFactor = maxChannel;
        vec3 enc = rgb / scaleFactor;
        float encAlpha = 1.0 / scaleFactor;
        normalizedRgb = vec4(enc, encAlpha);
    }
}

