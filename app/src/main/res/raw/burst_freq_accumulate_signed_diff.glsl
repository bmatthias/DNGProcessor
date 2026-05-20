#version 300 es

// Accumulate per-frame SIGNED diff (aligned − ref) into a running half-res
// RGBA accumulator. Counterpart to {@code burst_freq_abs_diff.glsl} but
// preserves sign and direction across CFA channels, which is needed by
// {@code burst_freq_direction_preserve.glsl} to constrain the merged diff.
//
//   accumulator += (aligned − ref) / scale
//
// {@code scale} is set to {@code N − 1} (the number of alternates) so the
// accumulator holds the MEAN signed diff after all alternates have been
// processed. For N = 1 (single-frame burst) this shader is never invoked.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D refRgbaTex;
uniform highp sampler2D alignedAltTex;
uniform highp sampler2D accumTex;       // previous accumulator value
uniform highp float scale;              // 1 / (N − 1)

layout(location = 0) out highp vec4 fragColor;

void main() {
    highp ivec2 p = ivec2(gl_FragCoord.xy);
    highp vec4 r = texelFetch(refRgbaTex,     p, 0);
    highp vec4 a = texelFetch(alignedAltTex,  p, 0);
    highp vec4 prev = texelFetch(accumTex,    p, 0);
    fragColor = prev + (a - r) * scale;
}
