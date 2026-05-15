#version 300 es

precision highp float;

// xyY input texture (intermediate format)
uniform sampler2D xyYInput;

// Color space conversion matrices
uniform mat3 XYZtoProPhoto;

// Baseline exposure multiplier (2^EV)
uniform float baselineExposure;

out vec4 result;

#include xyytoxyz

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

// Smooth clamp for vec3 (applies to each channel)
vec3 smoothClampVec3(vec3 x, float minVal, float maxVal, float transitionWidth) {
    return vec3(
        smoothClamp(x.r, minVal, maxVal, transitionWidth),
        smoothClamp(x.g, minVal, maxVal, transitionWidth),
        smoothClamp(x.b, minVal, maxVal, transitionWidth)
    );
}

// =============================================================================
// Convert xyY to ProPhoto RGB with gammaACESFusionCompress (like stage3)
//
// This shader:
// 1. Decodes HDR xyY from texture (already multiplied by baselineExposure)
// 2. Converts xyY to XYZ
// 3. Converts XYZ to ProPhoto RGB
// 4. Applies gammaACESFusionCompress (prevents banding, matches stage3)
//    Note: baselineExposure is passed as uniform for gamma calculation, but
//    RGB is NOT multiplied by it again (input is already HDR)
//
// Used for plain conversion (e.g., for UHDR gain map creation)
// =============================================================================

// Decode HDR xyY from texture sample
vec3 decodeHDRxyY(vec4 encoded) {
    if (encoded.w >= 0.9999) {
        return vec3(encoded.x, encoded.y, encoded.z);
    }
    float invScale = max(encoded.w, 0.0001);
    return vec3(encoded.x, encoded.y, encoded.z / invScale);
}

// Helper functions for gammaACESFusionCompress (from stage3)
vec3 reinhardCompress_GammaFusion(vec3 rgb) {
    return rgb / (vec3(1.0) + rgb);
}

vec3 acesFilmicSoftCompress_GammaFusion(vec3 x, float maxVal) {
    float a = 2.51;
    float b = 0.03;
    float c = 2.43;
    float d = 0.59;
    float e = 0.14;
    return (x * (a * x + b)) / (x * (c * x + d) + e);
}

vec3 gammaBasedCompress_GammaFusion(vec3 rgb) {
    float hdrScale = max(baselineExposure, 1.0);
    vec3 normalizedInput = rgb / hdrScale;
    float cgamma = clamp(baselineExposure, 0.5, 3.0);
    vec3 gammaCorrected = pow(normalizedInput, vec3(1.0 / cgamma));
    float contrastStrength = 1.0;
    if (cgamma > 1.0) {
        contrastStrength = 1.0 + (cgamma - 1.0) * 1.8;
        contrastStrength *= 3.3;
    }
    contrastStrength = min(contrastStrength, 6.0);
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
    if (hdrScale > 1.0) {
        float excess = hdrScale - 1.0;
        float compressedExcess = excess / (1.0 + excess);
        result = mix(result, vec3(1.0), compressedExcess * 0.3);
    }
    return result;
}

vec3 gammaACESFusionCompress(vec3 rgb, float maxVal) {
    vec3 reinhardResult = reinhardCompress_GammaFusion(rgb);
    vec3 acesResult = acesFilmicSoftCompress_GammaFusion(rgb, maxVal);
    vec3 gammaResult = gammaBasedCompress_GammaFusion(rgb);
    float inputLuminance = dot(reinhardResult, vec3(0.2126, 0.7152, 0.0722));
    float acesWeight = 0.0;
    if (inputLuminance < 0.5) {
        acesWeight = smoothstep(0.5, 0.2, inputLuminance) * 0.5;
    }
    float gammaWeight = 0.0;
    if (inputLuminance > 0.5) {
        gammaWeight = smoothstep(0.5, 1.0, inputLuminance) * 0.5;
    }
    float reinhardWeight = 1.0 - (acesWeight + gammaWeight);
    vec3 result = reinhardResult * reinhardWeight + acesResult * acesWeight + gammaResult * gammaWeight;
    // Smooth clamp to prevent banding
    result = smoothClampVec3(result, 0.0, 1.0, 0.001);
    return result;
}

void main() {
    ivec2 xyPos = ivec2(gl_FragCoord.xy);
    
    // Read and decode xyY
    vec4 encoded = texelFetch(xyYInput, xyPos, 0);
    vec3 xyY = decodeHDRxyY(encoded);
    
    // Validate xyY before conversion
    if (xyY.x + xyY.y > 1.0) {
        float sum = xyY.x + xyY.y;
        xyY.x = xyY.x / sum * 0.99;
        xyY.y = xyY.y / sum * 0.99;
    }
    if (xyY.y <= 0.0) {
        xyY.x = 0.3127;
        xyY.y = 0.3290;
    }
    
    // Convert xyY to XYZ
    vec3 XYZ = xyYtoXYZ(xyY);
    
    // Convert XYZ to ProPhoto RGB
    vec3 proPhoto = XYZtoProPhoto * XYZ;
    proPhoto = max(proPhoto, vec3(0.0));  // Clamp negatives
    
    // Note: xyY input is already HDR (multiplied by baselineExposure in stage3)
    // So we don't multiply by baselineExposure again here
    
    // Apply gammaACESFusionCompress to prevent banding (matches stage3)
    // This compresses HDR values to [0,1] range
    float maxChannel = max(max(proPhoto.r, proPhoto.g), proPhoto.b);
    vec3 compressed = gammaACESFusionCompress(proPhoto, maxChannel);
    
    result = vec4(compressed, 1.0);
}
