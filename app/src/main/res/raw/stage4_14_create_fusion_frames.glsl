#version 300 es

precision highp float;

// Intermediate xyY input (already has baselineExposure applied when method 17)
uniform sampler2D intermediate;

// Baseline exposure multiplier
uniform float baselineExposure;

// Frame type: 0=Reinhard, 1=ACES Filmic Soft, 2=Gamma Based
// All three are tone-mapped to similar ranges, making Laplacian pyramid more stable
uniform int frameType;

out vec3 result;  // Output RGB instead of just luma

// Helper functions from stage1 (simplified for single-channel)
vec3 applyBaselineExposureReinhard(vec3 rgb, float baselineExposure) {
    vec3 multiplied = rgb * baselineExposure;
    return multiplied / (vec3(1.0) + multiplied);
}

vec3 applyBaselineExposureACESFilmicSoft(vec3 rgb, float baselineExposure) {
    float maxChannel = max(max(rgb.r, rgb.g), rgb.b);
    float hdrScale = max(maxChannel, 1.0);
    vec3 normalizedRgb = rgb / hdrScale;
    vec3 x = normalizedRgb * baselineExposure;
    float a = 2.0;
    float b = 0.03;
    float c = 2.0;
    float d = 0.65;
    float e = 0.18;
    vec3 result = (x * (a * x + b)) / (x * (c * x + d) + e);
    float maxX = baselineExposure;
    float maxY = (maxX * (a * maxX + b)) / (maxX * (c * maxX + d) + e);
    // Smooth normalization blend instead of hard if-statement
    float normBlend = smoothstep(0.0005, 0.002, maxY);
    result = mix(result, result * (1.0 / max(maxY, 0.001)), normBlend);
    // Smooth HDR blend using smoothstep - start transition at 1.0 so non-HDR pixels get clean blend=0
    // Range [1.0, 1.3] ensures smooth transition for HDR values without affecting SDR
    float hdrBlend = smoothstep(1.0, 1.3, hdrScale);
    float excess = max(hdrScale - 1.0, 0.0);
    float compressedExcess = excess / (1.0 + excess);
    result = mix(result, mix(result, vec3(1.0), compressedExcess * 0.3), hdrBlend);
    return result;
}

vec3 applyBaselineExposureGammaBased(vec3 rgb, float baselineExposure) {
    float maxChannel = max(max(rgb.r, rgb.g), rgb.b);
    float hdrScale = max(maxChannel, 1.0);
    vec3 normalizedInput = rgb / hdrScale;
    // Calculate gamma from baselineExposure multiplier to produce equivalent exposure lifting
    // baselineExposure is a multiplier (2^EV), not EV directly
    // Convert multiplier to EV: EV = log2(baselineExposure)
    // Then use gamma = 1 + EV to approximate equivalent exposure lifting
    // - baselineExposure = 1.0 (EV=0): gamma = 1.0 → pow(x, 1.0) = x (correct)
    // - baselineExposure = 2.0 (EV=1): gamma = 2.0 → pow(x, 0.5) ≈ x * 1.41 (close to x * 2)
    // - baselineExposure = 8.0 (EV=3): gamma = 4.0 → pow(x, 0.25) ≈ x * 1.19 (close to x * 8)
    float ev = log2(baselineExposure);
    float cgamma_highlights = 1.0 + ev;
    // Soft clamp using smoothstep to avoid hard banding at clamp boundaries
    // Blend toward limits gradually instead of hard clamp
    float softClampLow = 0.5;
    float softClampHigh = 3.0;
    float lowBlend = smoothstep(softClampLow - 0.2, softClampLow + 0.2, cgamma_highlights);
    float highBlend = smoothstep(softClampHigh + 0.2, softClampHigh - 0.2, cgamma_highlights);
    cgamma_highlights = mix(softClampLow, cgamma_highlights, lowBlend);
    cgamma_highlights = mix(softClampHigh, cgamma_highlights, highBlend);
    vec3 gammaCorrected = pow(normalizedInput, vec3(1.0 / cgamma_highlights));
    // Smooth transition for contrast strength based on gamma
    // Use smoothstep instead of hard if-statement to avoid banding
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
        // Soft minimum output blend for very dark values to avoid hard edge at 0.01
        // Use smoothstep to create gradual transition instead of hard threshold
        float darkBlend = smoothstep(0.02, 0.0, val);
        float minOutput = val * 0.1;
        result[i] = mix(result[i], max(result[i], minOutput), darkBlend);
    }
    // Smooth HDR blend using smoothstep - start transition at 1.0 so non-HDR pixels get clean blend=0
    // Range [1.0, 1.3] ensures smooth transition for HDR values without affecting SDR
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
    // Use smoothstep for all weight calculations - no if-statements needed
    // ACES weight: higher for darker regions, fades out at 0.5
    float acesWeight = smoothstep(0.5, 0.2, inputLuminance) * 0.5;
    // Gamma weight: higher for brighter regions, fades in at 0.5
    float gammaWeight = smoothstep(0.5, 1.0, inputLuminance) * 0.5;
    // Reinhard fills in the rest
    float reinhardWeight = 1.0 - (acesWeight + gammaWeight);
    return reinhardResult * reinhardWeight + acesResult * acesWeight + gammaResult * gammaWeight;
}

