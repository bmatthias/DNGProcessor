#version 300 es
/*
 * Local Laplacian Filter - Process Curve (Tone Remapping)
 * Ported from darktable's locallaplacian.cl
 * 
 * This shader applies the local tone mapping curve to each pixel.
 * The curve modifies contrast based on the pixel's distance from a 
 * reference gray level (gamma), with different behavior for shadows
 * and highlights.
 * 
 * Parameters:
 * - gamma: Reference gray level for this remapped image (0-1)
 * - sigma: Transition width between shadows/midtones/highlights
 * - shadows: Shadow boost factor (>1 = lift shadows)
 * - highlights: Highlight compression factor (<1 = compress highlights)
 * - clarity: Local contrast/midtone detail boost
 */

precision highp float;

uniform sampler2D input_tex;
uniform float gamma;       // Reference gray level (e.g., 0.0833, 0.25, 0.4167, 0.5833, 0.75, 0.9167)
uniform float sigma;       // Transition width (typically 0.2)
uniform float shadows;     // Shadow boost (typically 1.0-2.0)
uniform float highlights;  // Highlight compression (typically 0.0-1.0)
uniform float clarity;     // Local contrast boost (typically 0.0-1.0)
uniform int width;
uniform int height;

out float result;

// Fast exponential approximation
float fast_exp(float x) {
    // Clamp to prevent overflow
    x = clamp(x, -88.0, 88.0);
    return exp(x);
}

// The core curve function from darktable (exact match)
float curve(float x, float g, float s, float shad, float high, float clar) {
    float c = x - g;  // Distance from reference gray
    float val;
    
    // Blend in via quadratic bezier (matching darktable's curve_scalar)
    if (c > 2.0 * s) {
        // Linear part: shadows far from reference
        val = g + s + shad * (c - s);
    } else if (c < -2.0 * s) {
        // Linear part: highlights far from reference
        val = g - s + high * (c + s);
    } else if (c > 0.0) {
        // Shadow contrast: quadratic Bezier blend
        float t = clamp(c / (2.0 * s), 0.0, 1.0);
        float t2 = t * t;
        float mt = 1.0 - t;
        val = g + s * 2.0 * mt * t + t2 * (s + s * shad);
    } else {
        // Highlight contrast: quadratic Bezier blend
        float t = clamp(-c / (2.0 * s), 0.0, 1.0);
        float t2 = t * t;
        float mt = 1.0 - t;
        val = g - s * 2.0 * mt * t + t2 * (-s - s * high);
    }
    
    // Midtone local contrast enhancement (clarity)
    // Gaussian-weighted boost centered on reference gray
    val += clar * c * fast_exp(-c * c / (2.0 * s * s / 3.0));
    
    return val;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    
    if (xy.x >= width || xy.y >= height) {
        result = 0.0;
        return;
    }
    
    float pixel = texelFetch(input_tex, xy, 0).x;
    
    // Apply the tone curve
    result = curve(pixel, gamma, sigma, shadows, highlights, clarity);
}
