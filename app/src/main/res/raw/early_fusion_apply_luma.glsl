#version 300 es

precision highp float;

// Original RGB texture (linear RGB from LinearRawPreProcess)
uniform sampler2D rgbInput;

// Merged luma from exposure fusion (tone-mapped, in [0,1])
uniform sampler2D mergedLuma;

// Debug mode: 0 = normal, 1 = show scale factor, 2 = show originalLuma, 3 = show quantization steps in scale
uniform int debugMode;

out vec4 result;

// =============================================================================
// Apply merged luma to original RGB
//
// This shader:
// 1. Reads original linear RGB
// 2. Computes original luminance
// 3. Scales RGB by the ratio (mergedLuma / originalLuma)
// 4. Outputs scaled RGB
//
// This preserves color ratios (hue and saturation) while applying the
// exposure fusion result from the xyY-space merge.
// =============================================================================

void main() {
    ivec2 xyPos = ivec2(gl_FragCoord.xy);
    
    // Read original linear RGB
    vec3 rgb = texelFetch(rgbInput, xyPos, 0).rgb;
    
    // Read merged luma (single-channel, tone-mapped)
    float mergedY = texelFetch(mergedLuma, xyPos, 0).r;
    
    // Compute original luminance using Rec. 709 coefficients
    float originalLuma = 0.2126 * rgb.r + 0.7152 * rgb.g + 0.0722 * rgb.b;
    
    // Compute scale factor (how much to adjust brightness)
    // BANDING FIX: Use log space computation to reduce Float16 quantization amplification
    // When originalLuma is large and varies smoothly, Float16 quantization creates small steps.
    // Direct division amplifies these steps. Solution: compute scale in log space which is more
    // stable and less sensitive to quantization errors.
    // scale = mergedY / originalLuma
    // log(scale) = log(mergedY) - log(originalLuma)
    // scale = exp(log(mergedY) - log(originalLuma))
    // This reduces sensitivity to small quantization errors in originalLuma.
    const float minLuma = 0.0001;  // Avoid log(0)
    float logMergedY = log(max(mergedY, minLuma));
    float logOriginalLuma = log(max(originalLuma, minLuma));
    float scale = exp(logMergedY - logOriginalLuma);
    
    // Clamp scale to prevent extreme values
    // Allow up to 10x boost for shadow recovery, minimum 0 for pure black
    scale = clamp(scale, 0.0, 10.0);
    
    // Debug output modes to visualize quantization
    if (debugMode == 1) {
        // Show scale factor (amplified for visibility)
        // Scale is typically small for highlights, so multiply to see steps
        float scaleVis = scale * 10.0;
        result = vec4(scaleVis, scaleVis, scaleVis, 1.0);
    } else if (debugMode == 2) {
        // Show originalLuma (normalized for display)
        float lumaVis = clamp(originalLuma / 20.0, 0.0, 1.0);
        result = vec4(lumaVis, lumaVis, lumaVis, 1.0);
    } else if (debugMode == 3) {
        // Show quantization steps in scale by computing second derivative
        // This amplifies quantization errors to make them visible
        ivec2 texSize = textureSize(rgbInput, 0);
        ivec2 left = ivec2(max(xyPos.x - 1, 0), xyPos.y);
        ivec2 right = ivec2(min(xyPos.x + 1, texSize.x - 1), xyPos.y);
        
        vec3 rgbLeft = texelFetch(rgbInput, left, 0).rgb;
        vec3 rgbRight = texelFetch(rgbInput, right, 0).rgb;
        
        float lumaLeft = 0.2126 * rgbLeft.r + 0.7152 * rgbLeft.g + 0.0722 * rgbLeft.b;
        float lumaRight = 0.2126 * rgbRight.r + 0.7152 * rgbRight.g + 0.0722 * rgbRight.b;
        
        // Use same log space computation as main calculation
        const float minLuma = 0.0001;
        float logMergedY = log(max(mergedY, minLuma));
        float scaleLeft = exp(logMergedY - log(max(lumaLeft, minLuma)));
        float scaleRight = exp(logMergedY - log(max(lumaRight, minLuma)));
        scaleLeft = clamp(scaleLeft, 0.0, 10.0);
        scaleRight = clamp(scaleRight, 0.0, 10.0);
        
        // Second derivative: difference of differences (detects quantization steps)
        float diffLeft = abs(scale - scaleLeft);
        float diffRight = abs(scaleRight - scale);
        float scaleStep = abs(diffRight - diffLeft);
        
        // Amplify quantization steps for visibility
        float stepVis = clamp(scaleStep * 10000.0, 0.0, 1.0);
        result = vec4(stepVis, stepVis, stepVis, 1.0);
    } else {
        // Normal output: Scale RGB uniformly to preserve color ratios
        vec3 scaledRgb = rgb * scale;
        result = vec4(scaledRgb, 1.0);
    }
}
