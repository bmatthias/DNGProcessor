#version 300 es

precision mediump float;

// The 3 exposure frames to blend (single-channel luma)
uniform sampler2D frameUnder;
uniform sampler2D frameNormal;
uniform sampler2D frameOver;
uniform sampler2D originalInput;

out vec4 result;  // R=wUnder, G=wNormal, B=wOver, A=blended result

#include gaussian

float wellExposednessWeight(float luma, float sigma) {
    float diff = luma - 0.5;
    return exp(-(diff * diff) / (2.0 * sigma * sigma));
}

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    
    float underVal = texelFetch(frameUnder, xyCenter, 0).x;
    float normalVal = texelFetch(frameNormal, xyCenter, 0).x;
    float overVal = texelFetch(frameOver, xyCenter, 0).x;
    
    vec4 originalEnc = texelFetch(originalInput, xyCenter, 0);
    float invScale = max(originalEnc.w, 0.001);
    float originalLuma = originalEnc.z / invScale;
    
    float sigma = 0.25;
    bool isHDR = (underVal > 1.0 || normalVal > 1.0 || overVal > 1.0);
    
    float wUnder, wNormal, wOver;
    
    if (isHDR) {
        float refMax = max(normalVal, 1.0);
        float underNorm = underVal / refMax;
        float normalNorm = normalVal / refMax;
        float overNorm = overVal / refMax;
        
        wUnder = wellExposednessWeight(underNorm, sigma);
        wNormal = wellExposednessWeight(normalNorm, sigma);
        wOver = wellExposednessWeight(overNorm, sigma);
        
        if (underVal > 1.0) {
            float hdrExcess = (underVal - 1.0) / max(refMax, 1.0);
            wUnder *= 1.0 + hdrExcess * 3.0;
        }
        
        float hdrFactor = 0.3;
        wUnder = mix(1.0, wUnder, hdrFactor);
        wNormal = mix(1.0, wNormal, hdrFactor);
        wOver = mix(1.0, wOver, hdrFactor);
    } else {
        wUnder = wellExposednessWeight(underVal, sigma);
        wNormal = wellExposednessWeight(normalVal, sigma);
        wOver = wellExposednessWeight(overVal, sigma);
    }
    
    float highlightThreshold = isHDR ? 1.0 : 0.5;
    float highlightMax = isHDR ? 2.0 : 1.0;
    
    if (originalLuma > highlightThreshold) {
        float highlightBoost = smoothstep(highlightThreshold, highlightMax, originalLuma);
        float boostFactor = isHDR ? 3.0 : 1.5;
        wUnder *= 1.0 + highlightBoost * boostFactor;
    }
    
    if (originalLuma < 0.3) {
        float shadowBoost = smoothstep(0.3, 0.0, originalLuma);
        wOver *= 1.0 + shadowBoost * 1.5;
    }
    
    wNormal *= 1.2;
    
    float minWeight = 0.01;
    wUnder = max(wUnder, minWeight);
    wNormal = max(wNormal, minWeight);
    wOver = max(wOver, minWeight);
    
    float wTotal = wUnder + wNormal + wOver;
    wUnder /= wTotal;
    wNormal /= wTotal;
    wOver /= wTotal;
    
    float blended = wUnder * underVal + wNormal * normalVal + wOver * overVal;
    
    result = vec4(wUnder, wNormal, wOver, blended);
}
