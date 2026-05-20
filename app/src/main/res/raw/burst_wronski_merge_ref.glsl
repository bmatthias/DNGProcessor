#version 300 es

// Wronski Alg. 11: merge reference frame with optional accumulated-r denoise.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D refFrame;
uniform highp sampler2D accumBuffer;
uniform highp sampler2D frameCov;
uniform highp sampler2D accRobTex;
uniform highp ivec2 accRobSize;
uniform highp int accRobHalfRes;

uniform highp int inWidth;
uniform highp int inHeight;
uniform highp int useSteerableKernel;
uniform highp int useAccRobDenoise;
uniform highp float accMaxMultiplier;
uniform highp int accMaxFrameCount;
uniform highp int accRadMax;

layout(location = 0) out highp vec4 fragColor;

highp vec3 sampleCovBilinear(highp vec2 greyPos, highp ivec2 covSize) {
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

highp float denoisePower(highp float accR) {
    if (!bool(useAccRobDenoise)) return 1.0;
    return (accR <= float(accMaxFrameCount)) ? accMaxMultiplier : 1.0;
}

highp int denoiseRange(highp float accR) {
    if (!bool(useAccRobDenoise)) return 1;
    return (accR <= float(accMaxFrameCount)) ? accRadMax : 1;
}

void main() {
    highp ivec2 outPos = ivec2(gl_FragCoord.xy);
    highp int px = outPos.x & 1;
    highp int py = outPos.y & 1;

    highp vec4 accum = texelFetch(accumBuffer, outPos, 0);
    highp vec2 coarse = (vec2(outPos) + 0.5) * 0.5;

    highp vec2 accPos = coarse;
    if (accRobHalfRes != 0) {
        accPos = coarse * 0.5;
    }
    highp vec2 accMax = vec2(accRobSize) - vec2(1.0);
    accPos = clamp(accPos, vec2(0.0), accMax);
    highp ivec2 accI0 = ivec2(floor(accPos));
    highp ivec2 accI1 = min(accI0 + ivec2(1), ivec2(accMax));
    highp vec2 accF = accPos - vec2(accI0);
    highp float localAccR = mix(
            mix(texelFetch(accRobTex, accI0, 0).r,
                texelFetch(accRobTex, ivec2(accI1.x, accI0.y), 0).r, accF.x),
            mix(texelFetch(accRobTex, ivec2(accI0.x, accI1.y), 0).r,
                texelFetch(accRobTex, accI1, 0).r, accF.x),
            accF.y);
    highp float addPower = denoisePower(localAccR);
    highp int rad = denoiseRange(localAccR);

    highp int cx = int(round(coarse.x));
    highp int cy = int(round(coarse.y));
    highp float subX = coarse.x - 0.5;
    highp float subY = coarse.y - 0.5;

    highp vec3 invOmega = vec3(2.0, 0.0, 2.0);
    if (useSteerableKernel != 0) {
        highp vec2 greyPos = coarse * 0.5 - vec2(0.5);
        highp vec3 cov = sampleCovBilinear(greyPos, ivec2(inWidth / 2, inHeight / 2));
        invertCov(cov, invOmega.x, invOmega.y, invOmega.z);
    }

    highp float val = 0.0;
    highp float wSum = 0.0;
    for (highp int di = -rad; di <= rad; di++) {
        for (highp int dj = -rad; dj <= rad; dj++) {
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
                z = invOmega.x * distX * distX + 2.0 * invOmega.y * distX * distY + invOmega.z * distY * distY;
            }
            z = max(z / addPower, 0.0);
            highp float w = exp(-0.5 * z);
            val += w * texelFetch(refFrame, ivec2(j, i), 0).r;
            wSum += w;
        }
    }

    // Always add ref splat; widened kernel (rad / addPower) handles low-merge regions.
    fragColor = accum + vec4(val, 0.0, 0.0, wSum);
}