// Convert xyY to linear RGB (CIE XYZ D50)
vec3 xyYToRGB(float x, float y, float Y) {
    // Avoid division by zero
    if (y < 0.0001) {
        return vec3(0.0);
    }
    
    // Convert xyY to XYZ
    float X = (Y / y) * x;
    float Z = (Y / y) * (1.0 - x - y);
    
    // XYZ D50 to linear RGB (simplified sRGB matrix)
    vec3 rgb;
    rgb.r = 3.2404542 * X - 1.5371385 * Y - 0.4985314 * Z;
    rgb.g = -0.9692660 * X + 1.8760108 * Y + 0.0415560 * Z;
    rgb.b = 0.0556434 * X - 0.2040259 * Y + 1.0572252 * Z;
    
    return max(rgb, vec3(0.0));  // Clamp negatives
}

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    
    // Decode intermediate xyY format
    vec4 xyYEnc = texelFetch(intermediate, xyCenter, 0);
    float invScale = max(xyYEnc.w, 0.001);
    float Y = xyYEnc.z / invScale;  // Decoded luminance
    
    // Debug: Check for invalid values
    if (isnan(Y) || isinf(Y)) {
        result = vec3(0.0);
        return;
    }
    
    // Debug: Check baselineExposure
    if (baselineExposure <= 0.0 || isnan(baselineExposure) || isinf(baselineExposure)) {
        vec3 rgb = xyYToRGB(xyYEnc.x, xyYEnc.y, Y);
        result = rgb;
        return;
    }
    
    // When baselineExposure == 1.0, no HDR compression needed
    if (abs(baselineExposure - 1.0) < 0.001) {
        vec3 rgb = xyYToRGB(xyYEnc.x, xyYEnc.y, Y);
        result = rgb;
        return;
    }
    
    // Convert xyY to RGB for proper tone mapping
    vec3 originalRgb = xyYToRGB(xyYEnc.x, xyYEnc.y, Y);
    
    // Apply tone mapping to RGB (maintains color ratios)
    vec3 toneMapped;
    if (frameType == 0) {
        // Reinhard
        toneMapped = applyBaselineExposureReinhard(originalRgb, baselineExposure);
    } else if (frameType == 1) {
        // ACES Filmic Soft
        toneMapped = applyBaselineExposureACESFilmicSoft(originalRgb, baselineExposure);
    } else {
        // Gamma Based
        toneMapped = applyBaselineExposureGammaBased(originalRgb, baselineExposure);
    }
    
    // Output tone-mapped RGB directly
    result = max(toneMapped, vec3(0.0));
}
