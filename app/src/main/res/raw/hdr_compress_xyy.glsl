#version 300 es

precision highp float;

// xyY input texture (intermediate format)
uniform sampler2D xyYInput;

// Compression method: 0=Reinhard, 1=ACES Filmic, 2=Uncharted 2, 3=Improved Rational, 4=Gamma+ACES Fusion
uniform int compressionMethod;

// Baseline exposure multiplier (2^EV)
uniform float baselineExposure;

// Maximum HDR value (not used for normalization, kept for compatibility)
uniform float maxHdr;

// Compression strength (0.0 = no compression, 1.0 = full compression)
uniform float compressionStrength;

out vec4 result;

#include gamma

// =============================================================================
// HDR Compression in xyY Space
//
// This shader applies HDR compression to the Y (luminance) channel of xyY
// while preserving xy chromaticity. This ensures color saturation is maintained.
//
// Process:
// 1. Decode xyY from texture
// 2. Extract Y (luminance) - already in linear space
// 3. Apply compression method to Y
// 4. Preserve xy chromaticity
// 5. Encode and output modified xyY
// =============================================================================

// Decode HDR xyY from texture sample
vec3 decodeHDRxyY(vec4 encoded) {
    if (encoded.w >= 0.9999) {
        return vec3(encoded.x, encoded.y, encoded.z);
    }
    float invScale = max(encoded.w, 0.0001);
    return vec3(encoded.x, encoded.y, encoded.z / invScale);
}

// Smooth clamping function (from stage3_3_tonemap_fs.glsl)
float smoothClamp(float x, float minVal, float maxVal, float transition) {
    if (x <= minVal) return minVal;
    if (x >= maxVal) return maxVal;
    // Smooth transition zones at boundaries
    float range = maxVal - minVal;
    float lowerZone = minVal + transition * range;
    float upperZone = maxVal - transition * range;
    
    if (x < lowerZone) {
        // Smooth transition from minVal to lowerZone
        float t = (x - minVal) / (lowerZone - minVal);
        t = smoothstep(0.0, 1.0, t);
        return mix(minVal, lowerZone, t);
    } else if (x > upperZone) {
        // Smooth transition from upperZone to maxVal
        float t = (x - upperZone) / (maxVal - upperZone);
        t = smoothstep(0.0, 1.0, t);
        return mix(upperZone, maxVal, t);
    } else {
        // Linear in the middle
        return x;
    }
}

// =============================================================================
// Compression Methods (applied to scalar luminance Y)
// =============================================================================

// Method 0: Reinhard compression
// Simple and smooth: Y / (1 + Y)
float reinhardCompress(float Y) {
    return Y / (1.0 + Y);
}

// Method 1: ACES Filmic compression
// Film-like response with good highlight rolloff
// Matches PreProcess: normalizes output so that max input (baselineExposure) maps to 1.0
float acesFilmicCompress(float Y, float baselineExposure) {
    float a = 2.51;
    float b = 0.03;
    float c = 2.43;
    float d = 0.59;
    float e = 0.14;
    
    // ACES filmic curve: (x * (a * x + b)) / (x * (c * x + d) + e)
    float result = (Y * (a * Y + b)) / (Y * (c * Y + d) + e);
    
    // Normalize so that x=baselineExposure maps to y=1.0 (matches PreProcess)
    // Maps full range to [0, 1.0]
    float maxX = baselineExposure;
    float maxY = (maxX * (a * maxX + b)) / (maxX * (c * maxX + d) + e);
    float targetMax = 1.0;
    
    if (maxY > 0.001) {
        result = result * (targetMax / maxY);
    }
    
    return result;
}

// Method 2: Uncharted 2 tone mapping
// Popular in game engines, good for HDR
// Matches PreProcess: handles HDR input and normalizes properly
// Note: Y is already scaled by baselineExposure, so we work directly on it
float uncharted2Compress(float Y, float baselineExposure) {
    // Handle HDR input: Y is already in [0, baselineExposure] range
    // For values > baselineExposure, we need to handle them specially
    float hdrScale = max(Y / baselineExposure, 1.0);
    float normalizedInput = Y / (hdrScale * baselineExposure);  // Normalize to [0,1] range
    
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
    
    // Normalize to [0, 1] range using standard white point (matches PreProcess)
    float whiteScale = ((11.2 * (A * 11.2 + C * B) + D * E) / (11.2 * (A * 11.2 + B) + D * F)) - E / F;
    result = result / whiteScale;
    
    // For HDR highlights, blend toward white based on compressed excess (matches PreProcess)
    if (hdrScale > 1.0) {
        float excess = hdrScale - 1.0;
        float compressedExcess = excess / (1.0 + excess);  // Reinhard on excess
        result = mix(result, 1.0, compressedExcess * 0.3);
    }
    
    return result;
}

