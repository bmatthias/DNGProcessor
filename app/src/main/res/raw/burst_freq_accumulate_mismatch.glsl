#version 300 es

// Accumulate a per-tile mismatch texture into a running total.
//
// Implements: out = accum + scale * add
// Used by BurstFrequencyMerge to maintain the total mismatch across alternates
// inside one of the 4 grid-offset passes. Deconvolution then reads the total.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D accumTex;
uniform highp sampler2D addTex;
uniform highp float scale;

out highp float fragColor;

void main() {
    highp ivec2 pos = ivec2(gl_FragCoord.xy);
    highp float a = texelFetch(accumTex, pos, 0).r;
    highp float b = texelFetch(addTex, pos, 0).r;
    fragColor = a + scale * b;
}
