#version 300 es

// Nearest-neighbour 1× → 2× Bayer upsampler. CFA-preserving: output pixel
// (X, Y) is the value of the nearest input pixel with matching CFA parity
// ((X & 1, Y & 1)).
//
// This shader exists purely for debugging: it produces a 2× output texture
// of the EXACT same dimensions and layout as `burst_sr_upsample.glsl`, but
// without ANY interpolation, anisotropic kernels, structure tensors, or
// Gaussian weighting. If the side-car DNG produced from this shader looks
// clean while the one from `burst_sr_upsample.glsl` looks corrupted, the
// bug is in the upsample shader. If both look corrupted, the bug is in the
// 2×-resolution DNG export / readback path.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D mergedBayerTex;
uniform highp int inWidth;
uniform highp int inHeight;

out highp float fragColor;

void main() {
    highp ivec2 outPos = ivec2(gl_FragCoord.xy);
    highp int px = outPos.x & 1;
    highp int py = outPos.y & 1;

    // Centre of this output pixel in source coords: half-pixel offset.
    highp float src_x = (float(outPos.x) + 0.5) * 0.5;
    highp float src_y = (float(outPos.y) + 0.5) * 0.5;

    // Snap src to nearest same-parity input pixel. nearestEvenOrOdd:
    //   for parity 0 → nearest even integer to src
    //   for parity 1 → nearest odd integer to src
    highp int rx = int(floor(src_x + 0.5));
    if ((rx & 1) != px) {
        rx = (src_x > float(rx)) ? rx + 1 : rx - 1;
    }
    highp int ry = int(floor(src_y + 0.5));
    if ((ry & 1) != py) {
        ry = (src_y > float(ry)) ? ry + 1 : ry - 1;
    }
    rx = clamp(rx, 0, inWidth - 1);
    ry = clamp(ry, 0, inHeight - 1);

    fragColor = texelFetch(mergedBayerTex, ivec2(rx, ry), 0).r;
}
