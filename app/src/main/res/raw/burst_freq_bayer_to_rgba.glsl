#version 300 es

// Bayer → 4-channel half-res packing for the frequency-domain merge.
//
// Ports `convert_to_rgba` from hdr-plus-swift/burstphoto/texture/texture.metal:315
// with two additional features:
//   1. Configurable grid offset (one of 4 sub-tile origins for overlap-add).
//   2. Per-tile flow lookup for the non-reference frame.
//
// Output texel (gx, gy) at gl_FragCoord = (x+0.5, y+0.5) packs the 4 Bayer
// values from quad at (2*x + offsetBayerX, 2*y + offsetBayerY):
//   .r = bayer[X,   Y]
//   .g = bayer[X+1, Y]
//   .b = bayer[X,   Y+1]
//   .a = bayer[X+1, Y+1]
//
// CFA pattern interpretation is NOT applied here — the FFT operates on the
// raw 2x2 quads. The inverse (`burst_freq_rgba_to_bayer`) writes back at
// the same positions so the round trip is lossless.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D bayerTex;

uniform highp int inWidth;
uniform highp int inHeight;

uniform highp ivec2 gridOffsetBayer;

uniform highp int useTileFlow;
uniform highp sampler2D tileFlowTex;
uniform highp vec2 tileFlowOriginSrc;
uniform highp vec2 tileFlowStrideSrc;
uniform highp ivec2 tileFlowSize;
uniform highp float tileFlowConfGamma;
uniform highp vec2 globalShift;

// 0 → bilinear corner-shift interp, one rounded quad sample.
// 1 → Hann-weighted blend of four separately rounded quad samples (PhotonCamera).
uniform highp int useFourCornerFlow;

uniform highp int useDogsonUpsample;
uniform highp float exposureScale;

layout(location = 0) out highp vec4 fragColor;

const highp float PI = 3.14159265358979323846;

highp float fetchBayer(highp int x, highp int y) {
    x = clamp(x, 0, inWidth - 1);
    y = clamp(y, 0, inHeight - 1);
    return texelFetch(bayerTex, ivec2(x, y), 0).r;
}

highp vec2 cornerShift(highp ivec2 g) {
    highp vec4 sampled = texelFetch(tileFlowTex, g, 0);
    highp float conf = clamp(sampled.z, 0.0, 1.0);
    highp float w = pow(conf, tileFlowConfGamma);
    return mix(globalShift, sampled.xy, w);
}

highp ivec2 roundBayerShiftEven(highp vec2 sh) {
    return 2 * ivec2(floor(sh * 0.5 + 0.5));
}

highp vec4 sampleQuad(highp int X, highp int Y) {
    return vec4(
        fetchBayer(X,     Y),
        fetchBayer(X + 1, Y),
        fetchBayer(X,     Y + 1),
        fetchBayer(X + 1, Y + 1));
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

void main() {
    highp ivec2 gid = ivec2(gl_FragCoord.xy);
    highp int baseX = gid.x * 2 + gridOffsetBayer.x;
    highp int baseY = gid.y * 2 + gridOffsetBayer.y;

    if (useTileFlow == 0) {
        fragColor = max(vec4(0.0), sampleQuad(baseX, baseY) * exposureScale);
        return;
    }

    highp float center_x = float(baseX) + 1.0;
    highp float center_y = float(baseY) + 1.0;
    highp vec2 gridF = (vec2(center_x, center_y) - tileFlowOriginSrc) / tileFlowStrideSrc;
    highp ivec2 maxIdx = tileFlowSize - ivec2(1);
    gridF = clamp(gridF, vec2(0.0), vec2(maxIdx));

    if (useFourCornerFlow == 0) {
        highp ivec2 g0 = clamp(ivec2(floor(gridF)), ivec2(0), maxIdx - ivec2(1));
        highp vec2 t = gridF - vec2(g0);
        highp vec2 sh00 = cornerShift(g0);
        highp vec2 sh10 = cornerShift(g0 + ivec2(1, 0));
        highp vec2 sh01 = cornerShift(g0 + ivec2(0, 1));
        highp vec2 sh11 = cornerShift(g0 + ivec2(1, 1));
        highp vec2 sh = mix(mix(sh00, sh10, t.x), mix(sh01, sh11, t.x), t.y);
        highp ivec2 s = roundBayerShiftEven(sh);
        fragColor = max(vec4(0.0), sampleQuad(baseX - s.x, baseY - s.y) * exposureScale);
        return;
    }

    if (useDogsonUpsample == 1) {
        highp ivec2 gNear = clamp(ivec2(round(gridF)), ivec2(1), maxIdx - ivec2(1));
        highp vec2 t = gridF - vec2(gNear);
        highp vec3 wX = vec3(
                t.x * t.x - 0.5 * t.x,
                -2.0 * t.x * t.x + 1.0,
                t.x * t.x + 0.5 * t.x);
        highp vec3 wY = vec3(
                t.y * t.y - 0.5 * t.y,
                -2.0 * t.y * t.y + 1.0,
                t.y * t.y + 0.5 * t.y);
        highp vec4 quad = vec4(0.0);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                highp ivec2 g = gNear + ivec2(dx, dy);
                highp ivec2 s = roundBayerShiftEven(cornerShift(g));
                quad += wX[dx + 1] * wY[dy + 1]
                      * sampleQuad(baseX - s.x, baseY - s.y);
            }
        }
        fragColor = max(vec4(0.0), quad * exposureScale);
        return;
    }

    highp ivec2 g0 = clamp(ivec2(floor(gridF)), ivec2(0), maxIdx - ivec2(1));
    highp vec2 t = gridF - vec2(g0);
    highp float w00, w10, w01, w11;
    hannCornerWeights(t, w00, w10, w01, w11);

    highp ivec2 s00 = roundBayerShiftEven(cornerShift(g0));
    highp ivec2 s10 = roundBayerShiftEven(cornerShift(g0 + ivec2(1, 0)));
    highp ivec2 s01 = roundBayerShiftEven(cornerShift(g0 + ivec2(0, 1)));
    highp ivec2 s11 = roundBayerShiftEven(cornerShift(g0 + ivec2(1, 1)));

    highp vec4 quad = w00 * sampleQuad(baseX - s00.x, baseY - s00.y)
                    + w10 * sampleQuad(baseX - s10.x, baseY - s10.y)
                    + w01 * sampleQuad(baseX - s01.x, baseY - s01.y)
                    + w11 * sampleQuad(baseX - s11.x, baseY - s11.y);
    fragColor = max(vec4(0.0), quad * exposureScale);
}
