#version 300 es

// Per-bin Wiener merge with sub-pixel Fourier shift.
//
// Second half of the port of `merge_frequency_domain` from hdr-plus-swift/
// burstphoto/merge/frequency.metal:13. The 49-shift sub-pixel best-shift
// is precomputed per tile by burst_freq_merge_best_shift.glsl and read
// from `bestShiftTex`. This pass writes the accumulator update.
//
// Output texture is the running FT accumulator (`final_texture_ft` in metal).
// Each fragment computes either the Re or Im of one (m, n) bin and ADDS
// the merged contribution to the previously-accumulated value:
//   accum += (1 - w) · aligned_shifted + w · ref

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D refFtTex;
uniform highp sampler2D alignedFtTex;
uniform highp sampler2D accumFtTex;       // previous accumulator value (read-modify-write)
uniform highp sampler2D rmsTex;           // (n_tiles_x, n_tiles_y) RGBA
uniform highp sampler2D mismatchTex;      // (n_tiles_x, n_tiles_y) R (normalised, 5×5-pooled)
uniform highp sampler2D highlightsNormTex;// (n_tiles_x, n_tiles_y) R
uniform highp sampler2D bestShiftTex;     // (n_tiles_x, n_tiles_y) RG
// Wronski Algorithm 6 (compute_s) flow-discontinuity penalty — per merge
// tile, S = 1.0 on clean flow and S = S_LOW on detected motion boundaries.
// Applied to the scalar Wiener weight to push the merge toward the
// reference at motion boundaries before the per-pixel residual is seen.
uniform highp sampler2D flowSTex;         // (n_tiles_x, n_tiles_y) R, value in [S_LOW, 1.0]

// Wronski / PhotonCamera adaptive per-burst noise model. When
// {@code useAdaptiveNoise == 1} the per-tile noise floor is taken from a
// linear fit of (mean, variance) over the per-tile RMS map (computed
// once per burst on the reference) instead of the camera spec-sheet read
// noise. Coefficients are per CFA channel:
//     σ²(tile) = noiseModelA + noiseModelB · tileMean
uniform highp sampler2D tileMeanTex;      // (n_tiles_x, n_tiles_y) RGBA
uniform highp vec4 noiseModelA;
uniform highp vec4 noiseModelB;
uniform highp int useAdaptiveNoise;

uniform highp float robustnessNorm;
uniform highp float readNoise;
uniform highp float maxMotionNorm;
uniform highp int tileSize;
uniform highp int uniformExposure;        // 1 = enable magnitude_norm

const highp float PI = 3.14159265358979323846;

layout(location = 0) out highp vec4 fragColor;

