#version 300 es

// Per-tile mean brightness of the half-res RGBA reference texture.
//
// Counterpart to burst_freq_rms.glsl: where rms produces a per-tile RMS,
// this produces a per-tile MEAN value per CFA channel. The pair (mean,
// rms²) feeds the per-burst adaptive noise fit (Wronski Algorithm 12
// `apply_noise_model` analogue + PhotonCamera 2D histogram fit) that
// upgrades the global noise model from the spec-sheet (read noise + photon
// noise) to a fit on the actual burst's reference frame.
//
// Output texture dims: (n_tiles_x, n_tiles_y), RGBA, one fragment per tile.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D refRgbaTex;
uniform highp int tileSize;            // T (half-res pixels per tile)

layout(location = 0) out highp vec4 fragColor;

void main() {
    highp ivec2 tile = ivec2(gl_FragCoord.xy);
    highp int x0 = tile.x * tileSize;
    highp int y0 = tile.y * tileSize;

    highp vec4 sum = vec4(0.0);
    for (highp int dy = 0; dy < tileSize; dy++) {
        for (highp int dx = 0; dx < tileSize; dx++) {
            sum += texelFetch(refRgbaTex, ivec2(x0 + dx, y0 + dy), 0);
        }
    }
    fragColor = sum / float(tileSize * tileSize);
}
