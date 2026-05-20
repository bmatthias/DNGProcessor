#version 300 es

// 2× bilinear upscale of guide-resolution RGBA stats to full Bayer grid.
// Alt path: shift guide lookup by per-pixel flow (Wronski upscale_warp_stats).

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D srcTex;
uniform highp ivec2 srcSize;

uniform highp int useFlow;
uniform highp sampler2D tileFlowTex;
uniform highp vec2 tileFlowOriginSrc;
uniform highp vec2 tileFlowStrideSrc;
uniform highp ivec2 tileFlowSize;
uniform highp float tileFlowConfGamma;
uniform highp vec2 globalShift;

layout(location = 0) out highp vec4 fragColor;

const highp float PI = 3.14159265358979323846;

highp vec2 cornerShift(highp ivec2 g) {
    highp vec4 s = texelFetch(tileFlowTex, g, 0);
    highp float w = pow(clamp(s.z, 0.0, 1.0), tileFlowConfGamma);
    return mix(globalShift, s.xy, w);
}

void hannCornerWeights(highp vec2 t, out highp float w00, out highp float w10,
                       out highp float w01, out highp float w11) {
    highp float hx0 = 0.5 + 0.5 * cos(PI * t.x);
    highp float hx1 = 0.5 + 0.5 * cos(PI * (1.0 - t.x));
    highp float hy0 = 0.5 + 0.5 * cos(PI * t.y);
    highp float hy1 = 0.5 + 0.5 * cos(PI * (1.0 - t.y));
    w00 = hx0 * hy0;
    w10 = hx1 * hy0;
    w01 = hx0 * hy1;
    w11 = hx1 * hy1;
}

highp vec2 flowAtRaw(highp vec2 rawCenter) {
    if (useFlow == 0) return vec2(0.0);
    highp vec2 gridF = (rawCenter - tileFlowOriginSrc) / tileFlowStrideSrc;
    highp ivec2 maxIdx = tileFlowSize - ivec2(1);
    gridF = clamp(gridF, vec2(0.0), vec2(maxIdx));
    highp ivec2 g0 = clamp(ivec2(floor(gridF)), ivec2(0), maxIdx - ivec2(1));
    highp vec2 t = gridF - vec2(g0);
    highp float w00, w10, w01, w11;
    hannCornerWeights(t, w00, w10, w01, w11);
    return w00 * cornerShift(g0)
            + w10 * cornerShift(g0 + ivec2(1, 0))
            + w01 * cornerShift(g0 + ivec2(0, 1))
            + w11 * cornerShift(g0 + ivec2(1, 1));
}

highp vec4 sampleBilinear(highp vec2 lr) {
    highp vec2 maxIdx = vec2(srcSize) - vec2(1.0);
    lr = clamp(lr, vec2(0.0), maxIdx);
    highp ivec2 i0 = ivec2(floor(lr));
    highp ivec2 i1 = min(i0 + ivec2(1), ivec2(maxIdx));
    highp vec2 f = lr - vec2(i0);
    highp vec4 v00 = texelFetch(srcTex, i0, 0);
    highp vec4 v10 = texelFetch(srcTex, ivec2(i1.x, i0.y), 0);
    highp vec4 v01 = texelFetch(srcTex, ivec2(i0.x, i1.y), 0);
    highp vec4 v11 = texelFetch(srcTex, i1, 0);
    return mix(mix(v00, v10, f.x), mix(v01, v11, f.x), f.y);
}

void main() {
    highp vec2 rawCenter = vec2(gl_FragCoord.xy) + vec2(0.5);
    highp vec2 flow = flowAtRaw(rawCenter);
    highp vec2 lr = (rawCenter + flow) * 0.5 - vec2(0.5);
    fragColor = sampleBilinear(lr);
}
