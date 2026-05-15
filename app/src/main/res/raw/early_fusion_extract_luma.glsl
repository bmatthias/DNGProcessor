#version 300 es

precision highp float;

// RGB input texture (linear RGB from LinearRawPreProcess)
uniform sampler2D rgbInput;

// Baseline exposure multiplier (2^EV)
uniform float baselineExposure;

// Frame type: 0 = under, 1 = center, 2 = over
uniform int frameType;

out float result;

// =============================================================================
// Extract luma from RGB and apply tone mapping for exposure fusion
//
// Three frames with different tone curves:
// - Under (GammaBased): Very low contrast, perfect highlight preservation
// - Center (Reinhard): Low contrast, somewhat preserved highlights
// - Over (ACES-like): Great contrast, clipped highlights (reveals shadows)
//
// These match the original xyY-space fusion approach but applied to luma.
// =============================================================================

// Smooth clamp function to prevent banding
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

// =============================================================================
// Tone mapping functions for exposure fusion
// These work on already-HDR input (already multiplied by baselineExposure in preprocess)
// Note: baselineExposure is still passed for gamma calculation in GammaBased, but input is NOT multiplied
// =============================================================================

// Tone mapping functions - match late fusion implementation
// These work on already-HDR input (already multiplied by baselineExposure in preprocess)
// Note: baselineExposure is still passed for gamma calculation in GammaBased, but input is NOT multiplied

float reinhardTonemap(float luma, float exposure) {
    // Input is already HDR, so just apply Reinhard compression
    return luma / (1.0 + luma);
}

float acesTonemap(float luma, float exposure) {
    // Input is already HDR, apply ACES directly (matches late fusion coefficients)
    float a = 2.0;
    float b = 0.03;
    float c = 2.0;
    float d = 0.65;
    float e = 0.18;
    float result = (luma * (a * luma + b)) / (luma * (c * luma + d) + e);
    // Normalize if needed (matches late fusion HDR blending)
    float hdrScale = max(luma, 1.0);
    if (hdrScale > 1.0) {
        float hdrBlend = smoothstep(1.0, 1.3, hdrScale);
        float excess = max(hdrScale - 1.0, 0.0);
        float compressedExcess = excess / (1.0 + excess);
        result = mix(result, mix(result, 1.0, compressedExcess * 0.3), hdrBlend);
    }
    return result;
}

float gammaBasedTonemap(float luma, float exposure) {
    // GammaBased creates an "under" frame by using gamma correction instead of multiplying by exposure
    // Since input is already multiplied by exposure, we need to divide first to avoid double exposure
    float originalLuma = luma / exposure;
    
    // Now apply gamma correction to create the under frame
    float hdrScale = max(originalLuma, 1.0);
    float normalizedInput = originalLuma / hdrScale;
    float ev = log2(exposure);
    float cgamma_highlights = 1.0 + ev;
    float softClampLow = 0.5;
    float softClampHigh = 3.0;
    float lowBlend = smoothstep(softClampLow - 0.2, softClampLow + 0.2, cgamma_highlights);
    float highBlend = smoothstep(softClampHigh + 0.2, softClampHigh - 0.2, cgamma_highlights);
    cgamma_highlights = mix(softClampLow, cgamma_highlights, lowBlend);
    cgamma_highlights = mix(softClampHigh, cgamma_highlights, highBlend);
    float gammaCorrected = pow(normalizedInput, 1.0 / cgamma_highlights);
    float contrastBlend = smoothstep(0.8, 1.2, cgamma_highlights);
    float contrastStrength = mix(1.0, 3.3, contrastBlend);
    float midpoint = 0.5;
    float sigmoid_mid = 1.0 / (1.0 + exp(-contrastStrength * (gammaCorrected - midpoint)));
    float sigmoid_0 = 1.0 / (1.0 + exp(-contrastStrength * (0.0 - midpoint)));
    float sigmoid_1 = 1.0 / (1.0 + exp(-contrastStrength * (1.0 - midpoint)));
    float result = (sigmoid_mid - sigmoid_0) / (sigmoid_1 - sigmoid_0);
    float darkBlend = smoothstep(0.02, 0.0, gammaCorrected);
    float minOutput = gammaCorrected * 0.1;
    result = mix(result, max(result, minOutput), darkBlend);
    // Remove HDR blending toward white for under frame to preserve highlights (keep darker)
    // The normalization and sigmoidal contrast already handle compression
    return result;
}

void main() {
    ivec2 xyPos = ivec2(gl_FragCoord.xy);
    
    // Read linear RGB
    vec3 rgb = texelFetch(rgbInput, xyPos, 0).rgb;
    
    // Compute luminance using Rec. 709 coefficients
    float luma = 0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b;
    
    // Handle negative values (shouldn't happen but be safe)
    luma = max(luma, 0.0);
    
    // Apply tone mapping based on frame type
    // Using Reinhard/Gamma/ACES functions (input is already HDR, no exposure multiplication)
    float toneMapped;
    
    if (frameType == 0) {
        // Under: GammaBased - very low contrast, perfect highlight preservation
        toneMapped = gammaBasedTonemap(luma, baselineExposure);
    } else if (frameType == 1) {
        // Center: Reinhard - low contrast, somewhat preserved highlights
        toneMapped = reinhardTonemap(luma, baselineExposure);
    } else {
        // Over: ACES - great contrast, clipped highlights
        toneMapped = acesTonemap(luma, baselineExposure);
    }
    
    result = smoothClamp(toneMapped, 0.0, 1.0, 0.001);
}
