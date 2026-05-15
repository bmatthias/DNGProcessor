#version 300 es

precision highp float;

// Laplacian coefficients from the 3 exposure frames
uniform sampler2D laplaceUnder;
uniform sampler2D laplaceNormal;
uniform sampler2D laplaceOver;

// Weights for blending (R=under, G=normal, B=over)
uniform sampler2D weights;

out float result;

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    
    // Get Laplacian coefficients (or Gaussian values for coarsest level)
    float lapUnder = texelFetch(laplaceUnder, xyCenter, 0).x;
    float lapNormal = texelFetch(laplaceNormal, xyCenter, 0).x;
    float lapOver = texelFetch(laplaceOver, xyCenter, 0).x;
    
    // Get weights
    vec3 w = texelFetch(weights, xyCenter, 0).rgb;
    
    // Weighted blend
    // For Laplacian coefficients: can be negative (they represent differences)
    // For Gaussian values (coarsest level): should be non-negative
    float blended = w.r * lapUnder + w.g * lapNormal + w.b * lapOver;
    
    // For Gaussian blending (coarsest level), ensure result is non-negative
    // For Laplacian blending, allow negatives (they'll be handled in reconstruction)
    // We can't distinguish here, so we'll let reconstruction handle it
    result = blended;
}
