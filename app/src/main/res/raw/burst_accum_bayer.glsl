#version 300 es

// Wronski Alg. 4: 2× steerable accumulate with Hann flow and robustness.

precision highp float;
precision highp int;
precision highp sampler2D;

const int MERGE_SR_EQUAL    = 0;
const int MERGE_HDR_BRACKET = 1;

uniform highp sampler2D frameBuffer;
uniform highp sampler2D accumBuffer;
uniform highp sampler2D tileFlowTex;
uniform highp sampler2D robustTex;
uniform highp sampler2D frameCov;

uniform highp int useTileFlow;
uniform highp vec2 tileFlowOriginSrc;
uniform highp vec2 tileFlowStrideSrc;
uniform highp ivec2 tileFlowSize;
uniform highp float tileFlowConfGamma;
uniform highp vec2 globalShift;

uniform highp int useRobustness;
uniform highp ivec2 robustSize;
uniform highp int robustHalfRes;
uniform highp float robustRFloor;

uniform highp int useFourCornerFlow;
uniform highp int useSteerableKernel;
uniform highp int mergeMode;
uniform highp float frameRelativeEv;
uniform highp float frameScale;
uniform highp float highlightClipThreshold;

uniform highp int inWidth;
uniform highp int inHeight;

layout(location = 0) out highp vec4 fragColor;

const highp float PI = 3.14159265358979323846;

