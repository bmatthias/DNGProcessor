#version 300 es
/*
 * Local Laplacian Filter - Pad Input
 * Ported from darktable's locallaplacian.cl
 * 
 * This shader pads the input image with a border by replicating edge pixels.
 * The padding is needed to support the multi-level Gaussian pyramid without
 * boundary artifacts.
 */

precision highp float;

uniform sampler2D input_tex;  // Original input (xyY format, we extract Y/luminance)
uniform int input_width;      // Original input dimensions
uniform int input_height;
uniform int max_supp;         // Padding size (2^(num_levels-1))
uniform int padded_width;     // Padded output dimensions
uniform int padded_height;

out float result;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    int x = xy.x;
    int y = xy.y;
    
    if (x >= padded_width || y >= padded_height) {
        result = 0.0;
        return;
    }
    
    // Compute corresponding input coordinate
    int cx = x - max_supp;
    int cy = y - max_supp;
    
    // Clamp to input boundaries (replicate edge pixels)
    cx = clamp(cx, 0, input_width - 1);
    cy = clamp(cy, 0, input_height - 1);
    
    // Read luminance from input
    // Input is in xyY format where:
    // - xy = chromaticity (x, y)
    // - z = Y luminance (normalized)
    // - w = HDR scale factor
    vec4 pixel = texelFetch(input_tex, ivec2(cx, cy), 0);
    
    // Decode HDR luminance: Y = z / w (or z * w depending on encoding)
    // The input uses z = Y/scale, w = 1/scale, so Y = z / w = z * scale
    float invScale = max(pixel.w, 0.001);
    float luminance = pixel.z / invScale;
    
    // Preserve HDR values > 1.0 for highlight detail
    // Only clamp negatives
    result = max(luminance, 0.0);
}
