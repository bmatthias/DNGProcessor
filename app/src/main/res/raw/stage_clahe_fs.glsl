#version 300 es

precision mediump float;

uniform sampler2D intermediate;  // Input xyY format
uniform sampler2D enhancedLuma; // Pre-computed enhanced luminance from multi-scale CLAHE

uniform float strength;         // CLAHE strength (0.0 to 1.0)

out vec4 processed;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    
    // Sample input (xyY format with HDR encoding)
    // Decode HDR luminance: Y = z / alpha (alpha = 1/scale)
    // If alpha == 1.0, no encoding was applied
    vec4 encoded = texelFetch(intermediate, xy, 0);
    float Y;
    if (encoded.w >= 0.9999) {
        // No encoding was applied - Y is already correct
        Y = encoded.z;
    } else {
        // Decode: Y = encoded.z / encoded.w
        float invScale = max(encoded.w, 0.0001);
        Y = encoded.z / invScale;
    }
    vec3 xyY = vec3(encoded.x, encoded.y, Y);
    
    // Sample enhanced luminance
    float enhancedY = texelFetch(enhancedLuma, xy, 0).r;
    
    // Blend original and enhanced based on strength
    float finalY = mix(xyY.z, enhancedY, strength);
    finalY = max(finalY, 0.0);  // Only clamp negatives, preserve HDR values > 1.0
    
    // Re-encode HDR luminance - only if Y > 1.0 to avoid quantization
    processed.xy = xyY.xy;
    if (finalY <= 1.0) {
        // Y already in [0,1] - no encoding needed, use alpha=1.0 as marker
        processed.z = finalY;
        processed.w = 1.0;
    } else {
        // HDR value - encode with scaling
        float hdrScale = finalY;
        processed.z = finalY / hdrScale;
        processed.w = 1.0 / hdrScale;
    }
}

