#version 300 es

// |ref - aligned| half-res RGBA difference.
//
// Port of `calculate_abs_diff_rgba` from hdr-plus-swift/burstphoto/merge/
// frequency.metal:179. Output is per-pixel-per-channel absolute difference,
// consumed by burst_freq_mismatch.glsl.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D refRgbaTex;
uniform highp sampler2D alignedRgbaTex;

layout(location = 0) out highp vec4 fragColor;

void main() {
    highp ivec2 pos = ivec2(gl_FragCoord.xy);
    highp vec4 r = texelFetch(refRgbaTex,     pos, 0);
    highp vec4 a = texelFetch(alignedRgbaTex, pos, 0);
    fragColor = abs(r - a);
}
