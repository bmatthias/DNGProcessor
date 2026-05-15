#version 300 es

precision highp float;

// Original xyY input (for chromaticity preservation)
uniform sampler2D xyYInput;

// Merged luma from exposure fusion (tone-mapped, in [0,1])
uniform sampler2D mergedLuma;

// Color space conversion matrices
uniform mat3 XYZtoProPhoto;

out vec4 result;

#include xyytoxyz

// =============================================================================
// Reconstruct RGB from original xyY chromaticity and merged luma
//
// This shader:
// 1. Reads original xyY (preserves original chromaticity)
// 2. Reads merged luma (tone-mapped Y from fusion)
// 3. Reconstructs xyY with original chromaticity (xy) + merged luma (Y)
// 4. Converts xyY to XYZ to RGB
// 5. Outputs RGB with preserved color saturation
//
// This preserves color by using the original chromaticity instead of scaling
// tone-mapped RGB, which can lose saturation.
// =============================================================================

// Decode HDR xyY from texture sample
vec3 decodeHDRxyY(vec4 encoded) {
    if (encoded.w >= 0.9999) {
        return vec3(encoded.x, encoded.y, encoded.z);
    }
    float invScale = max(encoded.w, 0.0001);
    return vec3(encoded.x, encoded.y, encoded.z / invScale);
}

void main() {
    ivec2 xyPos = ivec2(gl_FragCoord.xy);
    
    // Read original xyY (preserves original chromaticity)
    vec4 encoded = texelFetch(xyYInput, xyPos, 0);
    vec3 originalXyY = decodeHDRxyY(encoded);
    
    // Read merged luma (tone-mapped, in [0,1])
    float mergedY = texelFetch(mergedLuma, xyPos, 0).r;
    mergedY = max(mergedY, 0.0);
    
    // Reconstruct xyY with original chromaticity (xy) + merged luma (Y)
    // This preserves the original color saturation
    vec3 reconstructedXyY = vec3(originalXyY.x, originalXyY.y, mergedY);
    
    // Validate chromaticity before conversion
    // Invalid chromaticity (x + y > 1.0 or y = 0) can cause negative Z
    vec3 validXyY = reconstructedXyY;
    if (reconstructedXyY.y <= 0.0 || (reconstructedXyY.x + reconstructedXyY.y) >= 1.0) {
        // Fallback to neutral gray (D65 white point)
        validXyY = vec3(0.3127, 0.3290, mergedY);
    }
    
    // Convert xyY to XYZ
    vec3 XYZ = xyYtoXYZ(validXyY);
    
    // Convert XYZ to ProPhoto RGB
    vec3 proPhoto = XYZtoProPhoto * XYZ;
    
    // Clamp negatives only (values may exceed 1.0, which is fine)
    proPhoto = max(proPhoto, vec3(0.0));
    
    result = vec4(proPhoto, 1.0);
}
