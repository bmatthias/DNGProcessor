#version 300 es

precision mediump float;

uniform sampler2D buf;

uniform float factor;
uniform float gamma;  // Gamma to apply (1.0 = linear, <1.0 = compress highlights)
uniform bool isHDR;   // True for HDR Linear Raw processing

out float result;

#include gamma

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    
    // Decode HDR luminance: Y is stored normalized in .z, scale is in .w (alpha)
    vec4 xyYData = texelFetch(buf, xyCenter, 0);
    float invScale = max(xyYData.w, 0.001);
    float y = xyYData.z / invScale;  // Restore HDR luminance: normalized / (1/scale)
    
    float x;
    if (isHDR) {
        // Exposure fusion: use gamma curves ONLY, NEVER multiply!
        // convert_uraw uses: -evaluate Pow %[fx:1/$cgamma] which is pow(y, 1/gamma)
        // Applied directly to original image, NO multiplication first!
        //
        // BEST PRACTICE: Input y can exceed 1.0 (HDR headroom from baseline exposure)
        // Gamma curves create different values for HDR highlights, enabling fusion
        // to blend and recover highlight detail.
        //
        // The "factor" parameter is IGNORED - we only use gamma curves
        
        // Don't clamp input - preserve HDR values for exposure fusion
        float yInput = max(y, 0.0);  // Only clamp negatives
        
        // Detect if input is clipped exactly at 1.0 (unlikely with HDR)
        bool isClipped = (yInput >= 0.999 && yInput <= 1.001);
        
        // Apply curve: pow(y, gamma)
        // - gamma > 1.0: makes mid-tones DARKER (underexposed, preserves highlights)
        //   For HDR: pow(1.5, 1.5) = 1.84 (still HDR, but different per gamma)
        // - gamma = 1.0: linear, no change (correct exposure, reference)
        // - gamma < 1.0: makes mid-tones BRIGHTER (overexposed, expands shadows)
        //   For HDR: pow(1.5, 0.7) = 1.31 (compressed toward 1.0)
        x = pow(max(yInput, 0.001), gamma);  // Small epsilon to avoid pow(0, gamma)
        
        // Don't clamp output - let fusion work with full HDR range
        x = max(x, 0.0);  // Only clamp negatives
    } else {
        // SDR mode: original behavior (simple multiply + gamma encode)
        // Only clamp negatives, preserve precision for values > 1.0
        x = max(factor * y, 0.0);
    }
    
    //result = gammaEncode(x);
    result = x;
}
