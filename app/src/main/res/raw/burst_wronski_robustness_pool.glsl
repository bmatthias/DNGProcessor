#version 300 es

// 5×5 local-min of robustness (Wronski Alg. 9).

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D robustTex;
uniform highp ivec2 robustSize;

layout(location = 0) out highp float fragColor;

void main() {
    highp ivec2 p = ivec2(gl_FragCoord.xy);
    highp float m = 1.0;
    for (highp int dy = -2; dy <= 2; dy++) {
        for (highp int dx = -2; dx <= 2; dx++) {
            highp ivec2 q = clamp(p + ivec2(dx, dy), ivec2(0), robustSize - ivec2(1));
            m = min(m, texelFetch(robustTex, q, 0).r);
        }
    }
    fragColor = m;
}
