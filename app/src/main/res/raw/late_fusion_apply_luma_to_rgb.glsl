#version 300 es

precision highp float;

// Plain RGB input (from xyY => RGB conversion, no fusion)
uniform sampler2D rgbInput;

// Merged luma from exposure fusion (tone-mapped, in [0,1])
uniform sampler2D mergedLuma;

// Original xyY input (needed to extract original luma for scaling)
uniform sampler2D xyYInput;

out vec4 result;

// =============================================================================
// Apply merged luma to plain RGB conversion result
//
// This shader:
// 1. Reads plain RGB (from xyY => RGB conversion)
// 2. Extracts original luminance (Y) from xyY
// 3. Computes scale factor (mergedLuma / originalLuma)
// 4. Scales RGB by the scale factor
// 5. Outputs scaled RGB
//
// This preserves color ratios (hue and saturation) while applying the
// exposure fusion result from the luma-space merge.
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
    
    // Read plain RGB (from xyY => RGB conversion, linear ProPhoto, can be HDR > 1.0)
    vec3 rgb = texelFetch(rgbInput, xyPos, 0).rgb;
    
    // Read merged luma (single-channel, tone-mapped, in [0,1])
    float mergedY = texelFetch(mergedLuma, xyPos, 0).r;
    
    // Compute luma of plain RGB (should match original xyY luma)
    float rgbLuma = 0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b;
    rgbLuma = max(rgbLuma, 0.0);
    
    // Compute scale factor: mergedY (tone-mapped) / rgbLuma (HDR)
    // This applies the tone mapping from the fusion to the RGB
    // Use log space computation to reduce Float16 quantization amplification
    const float minLuma = 0.0001;  // Avoid log(0)
    float logMergedY = log(max(mergedY, minLuma));
    float logRgbLuma = log(max(rgbLuma, minLuma));
    float scale = exp(logMergedY - logRgbLuma);
    
    // Clamp scale to prevent extreme values
    // For HDR values, scale should be < 1.0 (tone mapping compresses)
    // For shadows, scale might be > 1.0 (brightening)
    scale = clamp(scale, 0.0, 10.0);
    
    // Scale RGB uniformly to preserve color ratios
    vec3 scaledRgb = rgb * scale;
    
    result = vec4(scaledRgb, 1.0);
}
