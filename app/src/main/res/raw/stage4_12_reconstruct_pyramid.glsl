#version 300 es

precision mediump float;

// Coarser level (upsampled)
uniform sampler2D coarse;

// Laplacian detail at this level
uniform sampler2D laplacian;

out float result;

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    
    // Get upsampled coarse level
    float coarseVal = texelFetch(coarse, xyCenter, 0).x;
    
    // Get Laplacian detail
    float lapVal = texelFetch(laplacian, xyCenter, 0).x;
    
    // Reconstruct: coarse + detail
    // Laplacian coefficients can be negative (they represent differences)
    // Best practice: Do NOT clamp negatives - they are essential for accurate reconstruction
    // Working in log space [0,1] - reconstruction should naturally produce valid values
    // Negative values are preserved as they're needed for accurate reconstruction
    result = coarseVal + lapVal;
}
