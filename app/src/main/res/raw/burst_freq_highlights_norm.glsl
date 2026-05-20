#version 300 es

// Per-tile saturation indicator for bracketed bursts.
//
// Port of `calculate_highlights_norm_rgba` from hdr-plus-swift/burstphoto/
// merge/frequency.metal:191. Only active when the alternate frame's exposure
// differs from the reference (exposureFactor > 1.001). Returns 1.0 when no
// correction is needed (uniform exposure) — i.e. the Wiener denominator is
// not scaled — and < 1.0 to suppress clipped highlights in the merge.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D alignedRgbaTex;
uniform highp int tileSize;
uniform highp float exposureFactor;
uniform highp float whiteLevel;         // -1 → use 1e6 (effectively disable)
uniform highp float blackLevelMean;

out highp float fragColor;

void main() {
    highp ivec2 tile = ivec2(gl_FragCoord.xy);

    if (exposureFactor <= 1.001) {
        fragColor = 1.0;
        return;
    }

    highp int x0 = tile.x * tileSize;
    highp int y0 = tile.y * tileSize;

    highp float clippedNorm = 0.0;
    highp float wl = (whiteLevel < 0.0) ? 1.0e6 : whiteLevel;
    for (highp int dy = 0; dy < tileSize; dy++) {
        for (highp int dx = 0; dx < tileSize; dx++) {
            highp vec4 v = texelFetch(alignedRgbaTex, ivec2(x0 + dx, y0 + dy), 0);
            highp float vmax = max(max(v.x, v.y), max(v.z, v.w));
            vmax = (vmax - blackLevelMean) * exposureFactor + blackLevelMean;
            clippedNorm += clamp((vmax / wl - 0.50) / 0.49, 0.0, 1.0);
        }
    }
    clippedNorm /= float(tileSize * tileSize);
    clippedNorm = (1.0 - clippedNorm) * (1.0 - clippedNorm);
    highp float floorVal = 0.04 / min(exposureFactor, 4.0);
    fragColor = clamp(clippedNorm, floorVal, 1.0);
}
