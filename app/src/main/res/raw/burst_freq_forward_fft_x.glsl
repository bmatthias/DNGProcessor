#version 300 es

// Forward FFT pass 2 — 1D DFT along X (rows) over complex column FT data.
//
// Pairs with burst_freq_forward_fft_y.glsl. Together they implement the
// 2D forward FFT used by `forward_fft` from hdr-plus-swift's frequency.metal.
//
// Input layout (from pass 1):
//   intermediateTex[2*m_global+0, n_global] = Re of column DFT, column m_global,
//                                              frequency bin n_in_tile
//   intermediateTex[2*m_global+1, n_global] = Im
// Output layout (final FT):
//   ftTex[2*m_global+0, n_global] = Re of bin (m_in_tile, n_in_tile)
//   ftTex[2*m_global+1, n_global] = Im
//
// Applies the X-direction raised-cosine window inline. The complete window
// at spatial position (dx, dy) is w_x(dx) * w_y(dy) — pass 1 applied w_y,
// this pass applies w_x. Mathematically equivalent to a single 2D window.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D intermediateTex;  // output of pass 1
uniform highp int tileSize;

const highp float PI = 3.14159265358979323846;

layout(location = 0) out highp vec4 fragColor;

void main() {
    highp ivec2 outPos = ivec2(gl_FragCoord.xy);

    highp int m_global = outPos.x >> 1;
    highp int isIm     = outPos.x & 1;
    highp int n_global = outPos.y;

    highp int tileX    = m_global / tileSize;
    highp int mInTile  = m_global - tileX * tileSize;     // output frequency bin m

    highp float angle = -2.0 * PI / float(tileSize);

    highp vec4 sumRe = vec4(0.0);
    highp vec4 sumIm = vec4(0.0);

    for (highp int dx = 0; dx < tileSize; dx++) {
        highp float wx = 0.5 - 0.5 * cos(-angle * (float(dx) + 0.5));
        highp int srcCol = (tileX * tileSize + dx) * 2;
        highp vec4 yre = texelFetch(intermediateTex, ivec2(srcCol,     n_global), 0);
        highp vec4 yim = texelFetch(intermediateTex, ivec2(srcCol + 1, n_global), 0);
        highp float cRe = cos(angle * float(mInTile * dx));
        highp float cIm = sin(angle * float(mInTile * dx));
        // Complex multiply (cRe + i*cIm) * (yre + i*yim) = (cRe*yre - cIm*yim) + i*(cIm*yre + cRe*yim)
        sumRe += wx * (cRe * yre - cIm * yim);
        sumIm += wx * (cIm * yre + cRe * yim);
    }

    fragColor = (isIm == 0) ? sumRe : sumIm;
}
