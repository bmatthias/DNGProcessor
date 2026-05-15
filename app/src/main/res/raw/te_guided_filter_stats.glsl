#version 300 es
/*
 * Tone Equalizer - Guided Filter Statistics
 * Ported from darktable's eigf.h (Exposure-Independent Guided Filter)
 * 
 * This shader computes local averages and variances needed for the guided filter.
 * Outputs 4 channels:
 * - Average of guide (luminance)
 * - Variance of guide
 * - (unused - set to 0)
 * - (unused - set to 0)
 */

precision highp float;

uniform sampler2D luminance_tex;
uniform sampler2D luminance_sq_tex;  // Pre-computed luminance squared
uniform int width;
uniform int height;
uniform float sigma;  // Blur radius in pixels

out vec4 stats;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    
    if (xy.x >= width || xy.y >= height) {
        stats = vec4(0.0);
        return;
    }
    
    // Simple box filter for averaging (will be replaced with proper Gaussian in Java)
    // For now, just read from pre-blurred textures
    float avg = texelFetch(luminance_tex, xy, 0).x;
    float avg_sq = texelFetch(luminance_sq_tex, xy, 0).x;
    
    // Variance = E[X^2] - E[X]^2
    float variance = max(avg_sq - avg * avg, 0.0);
    
    stats = vec4(avg, variance, 0.0, 0.0);
}
