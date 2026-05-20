#version 300 es

// Forward FFT pass 1 — 1D DFT along Y (columns) with raised-cosine window.
//
// Port of the column-wise DFT inside `forward_fft` from
// hdr-plus-swift/burstphoto/merge/frequency.metal:837 .
//
// GLES 3.0 fragment shaders don't have shared memory, so the 2D FFT is
// implemented as TWO separable 1D DFTs:
//   pass 1 (this shader): for each (column dx, output bin n) compute the
//                         column DFT of x[dx, dy] * w_y(dy). Output complex.
//   pass 2 (burst_freq_forward_fft_x.glsl): for each (output bin m, output
//                         bin n) combine columns via x DFT and apply w_x.
//
// Per-fragment cost (T=8): 8 input reads × 1 trig pair = ~16 fma per channel.
// Total ops match the metal radix-4 path within a factor of 2 — the savings
// from radix-4 butterflies are reserved for when we move FFT into a compute
// shader (GLES 3.1) with threadgroup memory.

precision highp float;
precision highp int;
precision highp sampler2D;

// Input: half-res RGBA (R, Gtr, Gbl, B) from burst_freq_bayer_to_rgba.
uniform highp sampler2D inputTex;
uniform highp int tileSize;           // T (= 8)

const highp float PI = 3.14159265358979323846;

layout(location = 0) out highp vec4 fragColor;

void main() {
    highp ivec2 outPos = ivec2(gl_FragCoord.xy);

    // Frequency-texture layout:
    //   x ∈ [0, 2*nTilesX*T)  with x=2*m_global+isIm
    //   y ∈ [0, nTilesY*T)
    // We index by output bin (n_in_tile) but pass through the spatial x
    // coordinate (dx_in_tile) since the x-transform happens in pass 2.
    highp int m_global = outPos.x >> 1;
    highp int isIm     = outPos.x & 1;
    highp int n_global = outPos.y;

    highp int tileX     = m_global / tileSize;
    highp int dxInTile  = m_global - tileX * tileSize;     // spatial column within tile
    highp int tileY     = n_global / tileSize;
    highp int nInTile   = n_global - tileY * tileSize;     // frequency bin n

    // Spatial position of the column in the half-res texture.
    highp int spatial_x = tileX * tileSize + dxInTile;
    highp int spatial_y0 = tileY * tileSize;

    highp float angle = -2.0 * PI / float(tileSize);

    highp vec4 sum = vec4(0.0);

    for (highp int dy = 0; dy < tileSize; dy++) {
        // Raised-cosine window along Y (Hann, period T, sampled at dy+0.5).
        // The X-direction window is applied in pass 2.
        highp float wy = 0.5 - 0.5 * cos(-angle * (float(dy) + 0.5));
        highp vec4 x = texelFetch(inputTex, ivec2(spatial_x, spatial_y0 + dy), 0);
        highp float c;
        if (isIm == 0) {
            c = cos(angle * float(nInTile * dy));
        } else {
            c = sin(angle * float(nInTile * dy));
        }
        sum += (wy * c) * x;
    }

    fragColor = sum;
}
