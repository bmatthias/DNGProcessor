#version 300 es

// 5x5 max-pool of the normalised per-tile mismatch — equivalent to a 5x5
// local-min pool of the robustness map (since mismatch is inverse-robust).
//
// Port of Wronski 2019 "Handheld Multi-Frame Super-Resolution" Algorithm 9
// (`local_min`), see robustness.py:641-687 in
// /Users/matthias/Code/Handheld-Multi-Frame-Super-Resolution.
//
// Why: a single corrupted tile (occlusion edge, parallax disocclusion) drags
// its neighbours' robustness down too. Dilating the rejection region by 2
// tiles in every direction prevents single-tile "ghost speckles" from
// leaking through the Wiener merge.
//
// Input  : single-channel normalised mismatch, dims (n_tiles_x, n_tiles_y).
// Output : same dims, each fragment = max of mismatch over a 5x5 window
//          centred on the tile, clamped to image edges.
// Cost   : 25 texelFetch + 24 max per tile, well below 1 ms even at 4K.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D mismatchTex;
uniform highp ivec2 nTiles;

out highp float fragColor;

void main() {
    highp ivec2 t = ivec2(gl_FragCoord.xy);
    highp float m = 0.0;
    for (highp int dy = -2; dy <= 2; dy++) {
        for (highp int dx = -2; dx <= 2; dx++) {
            highp ivec2 p = ivec2(
                    clamp(t.x + dx, 0, nTiles.x - 1),
                    clamp(t.y + dy, 0, nTiles.y - 1));
            m = max(m, texelFetch(mismatchTex, p, 0).r);
        }
    }
    fragColor = m;
}
