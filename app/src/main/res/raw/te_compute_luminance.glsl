#version 300 es
/*
 * Tone Equalizer - Compute Luminance Mask
 * Ported from darktable's toneequal.c
 * 
 * This shader computes the log2 luminance of each pixel,
 * which will be used to determine which EV band affects each pixel.
 * 
 * The luminance is clamped to [-8, +4] EV range to preserve HDR highlights.
 */

precision highp float;

uniform sampler2D input_tex;  // Input in xyY format
uniform int width;
uniform int height;

out float luminance;

const float MIN_EV = -8.0;
const float MAX_EV = 4.0;  // Allow HDR values up to 16.0 (2^4)
const float MIN_LUMINANCE = 0.00001;  // Prevents log2(0)

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    
    if (xy.x >= width || xy.y >= height) {
        luminance = MIN_EV;
        return;
    }
    
    // Read xyY color
    vec4 pixel = texelFetch(input_tex, xy, 0);
    
    // Decode HDR luminance
    float invScale = max(pixel.w, 0.001);
    float Y = pixel.z / invScale;
    
    // Convert to log2 exposure value
    // Clamp to valid range [-8, +4] EV to preserve HDR highlights
    float ev = clamp(log2(max(Y, MIN_LUMINANCE)), MIN_EV, MAX_EV);
    
    luminance = ev;
}