highp vec2 cornerShift(highp ivec2 g) {
    highp vec4 sampled = texelFetch(tileFlowTex, g, 0);
    highp float conf = clamp(sampled.z, 0.0, 1.0);
    highp float w = pow(conf, tileFlowConfGamma);
    return mix(globalShift, sampled.xy, w);
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

highp vec2 shiftAt1x(highp vec2 lr) {
    if (useTileFlow == 0) return globalShift;
    highp vec2 gridF = (lr - tileFlowOriginSrc) / tileFlowStrideSrc;
    highp ivec2 maxIdx = tileFlowSize - ivec2(1);
    gridF = clamp(gridF, vec2(0.0), vec2(maxIdx));
    if (useFourCornerFlow == 0) {
        highp ivec2 g = clamp(ivec2(round(gridF)), ivec2(0), maxIdx);
        return cornerShift(g);
    }
    highp ivec2 g0 = clamp(ivec2(floor(gridF)), ivec2(0), maxIdx - ivec2(1));
    highp vec2 t = gridF - vec2(g0);
    highp float w00, w10, w01, w11;
    hannCornerWeights(t, w00, w10, w01, w11);
    return w00 * cornerShift(g0)
            + w10 * cornerShift(g0 + ivec2(1, 0))
            + w01 * cornerShift(g0 + ivec2(0, 1))
            + w11 * cornerShift(g0 + ivec2(1, 1));
}

highp float sampleRobust(highp vec2 lr) {
    if (useRobustness == 0) return 1.0;
    highp vec2 pos = lr;
    if (robustHalfRes != 0) {
        pos = (lr + 0.5) * 0.5 - vec2(0.5);
    }
    highp vec2 maxIdx = vec2(robustSize) - vec2(1.0);
    pos = clamp(pos, vec2(0.0), maxIdx);
    highp ivec2 i0 = ivec2(floor(pos));
    highp ivec2 i1 = min(i0 + ivec2(1), ivec2(maxIdx));
    highp vec2 f = pos - vec2(i0);
    highp float v00 = texelFetch(robustTex, i0, 0).r;
    highp float v10 = texelFetch(robustTex, ivec2(i1.x, i0.y), 0).r;
    highp float v01 = texelFetch(robustTex, ivec2(i0.x, i1.y), 0).r;
    highp float v11 = texelFetch(robustTex, i1, 0).r;
    highp float r = mix(mix(v00, v10, f.x), mix(v01, v11, f.x), f.y);
    return max(r, robustRFloor);
}

highp vec3 sampleCovBilinear(highp vec2 greyPos) {
    highp ivec2 covSize = ivec2(inWidth / 2, inHeight / 2);
    highp vec2 maxIdx = vec2(covSize) - vec2(1.0);
    greyPos = clamp(greyPos, vec2(0.0), maxIdx);
    highp ivec2 i0 = ivec2(floor(greyPos));
    highp ivec2 i1 = min(i0 + ivec2(1), ivec2(maxIdx));
    highp vec2 f = greyPos - vec2(i0);
    highp vec3 t00 = texelFetch(frameCov, i0, 0).rgb;
    highp vec3 t10 = texelFetch(frameCov, ivec2(i1.x, i0.y), 0).rgb;
    highp vec3 t01 = texelFetch(frameCov, ivec2(i0.x, i1.y), 0).rgb;
    highp vec3 t11 = texelFetch(frameCov, i1, 0).rgb;
    return mix(mix(t00, t10, f.x), mix(t01, t11, f.x), f.y);
}

void invertCov(highp vec3 cov, out highp float inv_xx, out highp float inv_xy, out highp float inv_yy) {
    highp float det = cov.x * cov.z - cov.y * cov.y;
    highp float invDet = 1.0 / (det + 1e-8);
    inv_xx = invDet * cov.z;
    inv_xy = -invDet * cov.y;
    inv_yy = invDet * cov.x;
}

highp float splatBayer(highp int px, highp int py, highp vec2 srcF, highp float localR) {
    highp int cx = int(floor(srcF.x));
    highp int cy = int(floor(srcF.y));
    highp float subX = srcF.x - 0.5;
    highp float subY = srcF.y - 0.5;

    highp float inv_xx = 2.0;
    highp float inv_xy = 0.0;
    highp float inv_yy = 2.0;
    if (useSteerableKernel != 0) {
        highp vec2 greyPos = srcF * 0.5 - vec2(0.5);
        highp vec3 cov = sampleCovBilinear(greyPos);
        invertCov(cov, inv_xx, inv_xy, inv_yy);
    }

    highp float val = 0.0;
    highp float wSum = 0.0;
    for (highp int di = -1; di <= 1; di++) {
        for (highp int dj = -1; dj <= 1; dj++) {
            highp int j = cx + dj;
            highp int i = cy + di;
            if (j < 0 || i < 0 || j >= inWidth || i >= inHeight) continue;
            if ((j & 1) != px || (i & 1) != py) continue;
            highp float distX = float(j) - subX;
            highp float distY = float(i) - subY;
            highp float z;
            if (useSteerableKernel == 0) {
                z = 2.0 * (distX * distX + distY * distY);
            } else {
                z = inv_xx * distX * distX + 2.0 * inv_xy * distX * distY + inv_yy * distY * distY;
            }
            z = max(z, 0.0);
            highp float w = exp(-0.5 * z);
            val += w * localR * texelFetch(frameBuffer, ivec2(j, i), 0).r;
            wSum += w * localR;
        }
    }
    return (wSum > 1e-8) ? (val / wSum) : 0.0;
}

highp float hdrBracketWeight(highp float frameLum, highp float refLum) {
    highp float wExposure = exp2(frameRelativeEv);
    highp float refClamp = clamp(refLum, 0.0, 1.0);
    highp float shadowMask = 1.0 - smoothstep(0.0, 0.25, refClamp);
    highp float wLum = mix(sqrt(wExposure), wExposure, shadowMask);
    highp float clipLo = highlightClipThreshold * 0.25;
    highp float clipHi = max(highlightClipThreshold, clipLo + 1e-3);
    highp float wHigh  = 1.0 - smoothstep(clipLo, clipHi, frameLum);
    return max(wLum * wHigh, 1e-4);
}

void main() {
    highp ivec2 outPos = ivec2(gl_FragCoord.xy);
    highp int px = outPos.x & 1;
    highp int py = outPos.y & 1;

    highp vec4 accum = texelFetch(accumBuffer, outPos, 0);
    highp vec2 lr = (vec2(outPos) + 0.5) * 0.5;
    highp vec2 srcF = lr - shiftAt1x(lr);

    if (srcF.x < -2.0 || srcF.y < -2.0 ||
        srcF.x >= float(inWidth) + 2.0 ||
        srcF.y >= float(inHeight) + 2.0) {
        fragColor = accum;
        return;
    }

    highp float localR = sampleRobust(lr);
    highp float bayerVal = splatBayer(px, py, srcF, localR);
    highp float frameEq = bayerVal * frameScale;

    highp float weight = (mergeMode == MERGE_HDR_BRACKET)
            ? hdrBracketWeight(bayerVal, frameEq) : 1.0;

    fragColor = accum + vec4(frameEq * weight, 0.0, 0.0, weight);
}
