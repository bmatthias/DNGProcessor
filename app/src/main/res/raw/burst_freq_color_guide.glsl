#version 300 es

// Wronski half-res RGB-triple "colour guide" image (Algorithm 1
// `compute_guide_image` in robustness.py:173-226 of
// /Users/matthias/Code/Handheld-Multi-Frame-Super-Resolution).
//
// For each 2×2 Bayer quad we identify the R, G1, G2, B samples by CFA
// pattern, average the two greens, and INVERSE WHITE BALANCE each channel
// so that R, G and B are in the same physical (scene-radiance) units. The
// guide image is consumed by the robustness path (mismatch + per-pixel
// fallback distance) so colour motion is a TRUE distance in colour space,
// not three separate per-CFA distances.
//
// The FFT/merge itself still operates on the unprocessed per-CFA RGBA
// texture produced by burst_freq_bayer_to_rgba.glsl — only the
// robustness path uses this guide.
//
// Output channels: (R, G_avg, B, 0), float16, half-res.
//
// Supports the same flow lookup as burst_freq_bayer_to_rgba.glsl so that
// the ref/alt guides are sampled from the SAME aligned coordinate frame.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D bayerTex;

uniform highp int inWidth;
uniform highp int inHeight;
uniform highp ivec2 gridOffsetBayer;

// CFA enum (RGGB=0, GRBG=1, GBRG=2, BGGR=3) — matches
// {@code CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT}.
uniform highp int cfaPattern;

// White-balance multipliers, one per channel. Pass {@code 1 / asShotNeutral[c]}
// rescaled so that {@code wb[1] = 1}. Default values give an identity guide.
uniform highp vec3 wbGain;

// Flow lookup (same convention as burst_freq_bayer_to_rgba.glsl).
uniform highp int useTileFlow;
uniform highp sampler2D tileFlowTex;
uniform highp vec2 tileFlowOriginSrc;
uniform highp vec2 tileFlowStrideSrc;
uniform highp ivec2 tileFlowSize;
uniform highp float tileFlowConfGamma;
uniform highp vec2 globalShift;
// 0 → bilinear shift, one rounded quad; 1 → Hann blend of four rounded quads.
uniform highp int useFourCornerFlow;
// 0 → 2×2 Hann-bilinear flow upsample.
// 1 → 3×3 Wronski Dogson biquadratic upsample.
uniform highp int useDogsonUpsample;
// Same exposure normalisation as burst_freq_bayer_to_rgba.glsl.
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

highp vec4 sampleQuad(highp int X, highp int Y) {
    return vec4(
        fetchBayer(X,     Y),
        fetchBayer(X + 1, Y),
        fetchBayer(X,     Y + 1),
        fetchBayer(X + 1, Y + 1));
}

highp ivec2 roundBayerShiftEven(highp vec2 sh) {
    return 2 * ivec2(floor(sh * 0.5 + 0.5));
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

// Quad-to-guide for the four standard CFA orderings.
//   q.r  q.g
//   q.b  q.a
// RGGB: R at .r, G at .g/.b, B at .a
// GRBG: G at .r/.a, R at .g, B at .b
// GBRG: G at .r/.a, B at .g, R at .b
// BGGR: B at .r, G at .g/.b, R at .a
highp vec3 quadToRgb(highp vec4 q, highp int cfa) {
    if (cfa == 0) return vec3(q.r,  0.5 * (q.g + q.b),  q.a);
    if (cfa == 1) return vec3(q.g,  0.5 * (q.r + q.a),  q.b);
    if (cfa == 2) return vec3(q.b,  0.5 * (q.r + q.a),  q.g);
    /* cfa == 3 (BGGR) */
    return vec3(q.a,  0.5 * (q.g + q.b),  q.r);
}

void main() {
    highp ivec2 gid = ivec2(gl_FragCoord.xy);
    highp int baseX = gid.x * 2 + gridOffsetBayer.x;
    highp int baseY = gid.y * 2 + gridOffsetBayer.y;

    highp vec4 quad;
    if (useTileFlow == 0) {
        quad = sampleQuad(baseX, baseY);
    } else {
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
            quad = sampleQuad(baseX - s.x, baseY - s.y);
        } else if (useDogsonUpsample == 1) {
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
            quad = vec4(0.0);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    highp ivec2 g = gNear + ivec2(dx, dy);
                    highp ivec2 s = roundBayerShiftEven(cornerShift(g));
                    quad += wX[dx + 1] * wY[dy + 1]
                          * sampleQuad(baseX - s.x, baseY - s.y);
                }
            }
        } else {
            highp ivec2 g0 = clamp(ivec2(floor(gridF)), ivec2(0), maxIdx - ivec2(1));
            highp vec2 t = gridF - vec2(g0);
            highp float w00, w10, w01, w11;
            hannCornerWeights(t, w00, w10, w01, w11);
            highp ivec2 s00 = roundBayerShiftEven(cornerShift(g0));
            highp ivec2 s10 = roundBayerShiftEven(cornerShift(g0 + ivec2(1, 0)));
            highp ivec2 s01 = roundBayerShiftEven(cornerShift(g0 + ivec2(0, 1)));
            highp ivec2 s11 = roundBayerShiftEven(cornerShift(g0 + ivec2(1, 1)));
            quad = w00 * sampleQuad(baseX - s00.x, baseY - s00.y)
                 + w10 * sampleQuad(baseX - s10.x, baseY - s10.y)
                 + w01 * sampleQuad(baseX - s01.x, baseY - s01.y)
                 + w11 * sampleQuad(baseX - s11.x, baseY - s11.y);
        }
    }

    highp vec3 rgb = quadToRgb(max(vec4(0.0), quad * exposureScale), cfaPattern) * wbGain;
    fragColor = vec4(rgb, 0.0);
}
