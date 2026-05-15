#version 300 es

precision highp float;

// The 3 exposure frames to blend (single-channel luma in xyY format)
uniform sampler2D frameUnder;      // Darker frame (preserves highlights)
uniform sampler2D frameNormal;     // Normal exposure
uniform sampler2D frameOver;       // Brighter frame (lifts shadows)

// Original input texture (4-channel xyY) for weighting
uniform sampler2D originalInput;

// Texture dimensions for edge clamping (width-1, height-1)
uniform ivec2 texSize;

// Mertens exponent parameters for tuning (default all 1.0)
// W = C^alpha * S^beta * E^gamma
uniform float wExponentContrast;    // alpha: contrast weight exponent
uniform float wExponentSaturation;  // beta: saturation weight exponent  
uniform float wExponentExposure;    // gamma: well-exposedness weight exponent

// Use full Mertens method (contrast + saturation + well-exposedness) or just well-exposedness
// 0 = simple (well-exposedness only), 1 = full Mertens
uniform int useFullMertens;

out float result;

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
    return abs(4.0 * center - (leftVal + rightVal + topVal + bottomVal));
}

// Compute saturation from chroma in xyY format
// Higher saturation = more colorful = should prefer that frame's exposure
float computeSaturation(sampler2D inputTex, ivec2 xy) {
    vec4 xyYEnc = texelFetch(inputTex, xy, 0);
    vec2 chroma = xyYEnc.xy;
    
    // D65 white point in xy chromaticity
    vec2 whitePoint = vec2(0.3127, 0.3290);
    
    // Distance from white point as saturation measure
    // Add small epsilon to avoid zero weights
    return length(chroma - whitePoint) + 0.01;
}

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    
    // Use provided texture size or fall back to a large default
    ivec2 maxXY = (texSize.x > 0) ? texSize : ivec2(65535, 65535);
    
    // Get luma from the 3 exposure frames
    float underVal = texelFetch(frameUnder, xyCenter, 0).x;
    float normalVal = texelFetch(frameNormal, xyCenter, 0).x;
    float overVal = texelFetch(frameOver, xyCenter, 0).x;
    
    // Compute well-exposedness weights
    // Sigma controls width of Gaussian - smaller = stricter mid-tone preference
    float sigma = 0.2;
    float wellExpUnder = wellExposednessWeight(underVal, sigma);
    float wellExpNormal = wellExposednessWeight(normalVal, sigma);
    float wellExpOver = wellExposednessWeight(overVal, sigma);
    
    // Get exponents (use 1.0 as default if not set)
    float alpha = (wExponentContrast > 0.0) ? wExponentContrast : 1.0;
    float beta = (wExponentSaturation > 0.0) ? wExponentSaturation : 1.0;
    float gamma = (wExponentExposure > 0.0) ? wExponentExposure : 1.0;
    
    float wUnder, wNormal, wOver;
    
    if (useFullMertens != 0) {
        // Full Mertens: W = C^alpha * S^beta * E^gamma
        
        // Compute contrast per frame with edge handling
        float contrastUnder = computeContrast(frameUnder, xyCenter, maxXY);
        float contrastNormal = computeContrast(frameNormal, xyCenter, maxXY);
        float contrastOver = computeContrast(frameOver, xyCenter, maxXY);
        
        // Add small epsilon to contrast to avoid zero weights in flat regions
        const float contrastEps = 0.001;
        contrastUnder += contrastEps;
        contrastNormal += contrastEps;
        contrastOver += contrastEps;
        
        // Saturation from original input (same for all frames since we work with luma)
        float saturation = computeSaturation(originalInput, xyCenter);
        
        // Combine with Mertens formula
        wUnder = pow(contrastUnder, alpha) * pow(saturation, beta) * pow(wellExpUnder, gamma);
        wNormal = pow(contrastNormal, alpha) * pow(saturation, beta) * pow(wellExpNormal, gamma);
        wOver = pow(contrastOver, alpha) * pow(saturation, beta) * pow(wellExpOver, gamma);
    } else {
        // Simple: well-exposedness only (with gamma exponent)
        wUnder = pow(wellExpUnder, gamma);
        wNormal = pow(wellExpNormal, gamma);
        wOver = pow(wellExpOver, gamma);
    }
    
    // Normalize weights to sum to 1.0
    // FIX: Ensure exact sum to avoid dithering from precision errors
    float wTotal = wUnder + wNormal + wOver;
    if (wTotal < 0.001) {
        // Fallback to equal weights if all weights are near zero
        wUnder = wNormal = wOver = 1.0 / 3.0;
    } else {
        // Normalize first two weights, then compute third to ensure exact sum
        // This prevents dithering from floating-point precision errors
        float invTotal = 1.0 / wTotal;
        wUnder *= invTotal;
        wNormal *= invTotal;
        wOver = 1.0 - wUnder - wNormal;  // Exact sum to 1.0
    }
    
    // Weighted blend - use exact weights to avoid dithering
    result = wUnder * underVal + wNormal * normalVal + wOver * overVal;
}
