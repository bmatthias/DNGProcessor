#version 300 es

precision mediump float;

uniform sampler2D intermediate;
uniform ivec2 outOffset;
uniform int samplingFactor;

// Out
out vec4 analysis;

#include load3x3v3

void main() {
    ivec2 xy = samplingFactor * ivec2(gl_FragCoord.xy) + outOffset;

    // Load patch
    vec3[9] impatch = load3x3(xy, 2, intermediate);

    /**
     * STANDARD DEVIATIONS
     */
    vec3 mean = vec3(0.0);
    vec3 sigma = vec3(0.0);
    for (int i = 0; i < 9; i++) {
        mean += impatch[i];
    }
    mean /= 9.f;
    for (int i = 0; i < 9; i++) {
        vec3 diff = mean - impatch[i];
        sigma += diff * diff;
    }

    // Decode HDR luminance: Y = z / alpha (alpha = 1/scale)
    // If alpha == 1.0, no encoding was applied
    vec4 encoded = texelFetch(intermediate, xy, 0);
    float z;
    if (encoded.w >= 0.9999) {
        // No encoding was applied - Y is already correct
        z = encoded.z;
    } else {
        // Decode: Y = encoded.z / encoded.w
        float invScale = max(encoded.w, 0.0001);
        z = encoded.z / invScale;
    }
    analysis = vec4(sqrt(sigma / 9.f), z);
}
