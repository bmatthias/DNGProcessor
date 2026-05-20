#version 300 es

// Add pooled robustness into accumulated_r (Alg. 6 accumulation).

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D accRobTex;
uniform highp sampler2D robustTex;

layout(location = 0) out highp float fragColor;

void main() {
    highp ivec2 p = ivec2(gl_FragCoord.xy);
    fragColor = texelFetch(accRobTex, p, 0).r + texelFetch(robustTex, p, 0).r;
}
