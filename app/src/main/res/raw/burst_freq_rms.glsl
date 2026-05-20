#version 300 es

// Per-tile RMS of the reference half-res RGBA texture.
//
// Port of `calculate_rms_rgba` from hdr-plus-swift/burstphoto/merge/
// frequency.metal:296. Each fragment computes one tile's noise estimate
// (vec4: one value per CFA channel), used to scale the Wiener filter's
// σ² term in burst_freq_merge.glsl.
//
// Output texture dims: (n_tiles_x, n_tiles_y), one fragment per tile.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D refRgbaTex;
uniform highp int tileSize;            // T

layout(location = 0) out highp vec4 fragColor;

void main() {
    highp ivec2 tile = ivec2(gl_FragCoord.xy);
    highp int x0 = tile.x * tileSize;
    highp int y0 = tile.y * tileSize;

    highp vec4 sumSq = vec4(0.0);
    for (highp int dy = 0; dy < tileSize; dy++) {
        for (highp int dx = 0; dx < tileSize; dx++) {
            highp vec4 v = texelFetch(refRgbaTex, ivec2(x0 + dx, y0 + dy), 0);
            sumSq += v * v;
        }
    }

    // Match metal: noise_est = 0.25 * sqrt(sum_sq) / T
    fragColor = 0.25 * sqrt(sumSq) / float(tileSize);
}
