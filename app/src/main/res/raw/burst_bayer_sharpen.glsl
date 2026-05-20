#version 300 es

// CFA-safe unsharp mask on merged 2× Bayer (restores edge acutance after accumulate).

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D bayerTex;
uniform highp int inWidth;
uniform highp int inHeight;
uniform highp float amount;

layout(location = 0) out highp float fragColor;

highp float fetchSameCfa(highp int px, highp int py, highp int sx, highp int sy) {
    sx = clamp(sx, 0, inWidth - 1);
    sy = clamp(sy, 0, inHeight - 1);
    if ((sx & 1) != px || (sy & 1) != py) {
        return 0.0;
    }
    return texelFetch(bayerTex, ivec2(sx, sy), 0).r;
}

void main() {
    highp ivec2 pos = ivec2(gl_FragCoord.xy);
    highp int px = pos.x & 1;
    highp int py = pos.y & 1;

    highp float center = texelFetch(bayerTex, pos, 0).r;

    highp float blur = 0.0;
    highp float wSum = 0.0;
    for (highp int dy = -1; dy <= 1; dy++) {
        for (highp int dx = -1; dx <= 1; dx++) {
            highp float w = (dx == 0 && dy == 0) ? 4.0 : (abs(dx) + abs(dy) == 1) ? 2.0 : 1.0;
            highp float s = fetchSameCfa(px, py, pos.x + dx, pos.y + dy);
            blur += w * s;
            wSum += w;
        }
    }
    blur /= wSum;

    highp float sharp = center + amount * (center - blur);
    fragColor = max(sharp, 0.0);
}