// Method 3: Improved Rational compression
// Enhanced version of Reinhard with better highlight handling
// Matches PreProcess: uses adaptive formula based on baselineExposure
float improvedRationalCompress(float Y, float baselineExposure) {
    // Match PreProcess: adjust 'a' based on exposure for better contrast preservation
    float a = 1.0 / (1.0 + baselineExposure * 0.5);
    
    // Match PreProcess formula: x / (a + (1-a)*x)
    // This rational function provides smooth compression
    // It brightens shadows more while compressing highlights smoothly
    return Y / (a + (1.0 - a) * Y);
}

// Method 4: Gamma-based compression
// Matches PreProcess: uses adaptive gamma based on baselineExposure
// Note: Y is already scaled by baselineExposure, so we work directly on it
float gammaBasedCompress(float Y, float baselineExposure) {
    // Handle HDR input: Y is already in [0, baselineExposure] range (or higher for true HDR)
    // Normalize to [0,1] while preserving ratios (matches PreProcess)
    float hdrScale = max(Y / baselineExposure, 1.0);
    float normalizedInput = Y / (hdrScale * baselineExposure);
    
    // Calculate gamma from baselineExposure multiplier (matches PreProcess)
    // Convert multiplier to EV: EV = log2(baselineExposure)
    // Then use gamma = 1 + EV to approximate equivalent exposure lifting
    float ev = log2(baselineExposure);
    float cgamma_highlights = 1.0 + ev;
    
    // Clamp to reasonable range [0.5, 3.0] (matches PreProcess)
    cgamma_highlights = clamp(cgamma_highlights, 0.5, 3.0);
    
    // Apply gamma correction: pow(x, 1/gamma)
    float gammaCorrected = pow(normalizedInput, 1.0 / cgamma_highlights);
    
    // Apply sigmoidal contrast to compensate for contrast loss from gamma (matches PreProcess)
    float contrastStrength = 1.0;
    if (cgamma_highlights > 1.0) {
        // Increase contrast strength as gamma increases
        contrastStrength = mix(1.0, 3.3, smoothstep(1.0, 3.0, cgamma_highlights));
    }
    float midpoint = 0.5;
    float sigmoid_mid = 1.0 / (1.0 + exp(-contrastStrength * (gammaCorrected - midpoint)));
    float sigmoid_0 = 1.0 / (1.0 + exp(-contrastStrength * (0.0 - midpoint)));
    float sigmoid_1 = 1.0 / (1.0 + exp(-contrastStrength * (1.0 - midpoint)));
    float result = (sigmoid_mid - sigmoid_0) / (sigmoid_1 - sigmoid_0);
    
    // Preserve very dark values to avoid crushing shadows (matches PreProcess)
    float darkBlend = smoothstep(0.02, 0.0, gammaCorrected);
    float minOutput = gammaCorrected * 0.1;
    result = mix(result, max(result, minOutput), darkBlend);
    
    // For HDR highlights, blend toward white based on compressed excess (matches PreProcess)
    if (hdrScale > 1.0) {
        float excess = hdrScale - 1.0;
        float compressedExcess = excess / (1.0 + excess);  // Reinhard on excess
        result = mix(result, 1.0, compressedExcess * 0.3);
    }
    
    return result;
}

// ACES Filmic Soft - modified ACES with softer shoulder for better highlight preservation
// Matches PreProcess: uses ACES Filmic Soft (not regular ACES) for Gamma+ACES Fusion
// Note: Y is already scaled by baselineExposure, so we work directly on it
float acesFilmicSoftCompress(float Y, float baselineExposure) {
    // Handle HDR input: Y is already in [0, baselineExposure] range (or higher for true HDR)
    // Normalize to preserve values (matches PreProcess)
    float hdrScale = max(Y / baselineExposure, 1.0);
    float normalizedInput = Y / (hdrScale * baselineExposure);
    
    float x = normalizedInput * baselineExposure;  // Scale back to [0, baselineExposure] range
    
    // Modified ACES with softer shoulder - reduced 'a' and 'c' values (matches PreProcess)
    float a = 2.0;   // Reduced from 2.51 for softer shoulder
    float b = 0.03;
    float c = 2.0;   // Reduced from 2.43 for softer shoulder
    float d = 0.65;  // Increased from 0.59 for better highlight roll-off
    float e = 0.18;  // Increased from 0.14 for softer compression
    
    // ACES filmic curve: (x * (a * x + b)) / (x * (c * x + d) + e)
    float result = (x * (a * x + b)) / (x * (c * x + d) + e);
    
    // Normalize so that x=baselineExposure maps to y=1.0 (matches PreProcess)
    float maxX = baselineExposure;
    float maxY = (maxX * (a * maxX + b)) / (maxX * (c * maxX + d) + e);
    float targetMax = 1.0;
    
    if (maxY > 0.001) {
        result = result * (targetMax / maxY);
    }
    
    // For HDR highlights, blend toward white based on compressed excess (matches PreProcess)
    if (hdrScale > 1.0) {
        float excess = hdrScale - 1.0;
        float compressedExcess = excess / (1.0 + excess);  // Reinhard on excess
        result = mix(result, 1.0, compressedExcess * 0.3);
    }
    
    return result;
}

