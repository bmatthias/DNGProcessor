#version 300 es

precision highp float;

// xyY input texture (intermediate format)
uniform sampler2D xyYInput;

// Histogram matching control points (from ToneMap)
uniform float histMatchControlInputs[9];   // Input positions [0,1] for control points
uniform float histMatchControlOutputs[9]; // Output values [0,1] for control points
uniform float histMatchControlTangents[9]; // Tangents (slopes) at control points
uniform int histMatchNumControlPoints;

// Baseline exposure multiplier (2^EV)
uniform float baselineExposure;

// Histogram matching strength (0.0 = no matching, 1.0 = full matching)
uniform float histMatchStrength;

// Maximum HDR value for normalization
uniform float histMatchMaxHdr;

out vec4 result;

#include gamma

// =============================================================================
// Histogram Matching in xyY Space
//
// This shader applies histogram matching to the Y (luminance) channel of xyY
// while preserving xy chromaticity. This ensures color saturation is maintained.
//
// Process:
// 1. Decode xyY from texture
// 2. Extract Y (luminance) - already in linear space
// 3. Convert Y to gamma space for curve evaluation
// 4. Apply histogram matching curve (evaluateToneCurve)
// 5. Convert target Y back to linear space
// 6. Scale Y by ratio (targetLinearY / originalLinearY)
// 7. Preserve xy chromaticity
// 8. Encode and output modified xyY
// =============================================================================

// Decode HDR xyY from texture sample
vec3 decodeHDRxyY(vec4 encoded) {
    if (encoded.w >= 0.9999) {
        return vec3(encoded.x, encoded.y, encoded.z);
    }
    float invScale = max(encoded.w, 0.0001);
    return vec3(encoded.x, encoded.y, encoded.z / invScale);
}

// Evaluate histogram matching tone curve (Hermite interpolation)
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
    
    // Normalize Y for histogram matching (handle HDR values)
    // The curve expects values in [0, 1] in gamma space
    float normalizedY = Y;
    if (histMatchMaxHdr > 1.0) {
        normalizedY = min(Y / histMatchMaxHdr, 1.0);
    }
    
    // Convert to gamma space for curve evaluation (matches how RAW CDF was built)
    float gammaY = gammaEncode(normalizedY);
    
    // Evaluate histogram matching curve
    float targetGammaY = evaluateToneCurve(gammaY);
    
    // Convert target back to linear space
    float targetLinearY = gammaDecode(targetGammaY);
    
    // Scale original Y by the ratio to preserve color relationships
    // This maintains exact color ratios (hue and saturation) while adjusting brightness
    float yScale = targetLinearY / max(normalizedY, 0.0001);
    
    // Apply histogram matching strength (blend with original)
    yScale = mix(1.0, yScale, histMatchStrength);
    
    // Apply scale to original Y (restore HDR scale if needed)
    float newY = Y * yScale;
    if (histMatchMaxHdr > 1.0) {
        newY = min(newY, histMatchMaxHdr);
    }
    
    // Preserve xy chromaticity, update Y
    vec3 newXyY = vec3(x, y, newY);
    
    // Encode back to texture format
    // Use same encoding as input (check if HDR encoding is needed)
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
