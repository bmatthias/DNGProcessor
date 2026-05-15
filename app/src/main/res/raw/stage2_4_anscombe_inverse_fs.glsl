#version 300 es

// Inverse Anscombe Transform (Backtransform)
// Based on darktable's backtransform_v2 with bias correction
// Converts variance-stabilized space back to original signal space
//
// CURRENTLY DISABLED: Paired with precondition shader (both disabled)
// Cannot inverse transform if precondition wasn't applied
// See stage2_0_anscombe_precondition_fs.glsl for the HDR amplification issue
precision highp float;

uniform sampler2D buf;
uniform ivec2 bufSize;

// Noise model parameters (same as precondition)
uniform float noiseA;
uniform float noiseB;

// Adaptive parameter for shadows (must match precondition)
uniform float shadowsParam;

// Bias correction parameter to reduce inverse transform artifacts
// Typical range: 0.25-1.0 (higher = more aggressive denoising, may reduce detail)
uniform float bias;

out vec4 result;

#include xyytoxyz
#include xyztoxyy

// Inverse Anscombe transform with bias correction (luminance only, preserve color ratios)
// Aims to compute E[X] from E[f(X)] with low bias
// Based on Taylor expansion to 2nd order: E[f(X)] ≈ f(E[X]) + f''(E[X])/2 * Var(X)
float inverseAnscombeTransform(float transformedY) {
    float y = max(transformedY, 0.0);
    
    float sqrtA = sqrt(max(noiseA, 1e-8));
    
    // Match precondition parameters
    float p = shadowsParam;
    float expon_inv = 1.0 / (1.0 - p / 2.0);
    float scale = (sqrtA * (2.0 - p)) / 4.0;
    
    // Bias-corrected inverse:
    // delta = y^2 + bias
    // z1 = (y + sqrt(max(delta, 0))) * scale
    // x = z1^expon_inv - b
    float delta = y * y + bias;
    float sqrtDelta = sqrt(max(delta, 0.0));
    float z1 = (y + sqrtDelta) * scale;
    float back = pow(z1, expon_inv) - noiseB;
    
    // Clamp to prevent negative values
    return max(back, 0.0);
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    
    // Fetch denoised data in stabilized space
    vec4 encoded = texelFetch(buf, xy, 0);
    
    // Decode HDR luminance
    float invScale = max(encoded.w, 0.0001);
    vec3 stabilizedxyY = vec3(encoded.x, encoded.y, encoded.z / invScale);
    
    // Convert xyY to XYZ
    vec3 stabilizedXYZ = xyYtoXYZ(stabilizedxyY);
    
    // Store ratios to preserve saturation (color-preserving approach)
    // These ratios were preserved during precondition, so we preserve them here too
    float stabilizedY = max(stabilizedXYZ.y, 1e-8);  // Avoid division by zero
    float ratioX = stabilizedXYZ.x / stabilizedY;  // X/Y ratio (chroma information)
    float ratioZ = stabilizedXYZ.z / stabilizedY;  // Z/Y ratio (chroma information)
    
    // Apply inverse Anscombe transform ONLY to Y (luminance)
    // This restores original signal space while maintaining color relationships
    float restoredY = inverseAnscombeTransform(stabilizedXYZ.y);
    
    // Scale X and Z proportionally to maintain color ratios
    // This preserves saturation while restoring original signal space
    // The ratios encode color information, so preserving them preserves color
    float restoredX = restoredY * ratioX;
    float restoredZ = restoredY * ratioZ;
    vec3 restoredXYZ = vec3(restoredX, restoredY, restoredZ);
    
    // Convert back to xyY
    vec3 restoredxyY = XYZtoxyY(restoredXYZ);
    
    // Re-encode in HDR format
    float Y = restoredxyY.z;
    float hdrScale = max(Y, 1.0);
    
    result = vec4(restoredxyY.x, restoredxyY.y, Y / hdrScale, 1.0 / hdrScale);
}
