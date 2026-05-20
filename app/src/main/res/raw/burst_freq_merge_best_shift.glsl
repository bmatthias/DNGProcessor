#version 300 es

// Per-tile 49-shift Fourier sub-pixel refinement.
//
// First half of the port of `merge_frequency_domain` from hdr-plus-swift/
// burstphoto/merge/frequency.metal:13 . The 64-bin × 49-candidate residual
// loop is run once per tile to pick the best sub-pixel shift; the second
// half (per-bin Wiener) reads this small (n_tiles_x × n_tiles_y) RG16F
// texture and applies the shift via the Fourier shift theorem.
//
// 49 candidate shifts cover (-0.5, +0.5) half-res-px in steps of 1/6.
// Equivalent to ±1 Bayer-pixel residual error per tile after BayerAlignment.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D refFtTex;
uniform highp sampler2D alignedFtTex;
uniform highp int tileSize;            // T

const highp float PI = 3.14159265358979323846;

layout(location = 0) out highp vec4 fragColor;

void main() {
    highp ivec2 tile = ivec2(gl_FragCoord.xy);
    highp int m0 = tile.x * tileSize;
    highp int n0 = tile.y * tileSize;

    highp float angle = -2.0 * PI / float(tileSize);
    highp float step = 1.0 / 6.0;

    highp float totalDiff[49];
    for (highp int i = 0; i < 49; i++) totalDiff[i] = 0.0;

    for (highp int dn = 0; dn < tileSize; dn++) {
        for (highp int dm = 0; dm < tileSize; dm++) {
            highp int m2 = 2 * (m0 + dm);
            highp int n  = n0 + dn;
            highp vec4 refRe = texelFetch(refFtTex, ivec2(m2,     n), 0);
            highp vec4 refIm = texelFetch(refFtTex, ivec2(m2 + 1, n), 0);
            highp vec4 alRe  = texelFetch(alignedFtTex, ivec2(m2,     n), 0);
            highp vec4 alIm  = texelFetch(alignedFtTex, ivec2(m2 + 1, n), 0);

            for (highp int i = 0; i < 49; i++) {
                highp float sx = -0.5 + float(i - (i / 7) * 7) * step;
                highp float sy = -0.5 + float(i / 7) * step;
                highp float a = angle * (float(dm) * sx + float(dn) * sy);
                highp float cRe = cos(a);
                highp float cIm = sin(a);
                highp vec4 dRe = refRe - (cRe * alRe - cIm * alIm);
                highp vec4 dIm = refIm - (cIm * alRe + cRe * alIm);
                highp vec4 w4 = dRe * dRe + dIm * dIm;
                totalDiff[i] += w4.x + w4.y + w4.z + w4.w;
            }
        }
    }

    highp int bestI = 0;
    highp float bestDiff = totalDiff[0];
    for (highp int i = 1; i < 49; i++) {
        if (totalDiff[i] < bestDiff) {
            bestDiff = totalDiff[i];
            bestI = i;
        }
    }
    highp float bestSx = -0.5 + float(bestI - (bestI / 7) * 7) * step;
    highp float bestSy = -0.5 + float(bestI / 7) * step;
    fragColor = vec4(bestSx, bestSy, 0.0, 0.0);
}
