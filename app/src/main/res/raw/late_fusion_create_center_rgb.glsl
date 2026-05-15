#version 300 es

precision highp float;

// Plain RGB input (from xyY => RGB conversion, no fusion)
uniform sampler2D rgbInput;

// Baseline exposure multiplier (2^EV)
uniform float baselineExposure;

out vec4 result;

// =============================================================================
// Create center tone-mapped RGB frame using gammaACES compression
//
// This applies the standard gammaACES tone mapping to the plain RGB conversion.
// This frame is stable (already tone-mapped to [0,1]) and can be used as the base
// for scaling by luminance ratio, avoiding division/multiplication errors.
// =============================================================================

// Helper functions for gammaACES tone mapping
// These work on already-HDR input (already multiplied by baselineExposure in preprocess)
// Note: baselineExposure is still passed for gamma calculation in GammaBased, but input is NOT multiplied

vec3 applyBaselineExposureReinhard(vec3 rgb, float baselineExposure) {
    // Input is already HDR, so just apply Reinhard compression
    return rgb / (vec3(1.0) + rgb);
}

vec3 applyBaselineExposureACESFilmicSoft(vec3 rgb, float baselineExposure) {
    // Input is already HDR, apply ACES directly
    float a = 2.0;
    float b = 0.03;
    float c = 2.0;
    float d = 0.65;
    float e = 0.18;
    vec3 result = (rgb * (a * rgb + b)) / (rgb * (c * rgb + d) + e);
    float maxChannel = max(max(rgb.r, rgb.g), rgb.b);
    float hdrScale = max(maxChannel, 1.0);
    if (hdrScale > 1.0) {
        float hdrBlend = smoothstep(1.0, 1.3, hdrScale);
        float excess = max(hdrScale - 1.0, 0.0);
        float compressedExcess = excess / (1.0 + excess);
        result = mix(result, mix(result, vec3(1.0), compressedExcess * 0.3), hdrBlend);
    }
    return result;
}

vec3 applyBaselineExposureGammaBased(vec3 rgb, float baselineExposure) {
    // Input is already HDR, but we still use baselineExposure for gamma calculation
    float maxChannel = max(max(rgb.r, rgb.g), rgb.b);
    float hdrScale = max(maxChannel, 1.0);
    vec3 normalizedInput = rgb / hdrScale;
    float ev = log2(baselineExposure);
    float cgamma_highlights = 1.0 + ev;
    float softClampLow = 0.5;
    float softClampHigh = 3.0;
    float lowBlend = smoothstep(softClampLow - 0.2, softClampLow + 0.2, cgamma_highlights);
    float highBlend = smoothstep(softClampHigh + 0.2, softClampHigh - 0.2, cgamma_highlights);
    cgamma_highlights = mix(softClampLow, cgamma_highlights, lowBlend);
    cgamma_highlights = mix(softClampHigh, cgamma_highlights, highBlend);
    vec3 gammaCorrected = pow(normalizedInput, vec3(1.0 / cgamma_highlights));
    float contrastBlend = smoothstep(0.8, 1.2, cgamma_highlights);
    float contrastStrength = mix(1.0, 3.3, contrastBlend);
    float midpoint = 0.5;
    vec3 result;
    for (int i = 0; i < 3; i++) {
        float val = gammaCorrected[i];
        float sigmoid_mid = 1.0 / (1.0 + exp(-contrastStrength * (val - midpoint)));
        float sigmoid_0 = 1.0 / (1.0 + exp(-contrastStrength * (0.0 - midpoint)));
        float sigmoid_1 = 1.0 / (1.0 + exp(-contrastStrength * (1.0 - midpoint)));
        result[i] = (sigmoid_mid - sigmoid_0) / (sigmoid_1 - sigmoid_0);
        float darkBlend = smoothstep(0.02, 0.0, val);
        float minOutput = val * 0.1;
        result[i] = mix(result[i], max(result[i], minOutput), darkBlend);
    }
    float hdrBlend = smoothstep(1.0, 1.3, hdrScale);
    float excess = max(hdrScale - 1.0, 0.0);
    float compressedExcess = excess / (1.0 + excess);
    result = mix(result, mix(result, vec3(1.0), compressedExcess * 0.3), hdrBlend);
    return result;
}

vec3 applyBaselineExposureGammaACESFusion(vec3 rgb, float baselineExposure) {
    vec3 reinhardResult = applyBaselineExposureReinhard(rgb, baselineExposure);
    vec3 acesResult = applyBaselineExposureACESFilmicSoft(rgb, baselineExposure);
    vec3 gammaResult = applyBaselineExposureGammaBased(rgb, baselineExposure);
    
    float inputLuminance = dot(reinhardResult, vec3(0.2126, 0.7152, 0.0722));
    float acesWeight = smoothstep(0.5, 0.2, inputLuminance) * 0.5;
    float gammaWeight = smoothstep(0.5, 1.0, inputLuminance) * 0.5;
    float reinhardWeight = 1.0 - (acesWeight + gammaWeight);
    return reinhardResult * reinhardWeight + acesResult * acesWeight + gammaResult * gammaWeight;
}

void main() {
    ivec2 xyPos = ivec2(gl_FragCoord.xy);
    
    // Read plain RGB (from xyY => RGB conversion, linear ProPhoto, can be HDR)
    vec3 rgb = texelFetch(rgbInput, xyPos, 0).rgb;
    
    // Apply gammaACES tone mapping (standard compression method)
    // This produces a non-HDR [0,1] reference frame
    vec3 toneMappedRgb = applyBaselineExposureGammaACESFusion(rgb, baselineExposure);
    
    // Output tone-mapped RGB (already in [0,1] range, no clamping needed)
    result = vec4(toneMappedRgb, 1.0);
}
