#version 300 es

precision mediump float;
precision mediump usampler2D;

#include sigmoid

uniform usampler2D rawBuffer;
uniform int rawWidth;
uniform int rawHeight;

uniform sampler2D gainMap;
uniform usampler2D hotPixels;
uniform ivec2 hotPixelsSize;

// Sensor and picture variables
uniform int cfaPattern; // The Color Filter Arrangement pattern used
uniform vec4 blackLevel; // Blacklevel to subtract for each channel, given in CFA order
uniform float whiteLevel; // Whitelevel of sensor

// DNG tone mapping hints
uniform float linearResponseLimit;  // Fraction of max that is linear (0.0-1.0)
uniform float baselineExposure;     // Exposure multiplier (2^EV): 0 EV = 1.0 (identity), 1 EV = 2.0 (doubles)
uniform int baselineExposureCompression;  // Compression method: 0=None, 1=Reinhard, 2=ACES Filmic, 3=Uncharted 2, 4=Improved Rational, 5=Gradient Domain, 6=Hejl-Dawson, 7=Modified ACES, 8=Reinhard-Jodie, 9=Lottes, 10=Gamma-Based, 11=Gamma+ACES Fusion, 12=Exposure Slider, 13=Sigmoidal, 14=Piecewise, 15=Histogram Match, 16=LibRaw exp_bef, 17=Exposure Fusion (HDR)

// Reference Preview Image (embedded JPEG from DNG) for histogram matching
uniform sampler2D histMatchLut;     // Histogram matching LUT (maps gamma-encoded luminance to target)
uniform bool hasReferencePreview;   // Whether a preview is available

// Out (compressed to [0, 1] via baseline exposure falling curve)
out float intermediate;

// Helper function: simplified sigmoidal contrast for inline use
// Returns normalized sigmoid: maps [0,1] to [0,1] with S-curve
float applySigmoidalContrastInline(float x, float contrast, float midpoint) {
    // Normalized sigmoid function centered at midpoint
    float sigmoid_mid = 1.0 / (1.0 + exp(-contrast * (x - midpoint)));
    float sigmoid_0 = 1.0 / (1.0 + exp(-contrast * (0.0 - midpoint)));
    float sigmoid_1 = 1.0 / (1.0 + exp(-contrast * (1.0 - midpoint)));
    float result = (sigmoid_mid - sigmoid_0) / (sigmoid_1 - sigmoid_0);
    
    // Preserve very dark values to avoid crushing shadows
    if (x > 0.0 && x < 0.01) {
        float minOutput = x * 0.1;
        result = max(result, minOutput);
    }
    return result;
}

// Enhanced Reinhard with sigmoidal contrast - preserves highlights
// This approach:
// 1. Uses linear multiplication (avoids pow() precision issues that cause banding)
// 2. Uses standard Reinhard compression (guarantees y ≤ 1.0, prevents clipping)
// 3. Applies subtle sigmoidal contrast boost with highlight protection
// 4. Uses full baselineExposure multiplier and compresses to [0, 1]
float applyBaselineExposureReinhard(float normalized, float baselineExposure) {
    // Multiply by baselineExposure (NO input clamping - preserves highlights)
    float multiplied = normalized * baselineExposure;
    
    // Apply standard Reinhard compression: x / (1 + x)
    float reinhard = multiplied / (1.0 + multiplied);
    
    return reinhard;
    
    /*
    // Apply subtle sigmoidal contrast with highlight protection
    // Calculate highlight protection (reduce effect above 0.7)
    float highlightProtection = smoothstep(0.7, 0.95, reinhard);
    
    // Apply sigmoidal contrast with subtle strength
    float contrastStrength = 2.0; // Subtle contrast (adjustable: 1.5-3.0)
    float sigmoidal = applySigmoidalContrastInline(reinhard, contrastStrength, 0.5);
    
    // Blend sigmoidal with original based on highlight protection
    // Full effect in mid-tones, reduced in highlights
    float result = mix(sigmoidal, reinhard, highlightProtection * 0.7);
    
    // Ensure output is strictly in [0, 1] range
    return clamp(result, 0.0, 1.0);*/
}

