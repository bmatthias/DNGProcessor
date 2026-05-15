#version 300 es

precision mediump float;

uniform sampler2D bufDenoisedHighRes;
uniform sampler2D bufDenoisedMediumRes;
uniform sampler2D bufDenoisedLowRes;
uniform sampler2D bufNoisyMediumRes;
uniform sampler2D bufNoisyLowRes;
uniform sampler2D noiseTexMediumRes;
uniform sampler2D noiseTexLowRes;

// Output (must be vec4 because RGB16F is not color-renderable in GLES 3.0)
out vec4 result;

// Helper to decode HDR xyY (alpha = 1/scale)
// If alpha == 1.0, no encoding was applied
vec3 decodeHDRxyY(vec4 encoded) {
    if (encoded.w >= 0.9999) {
        // No encoding was applied - Y is already correct
        return vec3(encoded.x, encoded.y, encoded.z);
    }
    // Otherwise, decode: Y = encoded.z / encoded.w
    float invScale = max(encoded.w, 0.0001);
    return vec3(encoded.x, encoded.y, encoded.z / invScale);
}

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    vec3 highRes, mediumRes, lowRes;

    // Decode HDR from all xyY buffers
    highRes = decodeHDRxyY(texelFetch(bufDenoisedHighRes, xyCenter, 0));

    mediumRes = decodeHDRxyY(texelFetch(bufDenoisedMediumRes, xyCenter, 0));
    mediumRes -= decodeHDRxyY(texelFetch(bufNoisyMediumRes, xyCenter, 0));

    lowRes = decodeHDRxyY(texelFetch(bufDenoisedLowRes, xyCenter, 0));
    lowRes -= decodeHDRxyY(texelFetch(bufNoisyLowRes, xyCenter, 0));

    // Noise textures are not HDR encoded
    mediumRes *= min(texelFetch(noiseTexMediumRes, xyCenter / 4, 0).xyz * 32.f, 1.f);
    lowRes *= min(texelFetch(noiseTexLowRes, xyCenter / 4, 0).xyz * 64.f, 1.f);

    // Keep in xyY format - do NOT convert to XYZ here!
    // The pipeline expects xyY format until the final ToneMap stage
    vec3 combined = highRes + mediumRes + lowRes;
    
    // Re-encode HDR luminance - only if Y > 1.0 to avoid quantization
    float Y = combined.z;
    if (Y <= 1.0) {
        // Y already in [0,1] - no encoding needed, use alpha=1.0 as marker
        result = vec4(combined.x, combined.y, Y, 1.0);
    } else {
        // HDR value - encode with scaling
        float hdrScale = Y;
        result = vec4(combined.x, combined.y, Y / hdrScale, 1.0 / hdrScale);
    }
}
