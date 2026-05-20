#version 300 es

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D accumBuffer;
uniform highp float postMergeGain;

layout(location = 0) out highp float fragColor;

void main() {
    highp ivec2 outPos = ivec2(gl_FragCoord.xy);
    highp vec4 accum = texelFetch(accumBuffer, outPos, 0);
    highp float wSum = accum.a;
    highp float merged = (wSum > 1e-6) ? (accum.r / wSum) : 0.0;

    if (postMergeGain > 1.0001) {
        highp float k = postMergeGain - 1.0;
        merged = merged * postMergeGain / (1.0 + k * merged);
    }
    fragColor = merged;
}
