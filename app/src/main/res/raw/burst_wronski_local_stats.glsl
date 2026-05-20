#version 300 es

// 3×3 box mean on half-res RGB guide (Wronski Alg. 8 mean pass).

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D guideTex;
uniform highp ivec2 guideSize;

layout(location = 0) out highp vec4 fragColor;

void main() {
    highp ivec2 p = ivec2(gl_FragCoord.xy);
    highp vec3 sum = vec3(0.0);
    highp float n = 0.0;
    for (highp int dy = -1; dy <= 1; dy++) {
        for (highp int dx = -1; dx <= 1; dx++) {
            highp ivec2 q = clamp(p + ivec2(dx, dy), ivec2(0), guideSize - ivec2(1));
            sum += texelFetch(guideTex, q, 0).rgb;
            n += 1.0;
        }
    }
    fragColor = vec4(sum / n, 0.0);
}
