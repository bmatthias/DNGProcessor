#version 300 es

// 2× bilinear upscale of a half-res guide map to full Bayer resolution
// (Wronski upscale_warp_stats ref path without flow).

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D srcTex;
uniform highp ivec2 srcSize;

layout(location = 0) out highp float fragColor;

void main() {
    highp ivec2 outP = ivec2(gl_FragCoord.xy);
    highp vec2 lr = (vec2(outP) + vec2(0.5)) * 0.5 - vec2(0.5);
    highp vec2 maxIdx = vec2(srcSize) - vec2(1.0);
    lr = clamp(lr, vec2(0.0), maxIdx);
    highp ivec2 i0 = ivec2(floor(lr));
    highp ivec2 i1 = min(i0 + ivec2(1), ivec2(maxIdx));
    highp vec2 f = lr - vec2(i0);
    highp float v00 = texelFetch(srcTex, i0, 0).r;
    highp float v10 = texelFetch(srcTex, ivec2(i1.x, i0.y), 0).r;
    highp float v01 = texelFetch(srcTex, ivec2(i0.x, i1.y), 0).r;
    highp float v11 = texelFetch(srcTex, i1, 0).r;
    fragColor = mix(mix(v00, v10, f.x), mix(v01, v11, f.x), f.y);
}
