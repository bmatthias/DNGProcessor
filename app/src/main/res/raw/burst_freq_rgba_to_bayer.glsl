#version 300 es

// Half-res 4-channel RGBA → 1-channel Bayer overlap-add accumulator.
//
// Inverse of `burst_freq_bayer_to_rgba.glsl`. Ports `convert_to_bayer` from
// hdr-plus-swift/burstphoto/texture/texture.metal:299. Each output Bayer pixel
// corresponds to ONE channel of the half-res RGBA texel above it; the channel
// is selected by the pixel's parity inside its 2×2 quad:
//   (even_x, even_y)  → .r
//   (odd_x,  even_y)  → .g
//   (even_x, odd_y)   → .b
//   (odd_x,  odd_y)   → .a
//
// The shader is run as a fragment pass to a Bayer texture (gridOffsetBayer
// is the offset of this pass's half-res grid in the Bayer image; outside that
// region we additively contribute 0 so the running accumulator is unchanged).

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D rgbaTex;   // half-res RGBA from this pass's IFFT output
uniform highp ivec2 gridOffsetBayer;
uniform highp ivec2 halfResSize;   // (n_tiles_x*T, n_tiles_y*T) — valid region of rgbaTex

// Previously-accumulated Bayer (running output across the 4 passes).
uniform highp sampler2D accumBayerTex;

// 1.0 by default; set to 0.0 on the first pass so the accumulator is initialised.
uniform highp float passWeight;

out highp float fragColor;

void main() {
    highp ivec2 outPos = ivec2(gl_FragCoord.xy);

    // Locate output in BAYER coords relative to this pass's grid origin.
    highp int rx = outPos.x - gridOffsetBayer.x;
    highp int ry = outPos.y - gridOffsetBayer.y;

    highp float prev = passWeight * texelFetch(accumBayerTex, outPos, 0).r;

    // If outside this pass's valid region, output the running accumulator unchanged.
    if (rx < 0 || ry < 0 || rx >= halfResSize.x * 2 || ry >= halfResSize.y * 2) {
        fragColor = prev;
        return;
    }

    highp int qx = rx >> 1;          // half-res pixel x
    highp int qy = ry >> 1;          // half-res pixel y
    highp int sx = rx & 1;           // 0 / 1
    highp int sy = ry & 1;

    highp vec4 rgba = texelFetch(rgbaTex, ivec2(qx, qy), 0);
    highp float val;
    if (sy == 0) {
        val = (sx == 0) ? rgba.r : rgba.g;
    } else {
        val = (sx == 0) ? rgba.b : rgba.a;
    }

    fragColor = prev + val;
}
