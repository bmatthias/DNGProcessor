#version 300 es
/*
 * Tone Equalizer - Guided Filter Blending
 * Ported from darktable's eigf.h (Exposure-Independent Guided Filter)
 * 
 * This shader applies edge-aware smoothing using the guided filter principle:
 * output = a * guide + b
 * 
 * Where a and b are computed from local statistics to preserve edges.
 * The EIGF modification makes it exposure-independent by normalizing variance.
 */

precision highp float;

uniform sampler2D luminance_tex;  // Original luminance (guide)
uniform sampler2D stats_tex;      // Blurred statistics [avg, variance, 0, 0]
uniform float feathering;         // Edge preservation strength
uniform int width;
uniform int height;

out float smoothed;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    
    if (xy.x >= width || xy.y >= height) {
        smoothed = 0.0;
        return;
    }
    
    float guide = texelFetch(luminance_tex, xy, 0).x;
    vec4 stat = texelFetch(stats_tex, xy, 0);
    float avg = stat.x;
    float variance = stat.y;
    
    // EIGF: Normalize variance by pixel value for exposure independence
    // This prevents halos in shadows while maintaining edge preservation in highlights
    float norm = max(avg * guide, 1e-6);
    float normalized_variance = variance / norm;
    
    // Compute guided filter coefficients
    // a = variance / (variance + feathering)
    // b = avg - a * avg
    float a = normalized_variance / (normalized_variance + feathering);
    float b = avg - a * avg;
    
    // Apply: smoothed = a * guide + b
    smoothed = max(guide * a + b, -10.0);  // Clamp to prevent extreme values
}
