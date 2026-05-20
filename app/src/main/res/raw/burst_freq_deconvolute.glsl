#version 300 es

// Per-tile sub-frame-misalignment deconvolution.
//
// Port of `deconvolute_frequency_domain` from hdr-plus-swift/burstphoto/
// merge/frequency.metal:324. Slight high-frequency boost of the accumulated
// FT spectrum, scaled by per-bin tile-specific magnitudes and the running
// total mismatch (heavier sharpening on well-aligned tiles).
//
// Each fragment reads the existing accumulator value and rewrites it.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D accumFtTex;        // read+write (per-fragment)
uniform highp sampler2D totalMismatchTex;  // (n_tiles_x, n_tiles_y) R
uniform highp int tileSize;                // T (must be 8 or 16)

layout(location = 0) out highp vec4 fragColor;

void main() {
    highp ivec2 outPos = ivec2(gl_FragCoord.xy);
    highp int m_global = outPos.x >> 1;
    highp int isIm     = outPos.x & 1;
    highp int n_global = outPos.y;

    highp int tileX = m_global / tileSize;
    highp int dm    = m_global - tileX * tileSize;
    highp int tileY = n_global / tileSize;
    highp int dn    = n_global - tileY * tileSize;

    highp vec4 cur = texelFetch(accumFtTex, outPos, 0);

    // DC bin and very-large mismatch tiles get no deconvolution.
    if ((dm + dn) == 0) {
        fragColor = cur;
        return;
    }
    highp float mismatch = texelFetch(totalMismatchTex, ivec2(tileX, tileY), 0).r;
    if (mismatch >= 0.3) {
        fragColor = cur;
        return;
    }
    highp float mismatchWeight = clamp(1.0 - 10.0 * (mismatch - 0.2), 0.0, 1.0);

    // T-dependent gains from metal kernel.
    // cw[8]  = {0, 0.02, 0.04, 0.08, 0.04, 0.08, 0.04, 0.02}
    // cw[16] = {0, 0.01, 0.02, 0.03, 0.04, 0.06, 0.08, 0.06, 0.04, 0.06, 0.08, 0.06, 0.04, 0.03, 0.02, 0.01}
    highp float cw[16];
    if (tileSize == 8) {
        cw[0]=0.0;  cw[1]=0.02; cw[2]=0.04; cw[3]=0.08;
        cw[4]=0.04; cw[5]=0.08; cw[6]=0.04; cw[7]=0.02;
        for (highp int i = 8; i < 16; i++) cw[i] = 0.0;
    } else {
        cw[0]=0.0;  cw[1]=0.01; cw[2]=0.02; cw[3]=0.03;
        cw[4]=0.04; cw[5]=0.06; cw[6]=0.08; cw[7]=0.06;
        cw[8]=0.04; cw[9]=0.06; cw[10]=0.08; cw[11]=0.06;
        cw[12]=0.04; cw[13]=0.03; cw[14]=0.02; cw[15]=0.01;
    }

    // Need DC magnitude and current bin magnitude.
    highp int dcX = 2 * (tileX * tileSize);
    highp int dcY = tileY * tileSize;
    highp vec4 dcRe = texelFetch(accumFtTex, ivec2(dcX,     dcY), 0);
    highp vec4 dcIm = texelFetch(accumFtTex, ivec2(dcX + 1, dcY), 0);
    highp vec4 dcMag = sqrt(dcRe * dcRe + dcIm * dcIm);
    highp float magnitudeZero = dcMag.x + dcMag.y + dcMag.z + dcMag.w;

    // Current bin magnitude (need both Re and Im to compute it).
    highp vec4 curRe = (isIm == 0) ? cur : texelFetch(accumFtTex, ivec2(outPos.x - 1, outPos.y), 0);
    highp vec4 curIm = (isIm == 1) ? cur : texelFetch(accumFtTex, ivec2(outPos.x + 1, outPos.y), 0);
    highp vec4 curMag = sqrt(curRe * curRe + curIm * curIm);
    highp float magnitude = curMag.x + curMag.y + curMag.z + curMag.w;

    highp float ratio = magnitude / max(magnitudeZero, 1e-12);
    highp float weight = mismatchWeight * clamp(1.25 - 25.0 * ratio, 0.0, 1.0);
    highp float scale = (1.0 + weight * cw[dm]) * (1.0 + weight * cw[dn]);

    fragColor = scale * cur;
}