// Exposure slider approach: uses full baselineExposure multiplier
// This provides better midtone brightening than the sqrt approach
// Formula: y = (x * exposure) / (1.0 + x * (exposure - 1.0))
float applyBaselineExposureSlider(float normalized, float baselineExposure) {
    // Use exposure slider approach: y = (x * exposure) / (1.0 + x * (exposure - 1.0))
    // This provides better midtone brightening than sqrt(exposure) approach
    // The curve naturally compresses highlights while preserving shadow detail
    // NO input clamping - preserves highlights
    float result = (normalized * baselineExposure) / (1.0 + normalized * (baselineExposure - 1.0));
    
    // Ensure output is strictly in [0, 1] range
    return result;
}

// ACES Filmic tone mapping - better contrast preservation than Reinhard
// ACES RRT + ODT approximation for natural highlight roll-off
float applyBaselineExposureACESFilmic(float normalized, float baselineExposure) {
    // NO input clamping - preserves highlights
    float x = normalized * baselineExposure;
    
    // ACES RRT + ODT approximation (simplified)
    // These constants approximate the ACES Reference Rendering Transform
    float a = 2.51;
    float b = 0.03;
    float c = 2.43;
    float d = 0.59;
    float e = 0.14;
    
    // ACES filmic curve: (x * (a * x + b)) / (x * (c * x + d) + e)
    float result = (x * (a * x + b)) / (x * (c * x + d) + e);
    
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
// HANDLES HDR INPUT: Normalizes input to preserve values in highlights
float applyBaselineExposureUncharted2(float normalized, float baselineExposure) {
    // Handle HDR input: normalize to preserve values
    // This prevents highlight loss where values converge to asymptote
    float hdrScale = max(normalized, 1.0);
    float normalizedInput = normalized / hdrScale;  // Now in [0,1] with preserved ratios
    
    float x = normalizedInput * baselineExposure;
    
    // Uncharted 2 parameters
    float A = 0.15;  // Shoulder strength
    float B = 0.50;  // Linear strength
    float C = 0.10;  // Linear angle
    float D = 0.20;  // Toe strength
    float E = 0.02;  // Toe numerator
    float F = 0.30;  // Toe denominator
    
    // Uncharted 2 curve: ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F
    float result = ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
    
    // Normalize to [0, 1] range using standard white point
    float whiteScale = ((11.2 * (A * 11.2 + C * B) + D * E) / (11.2 * (A * 11.2 + B) + D * F)) - E / F;
    result = result / whiteScale;
    
    // For HDR highlights, blend toward white based on compressed excess
    if (hdrScale > 1.0) {
        float excess = hdrScale - 1.0;
        float compressedExcess = excess / (1.0 + excess);  // Reinhard on excess
        result = mix(result, 1.0, compressedExcess * 0.3);
    }
    
    return result;
}

// Improved Rational compression - better than old smooth method
// Uses full exposure value and better parameter tuning
float applyBaselineExposureImprovedRational(float normalized, float baselineExposure) {
    // NO input clamping - preserves highlights
    float multiplied = normalized * baselineExposure;  // Use full exposure, not sqrt
    
    // Rational function: x / (a + (1-a)*x)
    // Adjust 'a' based on exposure for better contrast preservation
    float a = 1.0 / (1.0 + baselineExposure * 0.5);  // Better than sqrt-based
    
    // This rational function provides smooth compression
    // It brightens shadows more while compressing highlights smoothly
    float result = multiplied / (a + (1.0 - a) * multiplied);
    
    return result;
}

// Hejl-Dawson tone mapping - better balance than ACES, preserves highlights better
// Provides good contrast preservation with softer highlight roll-off than ACES
float applyBaselineExposureHejlDawson(float normalized, float baselineExposure) {
    // NO input clamping - preserves highlights
    float x = normalized * baselineExposure;
    
    // Hejl-Dawson curve: (x - 0.004) * (6.2 * (x - 0.004) + 0.5) / ((x - 0.004) * (6.2 * (x - 0.004) + 1.7) + 0.06)
    float xx = max(x - 0.004, 0.0);
    float result = (xx * (6.2 * xx + 0.5)) / (xx * (6.2 * xx + 1.7) + 0.06);
    
    // Normalize so that x=baselineExposure maps to y=1.0
    // Maps full range to [0, 1.0]
    float maxX = baselineExposure;
    float maxXX = max(maxX - 0.004, 0.0);
    float maxY = (maxXX * (6.2 * maxXX + 0.5)) / (maxXX * (6.2 * maxXX + 1.7) + 0.06);
    float targetMax = 1.0;  // Map full range to [0, 1.0]
    
    if (maxY > 0.001) {
        result = result * (targetMax / maxY);
    }
    
    return result;
}

// Modified ACES Filmic with softer shoulder for better highlight preservation
// Uses reduced 'a' and 'c' values and increased 'd' and 'e' for softer compression
// HANDLES HDR INPUT: Normalizes input to preserve values in highlights
float applyBaselineExposureACESFilmicSoft(float normalized, float baselineExposure) {
    // Handle HDR input: normalize to preserve values
    // This prevents highlight loss where values converge to a/c asymptote
    float hdrScale = max(normalized, 1.0);
    float normalizedInput = normalized / hdrScale;  // Now in [0,1] with preserved ratios
    
    float x = normalizedInput * baselineExposure;
    
    // Modified ACES with softer shoulder - reduced 'a' and 'c' values
    float a = 2.0;   // Reduced from 2.51 for softer shoulder
    float b = 0.03;
    float c = 2.0;   // Reduced from 2.43 for softer shoulder
    float d = 0.65;  // Increased from 0.59 for better highlight roll-off
    float e = 0.18;  // Increased from 0.14 for softer compression
    
    // ACES filmic curve: (x * (a * x + b)) / (x * (c * x + d) + e)
    float result = (x * (a * x + b)) / (x * (c * x + d) + e);
    
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
        result = mix(result, 1.0, compressedExcess * 0.3);
    }
    
    return result;
}

