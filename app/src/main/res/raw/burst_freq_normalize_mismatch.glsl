#version 300 es

// Mismatch / mean(mismatch) · 0.12, clamped to [0, 1].
//
// Port of `normalize_mismatch` from hdr-plus-swift/burstphoto/merge/
// frequency.metal:389. The mean is computed CPU-side via a single readback
// of the small (n_tiles_x × n_tiles_y) mismatch texture and passed in as
// `meanMismatch`. The factor 0.12 anchors the per-burst mean to a value
// just below the threshold (0.17) where Wiener weights start letting
// alternate-frame data through.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D mismatchTex;
uniform highp float meanMismatch;

out highp float fragColor;

void main() {
    highp ivec2 tile = ivec2(gl_FragCoord.xy);
    highp float m = texelFetch(mismatchTex, tile, 0).r;
    m *= 0.12 / max(meanMismatch, 1e-12);
    fragColor = clamp(m, 0.0, 1.0);
}