void main() {
    highp ivec2 outPos = ivec2(gl_FragCoord.xy);
    highp int m_global = outPos.x >> 1;
    highp int isIm     = outPos.x & 1;
    highp int n_global = outPos.y;

    highp int tileX   = m_global / tileSize;
    highp int dm      = m_global - tileX * tileSize;
    highp int tileY   = n_global / tileSize;
    highp int dn      = n_global - tileY * tileSize;
    highp ivec2 tile  = ivec2(tileX, tileY);

    highp int m2 = 2 * m_global;

    highp vec4 refRe = texelFetch(refFtTex, ivec2(m2,     n_global), 0);
    highp vec4 refIm = texelFetch(refFtTex, ivec2(m2 + 1, n_global), 0);
    highp vec4 alRe  = texelFetch(alignedFtTex, ivec2(m2,     n_global), 0);
    highp vec4 alIm  = texelFetch(alignedFtTex, ivec2(m2 + 1, n_global), 0);

    highp vec2 bestShift = texelFetch(bestShiftTex, tile, 0).rg;

    // Apply Fourier shift theorem.
    highp float angle = -2.0 * PI / float(tileSize);
    highp float a = angle * (float(dm) * bestShift.x + float(dn) * bestShift.y);
    highp float cRe = cos(a);
    highp float cIm = sin(a);
    highp vec4 alRe2 = cRe * alRe - cIm * alIm;
    highp vec4 alIm2 = cIm * alRe + cRe * alIm;

    // Noise norm = noise_floor · T² · robustnessNorm (per-channel RGBA). The
    // noise floor is either spec-sheet (rms + readNoise) or per-burst-fit
    // (linear in tile brightness) depending on useAdaptiveNoise.
    highp vec4 noiseEst;
    if (useAdaptiveNoise == 1) {
        // BurstAdaptiveNoise fits σ²(B) = A + B·brightness. The Wiener path
        // expects a linear σ estimate (metal: rms + read_noise), so take sqrt.
        highp vec4 brightness = texelFetch(tileMeanTex, tile, 0);
        noiseEst = sqrt(max(vec4(1e-10), noiseModelA + noiseModelB * brightness));
    } else {
        noiseEst = texelFetch(rmsTex, tile, 0) + vec4(readNoise);
    }
    highp vec4 noiseNorm = noiseEst * float(tileSize * tileSize) * robustnessNorm;

    highp float mismatch = texelFetch(mismatchTex, tile, 0).r;
    highp float mismatchWeight = clamp(1.0 - 10.0 * (mismatch - 0.2), 0.0, 1.0);
    highp float motionNorm = clamp(maxMotionNorm - (mismatch - 0.02) * (maxMotionNorm - 1.0) / 0.15,
                                   1.0, maxMotionNorm);
    highp float highlightsNorm = texelFetch(highlightsNormTex, tile, 0).r;

    // Delbracio sharpness reweighting (uniform-exposure only, non-DC bins, low mismatch).
    highp float magnitudeNorm = 1.0;
    if ((dm + dn) > 0 && mismatch < 0.3 && uniformExposure == 1) {
        highp vec4 refMag = sqrt(refRe * refRe + refIm * refIm);
        highp vec4 alMag  = sqrt(alRe2 * alRe2 + alIm2 * alIm2);
        highp float refSum = refMag.x + refMag.y + refMag.z + refMag.w;
        highp float alSum  = alMag.x  + alMag.y  + alMag.z  + alMag.w;
        highp float ratio = alSum / max(refSum, 1e-12);
        highp float ratio2 = ratio * ratio;
        magnitudeNorm = mismatchWeight * clamp(ratio2 * ratio2, 0.5, 3.0);
    }

    // Per-channel Wiener weight4 = D² / (D² + magnitude·motion·noise·highlights).
    highp vec4 diffRe = refRe - alRe2;
    highp vec4 diffIm = refIm - alIm2;
    highp vec4 d2 = diffRe * diffRe + diffIm * diffIm;
    highp vec4 denom = d2 + magnitudeNorm * motionNorm * noiseNorm * highlightsNorm;
    highp vec4 w4 = d2 / max(denom, vec4(1e-30));

    // Collapse to scalar via "mean of two central" (frequency.metal:163-166).
    highp float wmin = min(min(w4.x, w4.y), min(w4.z, w4.w));
    highp float wmax = max(max(w4.x, w4.y), max(w4.z, w4.w));
    highp float weight = clamp(0.5 * ((w4.x + w4.y + w4.z + w4.w) - wmin - wmax), 0.0, 1.0);

    // Wronski Algorithm 6: flow-discontinuity penalty. Multiplies the
    // alt-contribution fraction (= 1 - weight) by S ∈ [S_LOW, 1].
    //   S = 1  → weight unchanged
    //   S = S_LOW → weight pushed toward 1 (use reference)
    // The DC bin (dm = dn = 0) is exempt so per-tile means are not biased
    // by the per-tile penalty.
    if ((dm + dn) > 0) {
        highp float flowS = texelFetch(flowSTex, tile, 0).r;
        weight = 1.0 - flowS * (1.0 - weight);
    }

    highp vec4 prev = texelFetch(accumFtTex, outPos, 0);
    if (isIm == 0) {
        fragColor = prev + (1.0 - weight) * alRe2 + weight * refRe;
    } else {
        fragColor = prev + (1.0 - weight) * alIm2 + weight * refIm;
    }
}
