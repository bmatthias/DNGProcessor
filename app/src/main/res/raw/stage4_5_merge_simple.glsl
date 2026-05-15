#version 300 es

precision mediump float;

// The 2 exposure frames to blend (single-channel luma)
uniform sampler2D frameUnder;      // Underexposed (preserves highlights)
uniform sampler2D frameOver;        // Overexposed (reveals shadows)

// Original input texture (3-channel: chroma + luma) for mask
// We read the luma channel (z component) which is the original scene luminance
uniform sampler2D originalInput;

out float result;

#include gamma

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    
    // Get the 2 exposure frames
    float underVal = texelFetch(frameUnder, xyCenter, 0).x;
    float overVal = texelFetch(frameOver, xyCenter, 0).x;
    
    // Get original scene luminance from input texture (before any gamma curves)
    // This is the same as convert_uraw's "clone 0" - the original frame
    // Input texture is 3-channel (chroma + luma), luma is in z component
    // Decode HDR luminance: Y = z * w
    vec4 originalEnc = texelFetch(originalInput, xyCenter, 0);
    float invScale = max(originalEnc.w, 0.001);
    float originalLuma = originalEnc.z / invScale;
    
    // More aggressive highlight preservation: use sharper transitions to preserve detail/contrast
    // In very bright areas, use 100% underexposed (no overexposed)
    // This prevents any blending that would reduce highlight contrast
    
    // Use a power curve to create sharper transition in highlights
    // Power < 1 makes the curve steeper in bright areas
    float highlightMask = pow(originalLuma, 0.6);  // Steeper curve for better highlight separation
    
    // Simple 2-frame blend: underexposed for highlights, overexposed for shadows
    // Use smoothstep for smooth transition in mid-tones
    // In very bright areas (highlightMask > 0.7), use 100% underexposed
    float weightUnder = smoothstep(0.3, 0.7, highlightMask);
    
    // In very bright areas, force 100% underexposed to preserve highlight detail/contrast
    if (highlightMask > 0.75) {
        weightUnder = 1.0;
    }
    
    float weightOver = 1.0 - weightUnder;
    
    // Direct blend - no pyramid, no blur, just simple weighted average
    float res = weightUnder * underVal + weightOver * overVal;
    
    result = res;
}