// Method 4: Gamma+ACES Fusion compression
// Blends Reinhard, ACES Filmic Soft, and Gamma Based methods based on luminance
// Matches PreProcess: uses ACES Filmic Soft and proper Gamma Based
float gammaACESFusionCompress(float Y, float baselineExposure) {
    // Apply all three methods (matches PreProcess)
    float reinhardResult = reinhardCompress(Y);
    float acesResult = acesFilmicSoftCompress(Y, baselineExposure);  // Use ACES Filmic Soft
    float gammaResult = gammaBasedCompress(Y, baselineExposure);
    
    // Calculate luminance of input (for single channel, use reinhardResult as luminance)
    float inputLuminance = reinhardResult;
    
    // ACES weight: higher in mid-tones/darker areas where contrast is needed (matches PreProcess)
    float acesWeight = 0.0;
    if (inputLuminance < 0.5) {
        // Boost contrast in mid-tones and darker areas
        acesWeight = smoothstep(0.5, 0.2, inputLuminance) * 0.5;  // Max 50% ACES
    }
    
    // Gamma weight: higher in bright areas where highlight retention is needed (matches PreProcess)
    float gammaWeight = 0.0;
    if (inputLuminance > 0.5) {
        // Preserve highlights in bright areas
        gammaWeight = smoothstep(0.5, 1.0, inputLuminance) * 0.5;  // Max 50% Gamma
    }
    
    // Adjust Reinhard weight to accommodate ACES and Gamma (matches PreProcess)
    float totalAdjustment = acesWeight + gammaWeight;
    float reinhardWeight = 1.0 - totalAdjustment;
    
    // Blend the three results
    float result = reinhardResult * reinhardWeight + 
                   acesResult * acesWeight + 
                   gammaResult * gammaWeight;
    
    return result;
}

// Apply compression method to luminance
// baselineExposure is needed for methods that adapt to exposure (ACES, Improved Rational, etc.)
float applyCompression(float Y, float baselineExposure) {
    float compressedY;
    
    if (compressionMethod == 0) {
        // Reinhard (no baselineExposure needed - simple formula)
        compressedY = reinhardCompress(Y);
    } else if (compressionMethod == 1) {
        // ACES Filmic (needs baselineExposure for normalization)
        compressedY = acesFilmicCompress(Y, baselineExposure);
    } else if (compressionMethod == 2) {
        // Uncharted 2 (needs baselineExposure for HDR handling)
        compressedY = uncharted2Compress(Y, baselineExposure);
    } else if (compressionMethod == 3) {
        // Improved Rational (needs baselineExposure for adaptive formula)
        compressedY = improvedRationalCompress(Y, baselineExposure);
    } else if (compressionMethod == 4) {
        // Gamma+ACES Fusion (needs baselineExposure for ACES and Gamma)
        compressedY = gammaACESFusionCompress(Y, baselineExposure);
    } else {
        // Default to Reinhard
        compressedY = reinhardCompress(Y);
    }
    
    return compressedY;
}

void main() {
    ivec2 xyPos = ivec2(gl_FragCoord.xy);
    
    // Read and decode xyY
    vec4 encoded = texelFetch(xyYInput, xyPos, 0);
    vec3 xyY = decodeHDRxyY(encoded);
    
    // Extract components
    float x = xyY.x;  // Chromaticity x (preserved)
    float y = xyY.y;  // Chromaticity y (preserved)
    float Y = xyY.z;  // Luminance (will be modified)
    
    // Avoid processing near-zero luminance
    if (Y < 0.0001) {
        result = encoded;  // Return original
        return;
    }
    
    // Apply compression method directly to Y (compression methods handle HDR values natively)
    // No need to normalize by maxHdr - compression methods (Reinhard, ACES, etc.) work on any positive value
    // Pass baselineExposure for methods that need it to match PreProcess behavior
    float compressedY = applyCompression(Y, baselineExposure);
    
    // Direct replacement approach (like LateExposureFusion) preserves color better than scaling
    // Apply compression strength (blend with original Y)
    float newY = mix(Y, compressedY, compressionStrength);
    
    // Compression methods output values in [0,1] range, so clamp to prevent negatives
    newY = max(newY, 0.0);
    
    // Preserve xy chromaticity, update Y
    vec3 newXyY = vec3(x, y, newY);
    
    // Encode back to texture format
    float hdrScale = max(baselineExposure, 1.0);
    if (newY > 1.0 && hdrScale > 1.0) {
        // HDR encoding: store Y/hdrScale in z, 1/hdrScale in w
        float scale = 1.0 / hdrScale;
        result = vec4(newXyY.x, newXyY.y, newY * scale, scale);
    } else {
        // No encoding needed: values are in [0, 1]
        result = vec4(newXyY.x, newXyY.y, newY, 1.0);
    }
}
