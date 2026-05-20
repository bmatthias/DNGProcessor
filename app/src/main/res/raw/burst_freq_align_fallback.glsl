#version 300 es

// Per-pixel aligned-vs-unaligned auto-fallback.
//
// Port of PhotonCamera's `merge0.glsl:120-127`
// (/Users/matthias/Code/PhotonCamera/app/src/main/assets/shaders/merge/merge0.glsl).
//
// Idea: if at this pixel the UNALIGNED alternate already matches the
// reference better than the ALIGNED alternate, then the alignment is wrong
// locally — fall back to the unaligned value. This rescues per-pixel
// misalignments (parallax, occlusion edges, local optical-flow errors)
// BEFORE they enter the FFT, where a per-tile Wiener cannot detect them.
//
// Formula (per CFA channel):
//     w1     = |aligned   − base|
//     w2     = |unaligned − base|
//     ratio  = w2 / (w1 + w2)
//     alpha  = smoothstep(0.48, 0.51, ratio)
//     out    = mix(unaligned, aligned, alpha)
//
// The smoothstep gives a sharp soft selector around the cross-over point:
//   alignment-correct (w1 << w2): alpha ≈ 1, keep aligned.
//   alignment-wrong   (w1 >> w2): alpha ≈ 0, fall back to unaligned.
//
// Operates per CFA channel of the packed RGBA texture — same channel
// independence the rest of the FFT pipeline already assumes.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D refRgbaTex;
uniform highp sampler2D alignedAltTex;
uniform highp sampler2D unalignedAltTex;

layout(location = 0) out highp vec4 fragColor;

void main() {
    highp ivec2 p = ivec2(gl_FragCoord.xy);
    highp vec4 base      = texelFetch(refRgbaTex,      p, 0);
    highp vec4 aligned   = texelFetch(alignedAltTex,   p, 0);
    highp vec4 unaligned = texelFetch(unalignedAltTex, p, 0);

    highp vec4 w1 = abs(aligned   - base);
    highp vec4 w2 = abs(unaligned - base);
    highp vec4 sum = w1 + w2 + vec4(1e-6);
    highp vec4 alpha = smoothstep(vec4(0.48), vec4(0.51), w2 / sum);
    fragColor = mix(unaligned, aligned, alpha);
}
