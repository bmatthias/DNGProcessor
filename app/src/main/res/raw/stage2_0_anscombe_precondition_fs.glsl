#version 300 es

// Anscombe Variance-Stabilizing Transform (Precondition)
// Based on darktable's denoiseprofile precondition_v2
// Transforms Poisson-Gaussian noise to approximately uniform Gaussian noise
//
// CURRENTLY DISABLED: Causes highlights to turn black due to HDR amplification
// Problem: DNG noise parameters cause ~100x amplification, HDR values (Y>>1) overflow
// See NoiseReduce.java for detailed explanation and potential fixes
precision highp float;

uniform sampler2D buf;
uniform ivec2 bufSize;

// Noise model parameters: variance = noiseA * signal + noiseB
// From DNG NoiseProfile or synthetic from BaselineNoise
uniform float noiseA;
uniform float noiseB;

// Adaptive parameter for shadows (0.0 = standard Anscombe, >0 = more detail in shadows)
uniform float shadowsParam;

out vec4 result;

#include xyytoxyz
#include xyztoxyy

// Generalized Anscombe transform (luminance only, preserve color ratios)
// For signal-dependent noise: variance = a * signal + b
// Transform: f(x) = 2 * (x + b) ^ (1 - p/2) / (sqrt(a) * (2 - p))
// where p controls shadow adaptation (p=0 for standard Anscombe)
float anscombeTransform(float Y) {
    // Clamp to prevent negative values
    float clamped = max(Y, 0.0);
    
    float sqrtA = sqrt(max(noiseA, 1e-8));
    
    // Adaptive exponent for better shadow handling
    // p = 0 gives standard Anscombe, p > 0 gives more detail in shadows
    float p = shadowsParam;
    float expon = 1.0 - p / 2.0;
    float denom = (2.0 - p) * sqrtA;
    
    // Transform: 2 * (x + b)^expon / denom
    float shifted = clamped + noiseB;
    float transformed = 2.0 * pow(shifted, expon) / denom;
    
    return transformed;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    
    // Fetch xyY color (Y stored in HDR encoding)
    vec4 encoded = texelFetch(buf, xy, 0);
    
    // Decode HDR luminance
    float invScale = max(encoded.w, 0.0001);
    vec3 xyY = vec3(encoded.x, encoded.y, encoded.z / invScale);
    
    // Convert xyY to XYZ
    vec3 XYZ = xyYtoXYZ(xyY);
    
    // Store original ratios to preserve saturation (color-preserving approach)
    // This ensures saturation is maintained while stabilizing noise variance
    float originalY = max(XYZ.y, 1e-8);  // Y is luminance (avoid division by zero)
    float ratioX = XYZ.x / originalY;  // X/Y ratio (chroma information)
    float ratioZ = XYZ.z / originalY;  // Z/Y ratio (chroma information)
    
    // Apply Anscombe transform ONLY to Y (luminance)
    // This stabilizes noise variance where it matters most (noise is primarily in luminance)
    float stabilizedY = anscombeTransform(XYZ.y);
    
    // Scale X and Z proportionally to maintain color ratios
    // This preserves saturation while stabilizing luminance noise
    // The ratios encode color information, so preserving them preserves color
    float stabilizedX = stabilizedY * ratioX;
    float stabilizedZ = stabilizedY * ratioZ;
    vec3 stabilizedXYZ = vec3(stabilizedX, stabilizedY, stabilizedZ);
    
    // Convert back to xyY
    vec3 stabilizedxyY = XYZtoxyY(stabilizedXYZ);
    
    // Re-encode in HDR format
    float Y = stabilizedxyY.z;
    float hdrScale = max(Y, 1.0);
    
    result = vec4(stabilizedxyY.x, stabilizedxyY.y, Y / hdrScale, 1.0 / hdrScale);
}
