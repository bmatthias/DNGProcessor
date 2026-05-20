#version 300 es

// Per-tile cosine-windowed mismatch.
//
// Port of `calculate_mismatch_rgba` from hdr-plus-swift/burstphoto/merge/
// frequency.metal:234. Sums |ref - aligned| with a modified raised-cosine
// weight (0.5 - 0.17 cos), normalises by the sum of weights, then divides
// by the per-tile noise estimate to get a unit-less "mismatch" value.
//
// Spatial support is 2× the tile size (centred on the tile), so the per-tile
// mismatch responds to motion that overlaps the tile from any neighbour.
// Plan item 8: a 5×5 local-min pooling of the robustness map is added after
// this stage (see local_min_pooling todo) — it expands rejection regions
// without changing this kernel.
//
// Output dims: (n_tiles_x, n_tiles_y), single-channel float.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D absDiffTex;     // per-pixel half-res RGBA
uniform highp sampler2D rmsTex;         // per-tile RMS, dims (n_tiles_x, n_tiles_y)
uniform highp int tileSize;
uniform highp float exposureFactor;     // = 2^(comp_ev - ref_ev); 1.0 for uniform bursts
uniform highp ivec2 absDiffSize;        // (half-res width, height)

const highp float PI = 3.14159265358979323846;

out highp float fragColor;

void main() {
    highp ivec2 tile = ivec2(gl_FragCoord.xy);
    highp int x0 = tile.x * tileSize;
    highp int y0 = tile.y * tileSize;

    highp int x_start = max(0, x0 - tileSize / 2);
    highp int y_start = max(0, y0 - tileSize / 2);
    highp int x_end   = min(absDiffSize.x - 1, x0 + tileSize * 3 / 2);
    highp int y_end   = min(absDiffSize.y - 1, y0 + tileSize * 3 / 2);

    highp int xShift = -(x0 - tileSize / 2);
    highp int yShift = -(y0 - tileSize / 2);

    highp float angle = -2.0 * PI / float(tileSize);

    highp vec4 sum = vec4(0.0);
    highp float nTotal = 0.0;
    for (highp int dy = y_start; dy < y_end; dy++) {
        for (highp int dx = x_start; dx < x_end; dx++) {
            highp float wx = 0.5 - 0.17 * cos(-angle * (float(dx + xShift) + 0.5));
            highp float wy = 0.5 - 0.17 * cos(-angle * (float(dy + yShift) + 0.5));
            highp float w = wx * wy;
            sum += w * texelFetch(absDiffTex, ivec2(dx, dy), 0);
            nTotal += w;
        }
    }
    sum /= max(nTotal, 1e-12);

    highp vec4 noiseEst = texelFetch(rmsTex, tile, 0);
    highp vec4 mismatch4 = sum / sqrt(0.5 * noiseEst + 0.5 * noiseEst / max(exposureFactor, 1e-6) + 1.0);
    fragColor = 0.25 * (mismatch4.x + mismatch4.y + mismatch4.z + mismatch4.w);
}
