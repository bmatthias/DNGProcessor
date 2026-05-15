#version 300 es
/*
 * Tone Equalizer - Apply Exposure Correction
 * Ported from darktable's toneequal.c
 * 
 * This shader applies per-pixel exposure correction based on the smoothed
 * luminance mask. The correction is computed as a Gaussian-weighted sum
 * of the 9 EV band adjustments.
 * 
 * EV Bands (centers):
 *   -8 EV: Blacks
 *   -7 EV: Deep Shadows  
 *   -6 EV: Shadows
 *   -5 EV: Light Shadows
 *   -4 EV: Midtones
 *   -3 EV: Dark Highlights
 *   -2 EV: Highlights
 *   -1 EV: Whites
 *    0 EV: Speculars (also influences HDR values > 0 EV via Gaussian weighting)
 */

precision highp float;

uniform sampler2D input_tex;      // Original input in xyY format
uniform sampler2D mask_tex;       // Smoothed luminance mask (log2 EV)
uniform int width;
uniform int height;

// EV band adjustments in stops (-2.0 to +2.0 range)
uniform float ev_blacks;          // -8 EV
uniform float ev_deep_shadows;    // -7 EV
uniform float ev_shadows;         // -6 EV
uniform float ev_light_shadows;   // -5 EV
uniform float ev_midtones;        // -4 EV
uniform float ev_dark_highlights; // -3 EV
uniform float ev_highlights;      // -2 EV
uniform float ev_whites;          // -1 EV
uniform float ev_speculars;       //  0 EV

uniform float smoothing;          // Gaussian width for interpolation (default sqrt(2))

out vec4 result;

// EV band centers
const float centers[9] = float[9](-8.0, -7.0, -6.0, -5.0, -4.0, -3.0, -2.0, -1.0, 0.0);

// Gaussian function
float gaussian(float x, float sigma) {
    float denom = 2.0 * sigma * sigma;
    return exp(-x * x / denom);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    
    if (xy.x >= width || xy.y >= height) {
        result = vec4(0.0);
        return;
    }
    
    // Read original pixel
    vec4 pixel = texelFetch(input_tex, xy, 0);
    
    // Read smoothed luminance mask (log2 EV)
    float exposure = texelFetch(mask_tex, xy, 0).x;
    
    // Clamp to valid range (allow HDR values above 0 EV)
    exposure = clamp(exposure, -8.0, 4.0);
    
    // Build EV adjustments array
    float factors[9];
    factors[0] = ev_blacks;
    factors[1] = ev_deep_shadows;
    factors[2] = ev_shadows;
    factors[3] = ev_light_shadows;
    factors[4] = ev_midtones;
    factors[5] = ev_dark_highlights;
    factors[6] = ev_highlights;
    factors[7] = ev_whites;
    factors[8] = ev_speculars;
    
    // Compute correction as Gaussian-weighted sum of band adjustments
    float correction = 0.0;
    float total_weight = 0.0;
    
    for (int i = 0; i < 9; i++) {
        float weight = gaussian(exposure - centers[i], smoothing);
        correction += weight * factors[i];
        total_weight += weight;
    }
    
    // Normalize and convert from stops to linear multiplier
    // correction is in stops, so multiplier = 2^correction
    correction /= max(total_weight, 0.001);
    
    // Clamp correction to [-2, +2] stops = [0.25, 4.0] multiplier
    float multiplier = clamp(exp2(correction), 0.25, 4.0);
    
    // Apply correction to luminance channel only
    // Input is xyY format: x=chromaticity, y=chromaticity, z=luminance*scale, w=1/scale
    float invScale = max(pixel.w, 0.001);
    float Y = pixel.z / invScale;
    
    // Apply correction
    Y *= multiplier;
    
    // Re-encode
    float encodedY = Y * invScale;
    
    // Output: keep chromaticity, update luminance
    result = vec4(pixel.x, pixel.y, encodedY, pixel.w);
}