// Reinhard-Jodie - preserves more contrast than standard Reinhard
// Blends between standard Reinhard and a contrast-enhanced version based on luminance
float applyBaselineExposureReinhardJodie(float normalized, float baselineExposure) {
    // NO input clamping - preserves highlights
    float x = normalized * baselineExposure;
    
    // Standard Reinhard
    float reinhard = x / (1.0 + x);
    
    // Jodie variant: adds contrast boost in mid-tones
    // Use a softer compression for the contrast boost: x / (1.0 + x * 0.5)
    float contrastBoost = pow(x / (1.0 + x * 0.5), 1.1);
    
    // Blend based on luminance - more contrast in mid-tones
    float luma = x;  // For single channel, luma is just the value itself
    float blendFactor = smoothstep(0.1, 0.9, luma);
    float result = mix(reinhard, contrastBoost, blendFactor * 0.4);
    
    // Normalize to ensure proper range
    // When normalized=1.0, x=baselineExposure
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
float applyBaselineExposureLottes(float normalized, float baselineExposure) {
    // NO input clamping - preserves highlights
    float x = normalized * baselineExposure;
    
    // Lottes parameters
    float a = 1.6;
    float d = 0.977;
    float hdrMax = baselineExposure;
    float midIn = 0.18;
    float midOut = 0.267;
    
    // Lottes curve: pow(x, a) / (pow(x, a*d) * b + c)
    // Calculate b and c based on parameters
    float b = (-pow(midIn, a) + pow(hdrMax, a) * midOut) / 
              ((pow(hdrMax, a * d) - pow(midIn, a * d)) * midOut);
    float c = (pow(hdrMax, a * d) * pow(midIn, a) - 
               pow(hdrMax, a) * pow(midIn, a * d) * midOut) / 
              ((pow(hdrMax, a * d) - pow(midIn, a * d)) * midOut);
    
    float result = pow(x, a) / (pow(x, a * d) * b + c);
    
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
// This preserves values in HDR highlights
float applyBaselineExposureGammaBased(float normalized, float baselineExposure) {
    // Handle HDR input: normalize to [0,1] while preserving ratios
    // This prevents highlight clipping
    float hdrScale = max(normalized, 1.0);  // Scale factor for HDR (1.0 if already in [0,1])
    float normalizedInput = normalized / hdrScale;  // Now in [0,1] range with preserved ratios
    
    // Calculate gamma from baselineExposure multiplier to produce equivalent exposure lifting
    // baselineExposure is a multiplier (2^EV), not EV directly
    // We want pow(x, 1/gamma) to produce equivalent brightness to x * baselineExposure
    // 
    // Convert multiplier to EV: EV = log2(baselineExposure)
    // Then use gamma = 1 + EV to approximate equivalent exposure lifting
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
    float gammaCorrected = pow(normalizedInput, 1.0 / cgamma_highlights);
    
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
    float result = applySigmoidalContrastInline(gammaCorrected, contrastStrength, midpoint);
    
    // For HDR highlights, compress the excess brightness
    // Instead of restoring full HDR scale, use Reinhard on the excess
    if (hdrScale > 1.0) {
        // Calculate how much brighter this pixel was (excess above 1.0 in original)
        float excess = hdrScale - 1.0;
        // Compress the excess using Reinhard: excess / (1 + excess)
        float compressedExcess = excess / (1.0 + excess);
        // Blend the result slightly toward white based on compressed excess
        // This gives bright highlights a gentle push without going fully white
        result = mix(result, 1.0, compressedExcess * 0.3);
    }
    
    return result;
}

// Gamma+ACES Fusion: Combines three compression methods
// - Reinhard as base (balanced, most of the image)
// - ACES blended in to improve contrast (mid-tones/darker areas)
// - Gamma blended in to improve highlight retention (bright areas)
float applyBaselineExposureGammaACESFusion(float normalized, float baselineExposure) {
    // Apply all three methods
    float reinhardResult = applyBaselineExposureReinhard(normalized, baselineExposure);
    float acesResult = applyBaselineExposureACESFilmicSoft(normalized, baselineExposure);
    float gammaResult = applyBaselineExposureGammaBased(normalized, baselineExposure);
    
    // Calculate luminance of input (for single channel, use reinhardResult as luminance)
    float inputLuminance = reinhardResult;
    
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
    float result = reinhardResult * reinhardWeight + 
                   acesResult * acesWeight + 
                   gammaResult * gammaWeight;
    
    return result;
}

// Gradient-aware baseline exposure compression
// Computes gradients from neighboring normalized values and applies gradient-aware compression
float applyBaselineExposureGradientDomain(float normalized, float baselineExposure, ivec2 xy, 
        float g, float bl, float whiteLevel) {
    // Sample neighbors from rawBuffer and normalize them
    ivec2 rightXY = ivec2(min(xy.x + 1, rawWidth - 1), xy.y);
    ivec2 leftXY = ivec2(max(xy.x - 1, 0), xy.y);
    ivec2 topXY = ivec2(xy.x, min(xy.y + 1, rawHeight - 1));
    ivec2 bottomXY = ivec2(xy.x, max(xy.y - 1, 0));
    
    // Get raw values for neighbors (simplified - using same gain/blacklevel as center)
    // In a more accurate implementation, we'd compute the correct gain/blacklevel for each neighbor
    float rightRaw = float(texelFetch(rawBuffer, rightXY, 0).x);
    float leftRaw = float(texelFetch(rawBuffer, leftXY, 0).x);
    float topRaw = float(texelFetch(rawBuffer, topXY, 0).x);
    float bottomRaw = float(texelFetch(rawBuffer, bottomXY, 0).x);
    
    // Normalize neighbors (using same gain/blacklevel as center for simplicity)
    float rightNorm = g * (rightRaw - bl) / (whiteLevel - bl);
    float leftNorm = g * (leftRaw - bl) / (whiteLevel - bl);
    float topNorm = g * (topRaw - bl) / (whiteLevel - bl);
    float bottomNorm = g * (bottomRaw - bl) / (whiteLevel - bl);
    
    rightNorm = max(rightNorm, 0.0);
    leftNorm = max(leftNorm, 0.0);
    topNorm = max(topNorm, 0.0);
    bottomNorm = max(bottomNorm, 0.0);
    
    // Compute gradients
    float gradX = (rightNorm - leftNorm) * 0.5;
    float gradY = (topNorm - bottomNorm) * 0.5;
    float gradMag = sqrt(gradX * gradX + gradY * gradY);
    
    // Apply baseline exposure to center value
    float multiplied = normalized * baselineExposure;
    
    // Compression threshold - larger gradients get compressed more
    float threshold = 0.1 * baselineExposure;
    float compressedGradMag = gradMag / (threshold + gradMag);
    
    // Compute compression factor based on gradient magnitude
    float gradientFactor = gradMag > 0.001 ? compressedGradMag / gradMag : 1.0;
    
    // Apply Reinhard compression as base
    float compressed = multiplied / (1.0 + multiplied);
    
    // Blend between compressed and original based on gradient strength
    // Strong gradients (edges): preserve more original detail
    // Weak gradients (smooth): use more compression
    float edgeStrength = min(gradMag * 10.0, 1.0);  // Normalize gradient magnitude
    float result = mix(compressed, multiplied, edgeStrength * 0.3);  // Blend up to 30% original at edges
    
    // Normalize to ensure [0, 1] output
    // When normalized=1.0, multiplied=baselineExposure, compressed≈1.0 (for large baselineExposure)
    // The blended result might exceed 1.0, so we need to normalize
    // Compute what the maximum would be: when normalized=1.0, multiplied=baselineExposure
    float maxInput = baselineExposure;
    float maxCompressed = maxInput / (1.0 + maxInput);
    float maxBlended = mix(maxCompressed, maxInput, edgeStrength * 0.3);
    
    // Normalize so that maxInput maps to 1.0
    if (maxBlended > 0.001) {
        result = result / maxBlended;
    }
    
    return result;
}

// Sigmoidal Curve Approach
// Uses sigmoidal contrast to brighten and compress, similar to gamma-based
// but with a different approach to the brightening step
float applyBaselineExposureSigmoidal(float normalized, float baselineExposure) {
    // Input should be clamped to [0,1] before calling this function
    float clampedInput = min(normalized, 1.0);  // Clamp for power curve
    
    // First, brighten the image using a power curve
    // Higher baselineExposure = more brightening needed
    float brightenExponent = 1.0 / baselineExposure;
    brightenExponent = clamp(brightenExponent, 0.33, 1.0);  // Limit to reasonable range
    float brightened = pow(clampedInput, brightenExponent);
    
    // Then apply sigmoidal contrast to restore contrast and compress highlights smoothly
    // Adjust contrast strength based on baselineExposure
    float contrastStrength = 2.0 + (baselineExposure - 1.0) * 1.5;
    contrastStrength = clamp(contrastStrength, 2.0, 6.0);
    float midpoint = 0.5;
    
    float result = applySigmoidalContrastInline(brightened, contrastStrength, midpoint);
    
    return clamp(result, 0.0, 1.0);  // Sigmoidal works on [0,1] so clamp is appropriate
}

// Piecewise Linear with Smooth Shoulder
// Linear in mid-tones (preserves contrast), smooth compression in highlights
float applyBaselineExposurePiecewise(float normalized, float baselineExposure) {
    // Input should be clamped to [0,1] before calling this function
    float clampedInput = min(normalized, 1.0);
    
    // Brighten using power curve
    float brightenExponent = 1.0 / baselineExposure;
    brightenExponent = clamp(brightenExponent, 0.33, 1.0);
    float brightened = pow(clampedInput, brightenExponent);
    
    // Apply piecewise curve: linear in mid-tones, smooth compression in highlights
    float x = brightened;
    
    // Define transition point (where we switch from linear to compression)
    float transition = 0.7;
    
    float result;
    if (x <= transition) {
        // Linear region: preserve contrast
        // Scale linearly to use most of the [0, transition] range
        result = x * (0.95 / transition);  // Map [0, transition] to [0, 0.95]
    } else {
        // Compression region: smooth shoulder
        // Map [transition, 1.0] to [0.95, 1.0] using smooth curve
        float excess = x - transition;
        float maxExcess = 1.0 - transition;
        
        // Use smooth compression curve (sigmoid-like)
        float compressedExcess = excess / (1.0 + excess / maxExcess);
        result = 0.95 + (compressedExcess / maxExcess) * 0.05;
    }
    
    return clamp(result, 0.0, 1.0);  // Piecewise works on [0,1] so clamp is appropriate
}

// ============================================================================
// HISTOGRAM MATCHING BASELINE EXPOSURE COMPRESSION
// ============================================================================
// Uses the embedded JPEG preview as a reference to guide baseline exposure compression.
// 
// IMPORTANT: Space conversion considerations:
// - Input: Linear single-channel value after baseline exposure multiplication [0, baselineExposure+]
// - Reference JPEG: Gamma-encoded sRGB, already tone-mapped/compressed [0, 1]
// - LUT: Maps gamma-encoded luminance [0, 1] to target gamma-encoded luminance [0, 1]
//
// For single-channel data (Bayer pattern), we treat the value as luminance directly.

// Simple gamma encode (approximate sRGB)
float gammaEncode(float linear) {
    return pow(max(linear, 0.0), 1.0 / 2.2);
}

// Simple gamma decode (approximate sRGB)
float gammaDecode(float encoded) {
    return pow(max(encoded, 0.0), 2.2);
}

float applyBaselineExposureHistogramMatch(float normalized, float baselineExposure) {
    // Step 1: Multiply by baseline exposure (linear brightening)
    float multiplied = normalized * baselineExposure;
    
    // Avoid division by zero for very dark pixels
    if (multiplied < 0.0001) {
        // For very dark pixels, just return the multiplied value normalized
        return multiplied / baselineExposure;
    }
    
    // Step 2: Normalize to [0, 1] range
    // After baselineExposure multiplication, "normal white" (input=1.0) is at baselineExposure
    float normalizedValue = multiplied / baselineExposure;
    normalizedValue = clamp(normalizedValue, 0.0, 1.0);  // Clamp for LUT lookup
    
    // Step 3: Convert to gamma space to match the reference JPEG's encoding
    float gammaValue = gammaEncode(normalizedValue);
    
    // Step 4: Look up target value from histogram matching LUT
    // The LUT maps gamma-encoded input to gamma-encoded target
    float targetGammaValue = texture(histMatchLut, vec2(gammaValue, 0.5)).r;
    
    // Step 5: Calculate adjustment ratio in gamma space
    float gammaRatio = 1.0;
    if (gammaValue > 0.0001) {
        gammaRatio = targetGammaValue / gammaValue;
        // Limit extreme adjustments to prevent artifacts
        gammaRatio = clamp(gammaRatio, 0.3, 3.0);
    }
    
    // Step 6: Apply the adjustment in gamma space
    float adjustedGammaValue = gammaValue * gammaRatio;
    
    // Step 7: Convert back to linear space
    float result = gammaDecode(adjustedGammaValue);
    
    // Clamp to [0, 1] - we're matching the compressed JPEG distribution
    return clamp(result, 0.0, 1.0);
}

// ============================================================================
// EXPOSURE FUSION - HDR OUTPUT
// ============================================================================
// Fuses three virtual exposures similar to stage4_1_doubleexpose.glsl:
// - Underexposed: original normalized values (gamma > 1, preserves highlights)
// - Overexposed: multiplied by baselineExposure (linear, bright)
// - Center: GammaACESFusion tone-mapped (balanced color/contrast)
//
// Unlike other compression methods, this outputs HDR values that can exceed 1.0
// This preserves the full dynamic range from baselineExposure multiplication
// while using exposure fusion to blend highlight and shadow detail.
float applyBaselineExposureExposureFusion(float normalized, float baselineExposure) {
    // === Create three exposures ===
    
    // 1. Underexposed: use gamma > 1 to darken, similar to stage4's approach
    // This preserves highlight detail by compressing bright values
    // gamma scales with baselineExposure: higher exposure = more darkening needed
    float gammaUnder = 1.0 + log2(baselineExposure) * 0.3;
    gammaUnder = clamp(gammaUnder, 1.0, 2.0);
    float under = pow(max(normalized, 0.0001), gammaUnder);
    // Scale up to target brightness (preserves highlight detail at correct level)
    under = under * baselineExposure;
    
    // 2. Overexposed: linear multiplication (gamma = 1)
    // Full brightness, may have extreme values in highlights
    float over = normalized * baselineExposure;
    
    // 3. Center: tone-mapped using GammaACESFusion
    // Balanced exposure with good contrast, typically in [0,1] range
    float center = applyBaselineExposureGammaACESFusion(normalized, baselineExposure);
    // Scale center toward HDR range for proper blending weight
    // Use sqrt(baselineExposure) for partial expansion
    center = center * sqrt(baselineExposure);
    
    // === Calculate luminance for each exposure ===
    float underLuma = under;
    float overLuma = over;
    float centerLuma = center;
    
    // === Well-exposedness weights (Mertens-style) ===
    // Values near 0.5 (after normalization) are considered "well exposed"
    // This is similar to how stage4 exposure fusion works
    float sigma = 0.25;
    float twoSigmaSq = 2.0 * sigma * sigma;
    
    // Normalize luminance to [0,1] for weight calculation
    // Use a reference point based on expected output range
    float refMax = baselineExposure * 1.5;  // Expected max HDR value
    
    float underNorm = clamp(underLuma / refMax, 0.0, 1.0);
    float overNorm = clamp(overLuma / refMax, 0.0, 1.0);
    float centerNorm = clamp(centerLuma / refMax, 0.0, 1.0);
    
    // Gaussian weight: exp(-(x - 0.5)^2 / (2 * sigma^2))
    float wUnder = exp(-pow(underNorm - 0.5, 2.0) / twoSigmaSq);
    float wOver = exp(-pow(overNorm - 0.5, 2.0) / twoSigmaSq);
    float wCenter = exp(-pow(centerNorm - 0.5, 2.0) / twoSigmaSq);
    
    // === Adjust weights based on exposure characteristics ===
    
    // Boost underexposed weight in highlight regions
    // Under preserves highlight detail through gamma compression
    float inputLuma = normalized;
    if (inputLuma > 0.5) {
        wUnder *= 1.0 + smoothstep(0.5, 1.0, inputLuma);
    }
    
    // Boost overexposed weight in shadow regions
    // Over has correct brightness for shadows
    if (inputLuma < 0.2) {
        wOver *= 1.0 + smoothstep(0.2, 0.0, inputLuma);
    }
    
    // Center is our balanced reference
    wCenter *= 1.2;  // Slight boost for stability
    
    // Ensure minimum weights to avoid division issues
    float minWeight = 0.01;
    wUnder = max(wUnder, minWeight);
    wOver = max(wOver, minWeight);
    wCenter = max(wCenter, minWeight);
    
    // Normalize weights to sum to 1.0
    float wTotal = wUnder + wOver + wCenter;
    wUnder /= wTotal;
    wOver /= wTotal;
    wCenter /= wTotal;
    
    // === Weighted blend of the three exposures ===
    float result = under * wUnder + over * wOver + center * wCenter;
    
    // === HDR output ===
    // Don't clamp to [0,1] - preserve full dynamic range
    // Only clamp negatives
    return max(result, 0.0);
}

// LibRaw exp_bef compression method
// Implements LibRaw's exposure correction with highlight preservation
// Uses cubic curve compression similar to LibRaw's exp_bef function
// This method applies exposure shift with smooth highlight compression
float applyBaselineExposureLibRawExpBef(float normalized, float baselineExposure) {
    // NO input clamping - preserves highlights
    float shift = baselineExposure;
    float smoothness = 0.0;  // LibRaw's exp_preser parameter (0.0 = no preservation, 1.0 = full)
    
    // Clamp shift to LibRaw's limits [0.25, 8.0]
    shift = clamp(shift, 0.25, 8.0);
    smoothness = clamp(smoothness, 0.0, 1.0);
    
    float x = normalized;
    
    if (shift <= 1.0) {
        // Simple linear scaling for darkening
        return x * shift;
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
        
        // Apply curve
        float Y;
        if (x < x1) {
            // Linear region
            Y = x * shift;
        } else {
            // Cubic compression region
            Y = A * pow(x, 1.0 / 3.0) + B * x + CC;
        }
        
        // Clamp to [0, 1] range
        return clamp(Y, 0.0, 1.0);
    }
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);

    float v;
    int pxInfo = int(texelFetch(hotPixels, xy % hotPixelsSize, 0).x);
    if (pxInfo == 0) {
        v = float(texelFetch(rawBuffer, xy, 0).x);
    } else {
        uint vx;
        int c;
        if ((pxInfo & 1) > 0) {
            // HORIZONTAL INTERPOLATE
            for (int j = -2; j <= 2; j += 4) {
                vx += texelFetch(rawBuffer, xy + ivec2(j, 0), 0).x;
            }
            c += 2;
        }
        if ((pxInfo & 2) > 0) {
            // VERTICAL INTERPOLATE
            for (int j = -2; j <= 2; j += 4) {
                vx += texelFetch(rawBuffer, xy + ivec2(0, j), 0).x;
            }
            c += 2;
        }
        if ((pxInfo & 4) > 0) {
            // CROSS INTERPOLATE
            for (int j = 0; j < 4; j++) {
                vx += texelFetch(rawBuffer, xy + ivec2(2 * (j % 4) - 1, 2 * (j / 4) - 1), 0).x;
            }
            c += 4;
        }
        v = float(vx) / float(c);
    }

    vec2 xyInterp = vec2(float(xy.x) / float(rawWidth), float(xy.y) / float(rawHeight));
    vec4 gains = texture(gainMap, xyInterp);
    int index = (xy.x & 1) | ((xy.y & 1) << 1);  // bits [0,1] are blacklevel offset
    //index |= (cfaPattern << 2);
    float bl = 0.f;
    float g = 1.f;
    switch (index) {
        // RGGB
        case 0: bl = blackLevel.x; g = gains.x; break;
        case 1: bl = blackLevel.y; g = gains.y; break;
        case 2: bl = blackLevel.z; g = gains.z; break;
        case 3: bl = blackLevel.w; g = gains.w; break;
        /*
        // GRBG
        case 4: bl = blackLevel.x; g = gains.y; break;
        case 5: bl = blackLevel.y; g = gains.x; break;
        case 6: bl = blackLevel.z; g = gains.w; break;
        case 7: bl = blackLevel.w; g = gains.z; break;
        // GBRG
        case 8: bl = blackLevel.x; g = gains.y; break;
        case 9: bl = blackLevel.y; g = gains.w; break;
        case 10: bl = blackLevel.z; g = gains.x; break;
        case 11: bl = blackLevel.w; g = gains.z; break;
        // BGGR
        case 12: bl = blackLevel.x; g = gains.w; break;
        case 13: bl = blackLevel.y; g = gains.y; break;
        case 14: bl = blackLevel.z; g = gains.z; break;
        case 15: bl = blackLevel.w; g = gains.x; break;
        */
    }

    // Normalize raw sensor value - can exceed 1.0 for HDR headroom
    float normalized = g * (v - bl) / (whiteLevel - bl);
    // BEST PRACTICE: Only clamp negatives, preserve HDR headroom
    // Baseline exposure is applied here as LINEAR MULTIPLICATION (like darktable/RawTherapee)
    // The demosaicing algorithms have been made HDR-safe by clamping edge weights
    normalized = max(normalized, 0.0);
    
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
            normalized = normalized * baselineExposure;
        } else if (baselineExposureCompression == 1) {
            normalized = applyBaselineExposureReinhard(normalized, baselineExposure);
        } else if (baselineExposureCompression == 2) {
            normalized = applyBaselineExposureACESFilmic(normalized, baselineExposure);
        } else if (baselineExposureCompression == 3) {
            normalized = applyBaselineExposureUncharted2(normalized, baselineExposure);
        } else if (baselineExposureCompression == 4) {
            normalized = applyBaselineExposureImprovedRational(normalized, baselineExposure);
        } else if (baselineExposureCompression == 5) {
            normalized = applyBaselineExposureGradientDomain(normalized, baselineExposure, xy, g, bl, whiteLevel);
        } else if (baselineExposureCompression == 6) {
            normalized = applyBaselineExposureHejlDawson(normalized, baselineExposure);
        } else if (baselineExposureCompression == 7) {
            normalized = applyBaselineExposureACESFilmicSoft(normalized, baselineExposure);
        } else if (baselineExposureCompression == 8) {
            normalized = applyBaselineExposureReinhardJodie(normalized, baselineExposure);
        } else if (baselineExposureCompression == 9) {
            normalized = applyBaselineExposureLottes(normalized, baselineExposure);
        } else if (baselineExposureCompression == 10) {
            normalized = applyBaselineExposureGammaBased(normalized, baselineExposure);
        } else if (baselineExposureCompression == 11) {
            normalized = applyBaselineExposureGammaACESFusion(normalized, baselineExposure);
        } else if (baselineExposureCompression == 12) {
            normalized = applyBaselineExposureSlider(normalized, baselineExposure);
        } else if (baselineExposureCompression == 13) {
            normalized = applyBaselineExposureSigmoidal(normalized, baselineExposure);
        } else if (baselineExposureCompression == 14) {
            normalized = applyBaselineExposurePiecewise(normalized, baselineExposure);
        } else if (baselineExposureCompression == 15) {
            // Histogram matching - uses embedded JPEG preview to guide compression
            if (hasReferencePreview) {
                normalized = applyBaselineExposureHistogramMatch(normalized, baselineExposure);
            } else {
                // Fallback to Gamma+ACES Fusion if no preview available
                normalized = applyBaselineExposureGammaACESFusion(normalized, baselineExposure);
            }
        } else if (baselineExposureCompression == 16) {
            normalized = applyBaselineExposureLibRawExpBef(normalized, baselineExposure);
        } else if (baselineExposureCompression == 17) {
            // Exposure Fusion: blends under/over/center exposures, outputs HDR
            normalized = applyBaselineExposureExposureFusion(normalized, baselineExposure);
        } else {
            // Fallback to Gamma+ACES Fusion for unknown values
            normalized = applyBaselineExposureGammaACESFusion(normalized, baselineExposure);
        }
    } else if (baselineExposure < 1.0) {
        // Darkening: use power curve (darkens highlights more than shadows)
        // Use full baselineExposure value for the curve exponent
        float curveExponent = baselineExposure;
        // Clamp to [0, 1] for power curve to avoid precision issues
        normalized = pow(clamp(normalized, 0.0, 1.0), curveExponent);
    }
    
    // BEST PRACTICE: Output can exceed 1.0 for HDR headroom
    // Demosaicing algorithms are HDR-safe (edge weights clamped before ^6)
    intermediate = max(normalized, 0.0);
}
