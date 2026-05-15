#version 300 es

precision highp float;

// Original xyY input (for chromaticity preservation)
uniform sampler2D xyYInput;

// Merged luma from exposure fusion (tone-mapped, in [0,1])
uniform sampler2D mergedLuma;

// Baseline exposure multiplier (2^EV)
uniform float baselineExposure;

out vec4 result;

// =============================================================================
// Reconstruct xyY from original xyY chromaticity and merged luma
//
// This shader:
// 1. Reads original xyY (preserves original chromaticity)
// 2. Reads merged luma (tone-mapped Y from fusion)
// 3. Reconstructs xyY with original chromaticity (xy) + merged luma (Y)
// 4. Outputs xyY (for HistogramMatch to convert to RGB later)
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
    
    // Validate chromaticity
    if (reconstructedXyY.y <= 0.0 || (reconstructedXyY.x + reconstructedXyY.y) >= 1.0) {
        // Fallback to neutral gray (D65 white point)
        reconstructedXyY = vec3(0.3127, 0.3290, mergedY);
    }
    
    // Encode back to texture format
    // Use same encoding as input (check if HDR encoding is needed)
    float hdrScale = max(baselineExposure, 1.0);
    if (mergedY > 1.0 && hdrScale > 1.0) {
        // HDR encoding: store Y/hdrScale in z, 1/hdrScale in w
        float scale = 1.0 / hdrScale;
        result = vec4(reconstructedXyY.x, reconstructedXyY.y, mergedY * scale, scale);
    } else {
        // No encoding needed: values are in [0, 1]
        result = vec4(reconstructedXyY.x, reconstructedXyY.y, mergedY, 1.0);
    }
}
