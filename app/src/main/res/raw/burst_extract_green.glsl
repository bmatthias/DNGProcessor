#version 300 es

// Explicit highp required by GLES 3.0 §4.5.3 for stable accumulation.
precision highp float;
precision highp int;
precision highp sampler2D;

// 1-channel (Bayer) or 4-channel (linear RGB) preprocessed sensor texture
uniform highp sampler2D rawBuffer;

// 1 if input is linear raw (RGBA), 0 if Bayer (single channel)
uniform highp int isLinearRaw;

// Per-frame exposure-equalisation scale: brings this frame into the reference
// frame's EV. Set to 1.0 for the reference frame and for uniform-exposure
// bursts; equal to 1 / 2^relativeEv (≤ 1) for brighter bracket frames so that
// SAD-based block matching compares same-radiance pixels across exposures.
uniform highp float frameScale;

out highp float result;

// Average over a 4x4 block to produce a downsampled luma value.
// Each output pixel represents a 4x4 region in the input sensor texture.
// For Bayer: uses .r channel (the single stored value).
// For linear raw: uses .g channel (green is the dominant luma contributor).

void main() {
    highp ivec2 blockOrigin = ivec2(gl_FragCoord.xy) * 4;
    highp float total = 0.0;
    for (highp int dy = 0; dy < 4; dy++) {
        for (highp int dx = 0; dx < 4; dx++) {
            highp ivec2 src = blockOrigin + ivec2(dx, dy);
            if (isLinearRaw == 1) {
                total += texelFetch(rawBuffer, src, 0).g;
            } else {
                total += texelFetch(rawBuffer, src, 0).r;
            }
        }
    }
    result = (total / 16.0) * frameScale;
}
