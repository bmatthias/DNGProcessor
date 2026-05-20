#version 300 es

// Wronski Alg. 7: inverse-WB guide + Hann 4-corner flow.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D bayerTex;
uniform highp int inWidth;
uniform highp int inHeight;
uniform highp int cfaPattern;
uniform highp vec3 wbGain;

uniform highp int useTileFlow;
uniform highp sampler2D tileFlowTex;
uniform highp vec2 tileFlowOriginSrc;
uniform highp vec2 tileFlowStrideSrc;
uniform highp ivec2 tileFlowSize;
uniform highp float tileFlowConfGamma;
uniform highp vec2 globalShift;

layout(location = 0) out highp vec4 fragColor;

const highp float PI = 3.14159265358979323846;

highp float fetchBayer(highp int x, highp int y) {
    x = clamp(x, 0, inWidth - 1);
    y = clamp(y, 0, inHeight - 1);
    return texelFetch(bayerTex, ivec2(x, y), 0).r;
}

highp vec2 cornerShift(highp ivec2 g) {
    highp vec4 s = texelFetch(tileFlowTex, g, 0);
    highp float w = pow(clamp(s.z, 0.0, 1.0), tileFlowConfGamma);
    return mix(globalShift, s.xy, w);
}

highp vec4 sampleQuadInvWb(highp int X, highp int Y) {
    highp int cfa = cfaPattern;
    highp float r = fetchBayer(X, Y);
    highp float g1 = fetchBayer(X + 1, Y);
    highp float g2 = fetchBayer(X, Y + 1);
    highp float b = fetchBayer(X + 1, Y + 1);
    if (cfa == 0) return vec4(r / wbGain.r, g1 / wbGain.g, g2 / wbGain.g, b / wbGain.b);
    if (cfa == 1) return vec4(g1 / wbGain.g, r / wbGain.r, b / wbGain.b, g2 / wbGain.g);
    if (cfa == 2) return vec4(b / wbGain.b, r / wbGain.r, g2 / wbGain.g, g1 / wbGain.g);
    return vec4(g1 / wbGain.g, g2 / wbGain.g, b / wbGain.b, r / wbGain.r);
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

highp vec3 quadToRgb(highp vec4 q, highp int cfa) {
    if (cfa == 0) return vec3(q.r, 0.5 * (q.g + q.b), q.a);
    if (cfa == 1) return vec3(q.g, 0.5 * (q.r + q.a), q.b);
    if (cfa == 2) return vec3(q.b, 0.5 * (q.r + q.a), q.g);
    return vec3(q.a, 0.5 * (q.g + q.b), q.r);
}

void main() {
    highp ivec2 gid = ivec2(gl_FragCoord.xy);
    highp int baseX = gid.x * 2;
    highp int baseY = gid.y * 2;

    highp vec4 quad;
    if (useTileFlow == 0) {
        quad = sampleQuadInvWb(baseX, baseY);
    } else {
        highp float center_x = float(baseX) + 1.0;
        highp float center_y = float(baseY) + 1.0;
        highp vec2 gridF = (vec2(center_x, center_y) - tileFlowOriginSrc) / tileFlowStrideSrc;
        highp ivec2 maxIdx = tileFlowSize - ivec2(1);
        gridF = clamp(gridF, vec2(0.0), vec2(maxIdx));

        highp ivec2 g0 = clamp(ivec2(floor(gridF)), ivec2(0), maxIdx - ivec2(1));
        highp vec2 t = gridF - vec2(g0);
        highp float w00, w10, w01, w11;
        hannCornerWeights(t, w00, w10, w01, w11);
        highp ivec2 s00 = roundBayerShiftEven(cornerShift(g0));
        highp ivec2 s10 = roundBayerShiftEven(cornerShift(g0 + ivec2(1, 0)));
        highp ivec2 s01 = roundBayerShiftEven(cornerShift(g0 + ivec2(0, 1)));
        highp ivec2 s11 = roundBayerShiftEven(cornerShift(g0 + ivec2(1, 1)));
        quad = w00 * sampleQuadInvWb(baseX - s00.x, baseY - s00.y)
             + w10 * sampleQuadInvWb(baseX - s10.x, baseY - s10.y)
             + w01 * sampleQuadInvWb(baseX - s01.x, baseY - s01.y)
             + w11 * sampleQuadInvWb(baseX - s11.x, baseY - s11.y);
    }

    fragColor = vec4(quadToRgb(max(vec4(0.0), quad), cfaPattern), 0.0);
}
