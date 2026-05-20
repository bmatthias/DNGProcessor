#version 300 es

// Per-tile flow irregularity factor S (Wronski compute_s), one value per alignment tile.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D tileFlowTex;

layout(location = 0) out highp float fragColor;

void main() {
    highp ivec2 g = ivec2(gl_FragCoord.xy);
    highp float maxMag = -1.0;
    highp float minMag = 1e20;
    for (highp int dy = -1; dy <= 1; dy++) {
        for (highp int dx = -1; dx <= 1; dx++) {
            highp vec2 sh = texelFetch(tileFlowTex, g + ivec2(dx, dy), 0).xy;
            highp float mag = length(sh);
            maxMag = max(maxMag, mag);
            minMag = min(minMag, mag);
        }
    }
    highp float spread = maxMag - minMag;
    fragColor = (spread * spread > 0.64) ? 2.0 : 12.0;
}
