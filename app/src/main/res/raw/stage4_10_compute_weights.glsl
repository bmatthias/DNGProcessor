#version 300 es

precision highp float;

// The 3 exposure frames at this pyramid level (single-channel luma)
uniform sampler2D frameUnder;
uniform sampler2D frameNormal;
uniform sampler2D frameOver;

// Original input luma at this pyramid level (single-channel)
uniform sampler2D originalInput;

// Texture dimensions for edge clamping (width-1, height-1)
uniform ivec2 texSize;

// Mertens exponent parameters for tuning (default all 1.0)
// W = C^alpha * S^beta * E^gamma
uniform float wExponentContrast;    // alpha: contrast weight exponent
uniform float wExponentSaturation;  // beta: saturation weight exponent  
uniform float wExponentExposure;    // gamma: well-exposedness weight exponent

// Output: weights for each frame (R=under, G=normal, B=over)
out vec3 weights;

#include gaussian

// Mertens well-exposedness weight
// Gaussian centered at 0.5 (mid-gray) - pixels closer to 0.5 get higher weight
float wellExposednessWeight(float luma, float sigma) {
    float diff = luma - 0.5;
    return exp(-(diff * diff) / (2.0 * sigma * sigma));
}

// Compute contrast using Laplacian filter with proper edge handling
// Clamps coordinates to valid range to avoid reading garbage at boundaries
float computeContrast(sampler2D frame, ivec2 xy, ivec2 maxXY) {
    float center = texelFetch(frame, xy, 0).x;
    
    // Clamp neighbor coordinates to valid texture bounds
    ivec2 left   = ivec2(max(xy.x - 1, 0), xy.y);
    ivec2 right  = ivec2(min(xy.x + 1, maxXY.x), xy.y);
    ivec2 top    = ivec2(xy.x, max(xy.y - 1, 0));
    ivec2 bottom = ivec2(xy.x, min(xy.y + 1, maxXY.y));
    
    float leftVal   = texelFetch(frame, left, 0).x;
    float rightVal  = texelFetch(frame, right, 0).x;
    float topVal    = texelFetch(frame, top, 0).x;
    float bottomVal = texelFetch(frame, bottom, 0).x;
    
    // Laplacian: 4*center - sum of neighbors
    // Use absolute value as contrast measure
    return abs(4.0 * center - (leftVal + rightVal + topVal + bottomVal));
}

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    
    // Use provided texture size or fall back to a large default
    ivec2 maxXY = (texSize.x > 0) ? texSize : ivec2(65535, 65535);
    
    // Get luma from the 3 frames
    float underVal = texelFetch(frameUnder, xyCenter, 0).x;
    float normalVal = texelFetch(frameNormal, xyCenter, 0).x;
    float overVal = texelFetch(frameOver, xyCenter, 0).x;
    
    // Compute contrast with edge handling
    float contrastUnder = computeContrast(frameUnder, xyCenter, maxXY);
    float contrastNormal = computeContrast(frameNormal, xyCenter, maxXY);
    float contrastOver = computeContrast(frameOver, xyCenter, maxXY);
    
    // Add small epsilon to contrast to avoid zero weights in flat regions
    const float contrastEps = 0.001;
    contrastUnder += contrastEps;
    contrastNormal += contrastEps;
    contrastOver += contrastEps;
    
    // Saturation: use neutral value for luma-only pyramid processing
    // (Original Mertens uses RGB std dev, but we work with luma pyramids)
    float saturation = 1.0;
    
    // Compute well-exposedness weights
    // Sigma controls the width of the Gaussian - larger = broader acceptance
    float sigma = 0.2;
    float wellExpUnder = wellExposednessWeight(underVal, sigma);
    float wellExpNormal = wellExposednessWeight(normalVal, sigma);
    float wellExpOver = wellExposednessWeight(overVal, sigma);
    
    // Get exponents (use 1.0 as default if not set)
    float alpha = (wExponentContrast > 0.0) ? wExponentContrast : 1.0;
    float beta = (wExponentSaturation > 0.0) ? wExponentSaturation : 1.0;
    float gamma = (wExponentExposure > 0.0) ? wExponentExposure : 1.0;
    
    // Combine quality measures with Mertens formula: W = C^alpha * S^beta * E^gamma
    float wUnder = pow(contrastUnder, alpha) * pow(saturation, beta) * pow(wellExpUnder, gamma);
    float wNormal = pow(contrastNormal, alpha) * pow(saturation, beta) * pow(wellExpNormal, gamma);
    float wOver = pow(contrastOver, alpha) * pow(saturation, beta) * pow(wellExpOver, gamma);
    
    // Normalize weights to sum to 1.0
    float wTotal = wUnder + wNormal + wOver;
    if (wTotal < 0.001) {
        // Fallback to equal weights if all weights are near zero
        weights = vec3(1.0 / 3.0);
    } else {
        weights = vec3(wUnder, wNormal, wOver) / wTotal;
    }
}
