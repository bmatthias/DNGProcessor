#version 300 es

// Wronski Alg. 5: per-frame steerable kernel covariance Ω (half-res grey grid).

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D bayerTex;
uniform highp int inWidth;
uniform highp int inHeight;

uniform highp float kDetail;
uniform highp float kDenoise;
uniform highp float dTh;
uniform highp float dTr;
uniform highp float kStretch;
uniform highp float kShrink;

layout(location = 0) out highp vec4 fragColor;

highp float fetchBayer(int x, int y) {
    x = clamp(x, 0, inWidth - 1);
    y = clamp(y, 0, inHeight - 1);
    return texelFetch(bayerTex, ivec2(x, y), 0).r;
}

highp float decimateGrey(highp ivec2 gid) {
    int bx = gid.x * 2;
    int by = gid.y * 2;
    return 0.25 * (fetchBayer(bx, by) + fetchBayer(bx + 1, by)
            + fetchBayer(bx, by + 1) + fetchBayer(bx + 1, by + 1));
}

void computeK(highp float l1, highp float l2, out highp float k1, out highp float k2) {
    highp float A = 1.0 + sqrt(max((l1 - l2) / (l1 + l2 + 1e-8), 0.0));
    highp float D = clamp(1.0 - sqrt(max(l1, 0.0)) / dTr + dTh, 0.0, 1.0);
    k1 = kDetail * ((1.0 - D) * (1.0 + A * 0.5 * (1.0 / kShrink - 1.0)) + D * kDenoise);
    k2 = kDetail * ((1.0 - D) * (1.0 + A * 0.5 * (kStretch - 1.0)) + D * kDenoise);
}

void main() {
    highp ivec2 gid = ivec2(gl_FragCoord.xy);
    highp int gw = inWidth / 2;
    highp int gh = inHeight / 2;
    highp int px = gid.x;
    highp int py = gid.y;

    highp float sxx = 0.0;
    highp float syy = 0.0;
    highp float sxy = 0.0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            int x = px + dx;
            int y = py + dy;
            if (x < 0 || y < 0 || x >= gw || y >= gh) continue;
            highp float ix = decimateGrey(ivec2(x + 1, y)) - decimateGrey(ivec2(x - 1, y));
            highp float iy = decimateGrey(ivec2(x, y + 1)) - decimateGrey(ivec2(x, y - 1));
            sxx += ix * ix;
            syy += iy * iy;
            sxy += ix * iy;
        }
    }

    highp float halfTrace = (sxx + syy) * 0.5;
    highp float disc = max(((sxx - syy) * 0.5) * ((sxx - syy) * 0.5) + sxy * sxy, 0.0);
    highp float root = sqrt(disc);
    highp float l1 = halfTrace + root;
    highp float l2 = halfTrace - root;

    highp float k1, k2;
    computeK(l1, l2, k1, k2);

    highp vec2 e1;
    if (abs(sxy) > 1e-8) {
        e1 = normalize(vec2(sxy, l1 - sxx));
    } else {
        e1 = (sxx >= syy) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
    }
    highp vec2 e2 = vec2(-e1.y, e1.x);
    highp float k1sq = k1 * k1;
    highp float k2sq = k2 * k2;
    highp float cxx = k1sq * e1.x * e1.x + k2sq * e2.x * e2.x;
    highp float cxy = k1sq * e1.x * e1.y + k2sq * e2.x * e2.y;
    highp float cyy = k1sq * e1.y * e1.y + k2sq * e2.y * e2.y;
    fragColor = vec4(cxx, cxy, cyy, 1.0);
}
