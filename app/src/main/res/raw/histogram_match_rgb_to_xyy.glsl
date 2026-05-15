#version 300 es

precision highp float;

// xyY input texture (intermediate format)
uniform sampler2D xyYInput;

// Histogram matching control points (from HistogramMatch)
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

// Color space conversion matrix
uniform mat3 XYZtoProPhoto;

out vec4 result;

#include gamma
#include xyytoxyz

// =============================================================================
// Histogram Matching: RGB-space exposure + xyY-space color preservation
//
// This shader uses a hybrid approach:
// 1. Converts xyY => RGB
// 2. Applies histogram matching in RGB space (to get correct exposure)
// 3. Extracts the luminance adjustment from RGB-space matching
// 4. Applies that adjustment in xyY space (to preserve color and saturation)
// 5. Converts xyY => RGB for output
//
// Process:
// 1. Decode xyY from texture
// 2. Convert xyY => XYZ => RGB
// 3. Calculate luminance from RGB
// 4. Apply histogram matching curve to RGB luminance (in gamma space)
// 5. Calculate luminance adjustment ratio
// 6. Apply adjustment to Y in xyY space (preserving xy chromaticity)
// 7. Convert xyY => XYZ => RGB
// 8. Output RGB
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
    float x = xyY.x;  // Chromaticity x (will be preserved)
    float y = xyY.y;  // Chromaticity y (will be preserved)
    float Y = xyY.z;  // Luminance (will be modified)
    
    // Avoid processing near-zero luminance
    if (Y < 0.0001) {
        // Still convert to RGB even for near-zero luminance
        vec3 XYZ = xyYtoXYZ(xyY);
        vec3 proPhoto = XYZtoProPhoto * XYZ;
        proPhoto = max(proPhoto, vec3(0.0));
        result = vec4(proPhoto, 1.0);
        return;
    }
    
    // Step 1: Convert xyY => XYZ => RGB to get RGB values
    vec3 XYZ = xyYtoXYZ(xyY);
    vec3 rgb = XYZtoProPhoto * XYZ;
    rgb = max(rgb, vec3(0.0));  // Ensure non-negative
    
    // Step 2: Calculate exposure adjustment using RGB-space histogram matching
    // (This gives us the correct exposure, matching what works well in RGB space)
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    
    // Normalize for histogram matching
    float normalizedLuma = luma;
    if (histMatchMaxHdr > 1.0 && luma > 1.0) {
        // HDR value: normalize by maxHdr for curve evaluation
        normalizedLuma = luma / histMatchMaxHdr;
    }
    normalizedLuma = max(normalizedLuma, 0.0);
    
    // Convert to gamma space for curve evaluation (matches how RAW CDF was built)
    float gammaLuma = gammaEncode(normalizedLuma);
    
    // Evaluate histogram matching curve
    float targetGammaLuma = evaluateToneCurve(gammaLuma);
    
    // Convert target back to linear space
    float targetLinearLuma = gammaDecode(targetGammaLuma);
    
    // Calculate the luminance adjustment ratio (this is the exposure correction)
    float lumaScale = targetLinearLuma / max(normalizedLuma, 0.0001);
    
    // Apply histogram matching strength (blend with original)
    lumaScale = mix(1.0, lumaScale, histMatchStrength);
    
    // Step 3: Apply this adjustment in xyY space to preserve color and saturation
    // The luminance adjustment ratio (lumaScale) was calculated from RGB-space matching
    // We apply it to Y while preserving xy chromaticity
    float newY = Y * lumaScale;
    newY = max(newY, 0.0);
    
    // Preserve xy chromaticity, update Y
    vec3 newXyY = vec3(x, y, newY);
    
    // Minimal validation - only fix truly invalid chromaticity
    if (newXyY.y <= 0.0 || (newXyY.x + newXyY.y) >= 1.0) {
        // Only fix if truly invalid, use original xy if possible
        if (y > 0.0 && (x + y) < 1.0) {
            // Use original xy if it was valid
            newXyY = vec3(x, y, newY);
        } else {
            // Fallback to neutral gray (D65 white point) only if absolutely necessary
            newXyY = vec3(0.3127, 0.3290, newY);
        }
    }
    
    // Step 5: Convert xyY => XYZ => RGB for output
    vec3 newXYZ = xyYtoXYZ(newXyY);
    vec3 proPhoto = XYZtoProPhoto * newXYZ;
    proPhoto = max(proPhoto, vec3(0.0));
    
    result = vec4(proPhoto, 1.0);
}
