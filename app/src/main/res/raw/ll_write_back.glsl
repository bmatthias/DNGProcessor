#version 300 es
/*
 * Local Laplacian Filter - Write Back (Final Output)
 * Ported from darktable's locallaplacian.cl
 * 
 * This shader writes the processed luminance back to the output,
 * combining it with the original chromaticity channels.
 */

precision highp float;

uniform sampler2D input_tex;      // Original input (xyY format) for chromaticity
uniform sampler2D processed_tex;  // Processed luminance from Local Laplacian
uniform int max_supp;             // Padding offset to align with original
uniform int output_width;
uniform int output_height;

out vec4 result;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    int x = xy.x;
    int y = xy.y;
    
    if (x >= output_width || y >= output_height) {
        result = vec4(0.0);
        return;
    }
    
    // Read original pixel for chromaticity
    vec4 original = texelFetch(input_tex, xy, 0);
    
    // Read processed luminance (accounting for padding offset)
    float processedY = texelFetch(processed_tex, ivec2(x + max_supp, y + max_supp), 0).x;
    
    // Only clamp negatives - preserve HDR values > 1.0 for highlight detail
    // HDR compression happens later in the pipeline
    processedY = max(processedY, 0.0);
    
    // Reconstruct xyY output:
    // - Keep original chromaticity (x, y)
    // - Replace luminance with processed value
    // - Keep original HDR scale
    float invScale = max(original.w, 0.001);
    
    // Encode: z = Y * invScale (to maintain same encoding as input)
    float encodedY = processedY * invScale;
    
    result = vec4(original.x, original.y, encodedY, original.w);
}
