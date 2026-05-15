#version 300 es

precision highp float;

// xyY input texture (intermediate format)
uniform sampler2D xyYInput;

// Baseline exposure multiplier (2^EV)
uniform float baselineExposure;

// Frame type: 0 = Gamma Based (under), 1 = Reinhard (center), 2 = ACES Filmic Soft (over)
uniform int frameType;

out float result;


// =============================================================================
// Extract luma from xyY and apply tone mapping for exposure fusion
//
// Three frames with different tone curves:
// - Frame 0 (Gamma Based): Very low contrast, perfect highlight preservation (under)
// - Frame 1 (Reinhard): Low contrast, somewhat preserved highlights (center)
// - Frame 2 (ACES Filmic Soft): Great contrast, clipped highlights (over)
// =============================================================================

// Decode HDR xyY from texture sample
vec3 decodeHDRxyY(vec4 encoded) {
    if (encoded.w >= 0.9999) {
        return vec3(encoded.x, encoded.y, encoded.z);
    }
    float invScale = max(encoded.w, 0.0001);
    return vec3(encoded.x, encoded.y, encoded.z / invScale);
}

// Tone mapping functions that work directly on luma (Y channel of xyY)
// These work on already-HDR input (already multiplied by baselineExposure in preprocess)
// Note: baselineExposure is still passed for gamma calculation in GammaBased, but input is NOT multiplied

float applyBaselineExposureReinhardLuma(float luma, float baselineExposure) {
    // Input is already HDR, so just apply Reinhard compression
    return luma / (1.0 + luma);
}

float applyBaselineExposureACESFilmicSoftLuma(float luma, float baselineExposure) {
    // Input is already HDR, apply ACES directly
    float a = 2.0;
    float b = 0.03;
    float c = 2.0;
    float d = 0.65;
    float e = 0.18;
    float result = (luma * (a * luma + b)) / (luma * (c * luma + d) + e);
    // Normalize if needed
    float hdrScale = max(luma, 1.0);
    if (hdrScale > 1.0) {
        float hdrBlend = smoothstep(1.0, 1.3, hdrScale);
        float excess = max(hdrScale - 1.0, 0.0);
        float compressedExcess = excess / (1.0 + excess);
        result = mix(result, mix(result, 1.0, compressedExcess * 0.3), hdrBlend);
    }
    return result;
}

float applyBaselineExposureGammaBasedLuma(float luma, float baselineExposure) {
    // GammaBased creates an "under" frame by using gamma correction instead of multiplying by baselineExposure
    // Since input is already multiplied by baselineExposure, we need to divide first to avoid double exposure
    float originalLuma = luma / baselineExposure;
    
    // Now apply gamma correction to create the under frame
    float hdrScale = max(originalLuma, 1.0);
    float normalizedInput = originalLuma / hdrScale;
    float ev = log2(baselineExposure);
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
    
    // Read and decode xyY
    vec4 encoded = texelFetch(xyYInput, xyPos, 0);
    vec3 xyY = decodeHDRxyY(encoded);
    
    // Extract Y (luminance) from xyY
    float luma = xyY.z;
    luma = max(luma, 0.0);  // Handle negative values
    
    // Apply tone mapping directly to luma based on frame type
    float toneMappedLuma;
    
    if (frameType == 0) {
        // Gamma Based: Very low contrast, perfect highlight preservation (under frame)
        toneMappedLuma = applyBaselineExposureGammaBasedLuma(luma, baselineExposure);
    } else if (frameType == 1) {
        // Reinhard: Low contrast, somewhat preserved highlights (center frame)
        toneMappedLuma = applyBaselineExposureReinhardLuma(luma, baselineExposure);
    } else {
        // ACES Filmic Soft: Great contrast, clipped highlights (over frame)
        toneMappedLuma = applyBaselineExposureACESFilmicSoftLuma(luma, baselineExposure);
    }
    
    result = clamp(toneMappedLuma, 0.0, 1.0);
}
