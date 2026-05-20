#version 300 es

// In-place per-pixel hot-pixel detection + correction (PhotonCamera-borrow,
// `PyramidMerging.java:76-250`, `merge/hotpixeldetect.glsl` +
// `hotpixelcorrect.glsl`, simplified to a single GPU pass).
//
// For each Bayer pixel:
//   1. Read the 4 same-CFA-colour neighbours at offsets (±2, 0) and (0, ±2).
//   2. Compute their mean and standard deviation.
//   3. If |centre − mean| > k · std → flag as hot.
//   4. Hot pixels are replaced with the MEDIAN of the 4 neighbours; clean
//      pixels are passed through unchanged.
//
// Operates on a 1-channel Bayer texture (post-BurstPreProcess: black-level
// subtracted, normalised to [0, 1]). The 2-pixel stride preserves CFA
// parity — the neighbours are guaranteed to be the same colour as the
// centre. A 2-pixel border on each side is left unchanged.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D bayerTex;
uniform highp int inWidth;
uniform highp int inHeight;
// Detection threshold in standard deviations. Higher = fewer hot-pixel
// hits, less correction. 4-6 is the usable range.
uniform highp float hotPixelThresholdK;

layout(location = 0) out highp float fragColor;

void main() {
    highp ivec2 pos = ivec2(gl_FragCoord.xy);
    if (pos.x < 2 || pos.y < 2 || pos.x >= inWidth - 2 || pos.y >= inHeight - 2) {
        fragColor = texelFetch(bayerTex, pos, 0).r;
        return;
    }

    highp float c  = texelFetch(bayerTex, pos,                 0).r;
    highp float n0 = texelFetch(bayerTex, pos + ivec2(-2,  0), 0).r;
    highp float n1 = texelFetch(bayerTex, pos + ivec2( 2,  0), 0).r;
    highp float n2 = texelFetch(bayerTex, pos + ivec2( 0, -2), 0).r;
    highp float n3 = texelFetch(bayerTex, pos + ivec2( 0,  2), 0).r;

    highp float mean = 0.25 * (n0 + n1 + n2 + n3);
    highp float d0 = n0 - mean;
    highp float d1 = n1 - mean;
    highp float d2 = n2 - mean;
    highp float d3 = n3 - mean;
    highp float variance = 0.25 * (d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3);
    highp float std = sqrt(variance) + 1e-6;

    if (abs(c - mean) > hotPixelThresholdK * std) {
        // Median of 4 same-colour neighbours = average of the two middle values.
        highp float a = min(n0, n1);
        highp float b = max(n0, n1);
        highp float p = min(n2, n3);
        highp float q = max(n2, n3);
        highp float midLow  = max(a, p);
        highp float midHigh = min(b, q);
        fragColor = 0.5 * (midLow + midHigh);
    } else {
        fragColor = c;
    }
}
