#version 300 es

// Inverse FFT pass 1 — 1D inverse DFT along Y (rows of FT).
//
// Port of the Y-direction reduce of `backward_fft` from
// hdr-plus-swift/burstphoto/merge/frequency.metal:552. Operates on complex
// spectra packed as (Re, Im) pairs interleaved in X. Output is complex
// (intermediate) — pass 2 (burst_freq_backward_fft_x.glsl) reduces to real
// and applies the 1/(N_alternates · T²) normalisation.
//
// Inverse DFT uses POSITIVE angle: x[n] = Σ_l X[l] · exp(+i·2π·l·n/T).
// No window is applied (the raised cosine was applied at the forward step;
// applying it again here would window-square the signal).

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D inputFtTex;
uniform highp int tileSize;

const highp float PI = 3.14159265358979323846;

layout(location = 0) out highp vec4 fragColor;

void main() {
    highp ivec2 outPos = ivec2(gl_FragCoord.xy);

    highp int m_global = outPos.x >> 1;
    highp int isIm     = outPos.x & 1;
    highp int n_global = outPos.y;

    highp int tileY    = n_global / tileSize;
    highp int nOut     = n_global - tileY * tileSize;  // spatial output bin n
    highp int n0       = tileY * tileSize;

    highp float angle = 2.0 * PI / float(tileSize);    // POSITIVE for inverse

    highp vec4 sumRe = vec4(0.0);
    highp vec4 sumIm = vec4(0.0);

    for (highp int dl = 0; dl < tileSize; dl++) {
        highp vec4 xre = texelFetch(inputFtTex, ivec2(outPos.x & ~1,     n0 + dl), 0);
        highp vec4 xim = texelFetch(inputFtTex, ivec2((outPos.x & ~1)+1, n0 + dl), 0);
        highp float cRe = cos(angle * float(dl * nOut));
        highp float cIm = sin(angle * float(dl * nOut));
        // (cRe + i*cIm) * (xre + i*xim) = (cRe*xre - cIm*xim) + i*(cIm*xre + cRe*xim)
        sumRe += cRe * xre - cIm * xim;
        sumIm += cIm * xre + cRe * xim;
    }

    fragColor = (isIm == 0) ? sumRe : sumIm;
}
