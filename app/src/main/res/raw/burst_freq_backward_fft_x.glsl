#version 300 es

// Inverse FFT pass 2 — 1D inverse DFT along X, real output.
//
// Pairs with burst_freq_backward_fft_y.glsl. Reads the Y-reduced complex
// intermediate, computes the X-direction inverse DFT, sums to the real
// output, divides by N_alternates · T². Output is a half-res RGBA texture
// (R, Gtr, Gbl, B) at the same dims as the original packed half-res input.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D intermediateTex;
uniform highp int tileSize;
uniform highp int nTextures;   // N_alternates including reference (>= 1)

const highp float PI = 3.14159265358979323846;

layout(location = 0) out highp vec4 fragColor;

void main() {
    highp ivec2 outPos = ivec2(gl_FragCoord.xy);

    // Output is real-valued half-res RGBA at (mOut, nOut).
    highp int mOut_global = outPos.x;
    highp int nOut_global = outPos.y;

    highp int tileX = mOut_global / tileSize;
    highp int mOut  = mOut_global - tileX * tileSize;
    highp int m0    = tileX * tileSize;

    highp float angle = 2.0 * PI / float(tileSize);

    highp vec4 sumRe = vec4(0.0);

    for (highp int dk = 0; dk < tileSize; dk++) {
        highp int srcCol = (m0 + dk) * 2;
        highp vec4 xre = texelFetch(intermediateTex, ivec2(srcCol,     nOut_global), 0);
        highp vec4 xim = texelFetch(intermediateTex, ivec2(srcCol + 1, nOut_global), 0);
        highp float cRe = cos(angle * float(dk * mOut));
        highp float cIm = sin(angle * float(dk * mOut));
        // Inverse: real part = cRe*xre - cIm*xim (we only need the real part).
        sumRe += cRe * xre - cIm * xim;
    }

    highp float normFactor = 1.0 / (float(nTextures) * float(tileSize) * float(tileSize));
    fragColor = sumRe * normFactor;
}
