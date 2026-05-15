#version 300 es

precision mediump float;

uniform sampler2D inBuffer;
uniform sampler2D noiseTex;
uniform ivec2 bufSize;

uniform ivec2 radius;
uniform vec2 sigma;
uniform float blendY;

// Output (must be vec4 because RGB16F is not color-renderable in GLES 3.0)
out vec4 result;

#include gaussian

// Difference
vec3 fr(vec3 diffi, vec3 s) {
    return unscaledGaussian(abs(diffi), sigma.y * s);
}

// Distance
float gs(ivec2 diffxy) {
    return unscaledGaussian(length(vec2(diffxy.x, diffxy.y)), sigma.x);
}

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

    // Decode HDR luminance
    vec3 XYZCenter = decodeHDRxyY(texelFetch(inBuffer, xyCenter, 0));
    vec3 noiseLevel = texelFetch(noiseTex, xyCenter / 4, 0).xyz;  // Noise is not HDR encoded

    ivec2 minxy = max(ivec2(0, 0), xyCenter - radius.x);
    ivec2 maxxy = min(bufSize - 1, xyCenter + radius.x);

    vec3 I = vec3(0.f);
    float W = 0.f;

    ivec2 xyPixel;
    vec3 XYZPixel, XYZScale;
    float XYZScalef;
    for (int y = minxy.y; y <= maxxy.y; y += radius.y) {
        for (int x = minxy.x; x <= maxxy.x; x += radius.y) {
            xyPixel = ivec2(x, y);
            // Decode HDR luminance for each pixel
            XYZPixel = decodeHDRxyY(texelFetch(inBuffer, xyPixel, 0));

            XYZScale = fr(XYZPixel - XYZCenter, noiseLevel) * gs(xyPixel - xyCenter);
            XYZScalef = length(XYZScale);
            I += XYZPixel * XYZScalef;
            W += XYZScalef;
        }
    }

    vec3 tmp;
    if (W < 0.0001f) {
        tmp = XYZCenter;
    } else {
        tmp = I / W;
        tmp.z = mix(tmp.z, XYZCenter.z, blendY);
    }

    // Desaturate noisy patches - DISABLED to preserve saturation
    // tmp.xy = mix(tmp.xy, vec2(0.345703f, 0.358539f), min(0.01f * length(noiseLevel.xy) - 0.01f, 0.25f));
    
    // Re-encode HDR luminance - only if Y > 1.0 to avoid quantization
    float Y = tmp.z;
    if (Y <= 1.0) {
        // Y already in [0,1] - no encoding needed, use alpha=1.0 as marker
        result = vec4(tmp.x, tmp.y, Y, 1.0);
    } else {
        // HDR value - encode with scaling
        float hdrScale = Y;
        result = vec4(tmp.x, tmp.y, Y / hdrScale, 1.0 / hdrScale);
    }
}
