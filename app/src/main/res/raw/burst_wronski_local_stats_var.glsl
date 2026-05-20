#version 300 es

// Per-channel 3×3 variance on half-res RGB guide (Wronski Alg. 8).

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D guideTex;
uniform highp ivec2 guideSize;

layout(location = 0) out highp vec4 fragColor;

void main() {
    highp ivec2 p = ivec2(gl_FragCoord.xy);
    highp vec3 sum = vec3(0.0);
    highp vec3 sum2 = vec3(0.0);
    highp float n = 0.0;
    for (highp int dy = -1; dy <= 1; dy++) {
        for (highp int dx = -1; dx <= 1; dx++) {
            highp ivec2 q = clamp(p + ivec2(dx, dy), ivec2(0), guideSize - ivec2(1));
            highp vec3 g = texelFetch(guideTex, q, 0).rgb;
            sum += g;
            sum2 += g * g;
            n += 1.0;
        }
    }
    highp vec3 mean = sum / n;
    fragColor = vec4(max(sum2 / n - mean * mean, vec3(0.0)), 0.0);
}
