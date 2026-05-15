#version 300 es

precision mediump float;

uniform sampler2D buf;
uniform ivec2 minxy;
uniform ivec2 maxxy;

uniform float sigma;
uniform ivec2 radius;

uniform ivec2 dir;
uniform vec2 ch;

// Out
out float result;

#include gaussian

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);

    float I = 0.f;
    float W = 0.f;

    for (int i = -radius.x; i <= radius.x; i += radius.y) {
        ivec2 xy = xyCenter + i * dir;
        if (xy.x >= minxy.x && xy.y >= minxy.y && xy.x <= maxxy.x && xy.y <= maxxy.y) {
            vec4 texData = texelFetch(buf, xyCenter + i * dir, 0);
            // Decode HDR luminance if selecting z channel: z_decoded = z * w
            // x channel doesn't need decoding (chromaticity, always [0,1])
            float invScale = max(texData.w, 0.001);
            float z_decoded = texData.z / invScale;  // HDR decode
            float value = dot(ch, vec2(texData.x, z_decoded));
            float scale = unscaledGaussian(float(i), sigma);
            I += value * scale;
            W += scale;
        }
    }

    result = I / W;
}
