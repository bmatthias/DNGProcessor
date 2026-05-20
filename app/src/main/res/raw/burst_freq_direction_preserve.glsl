#version 300 es

// Direction-preserving diff magnitude shrinkage.
//
// Port of PhotonCamera's merge11.glsl:60-69
// (/Users/matthias/Code/PhotonCamera/app/src/main/assets/shaders/merge/merge11.glsl)
// adapted for the post-IFFT frequency-merge output.
//
// After the per-bin Wiener inside burst_freq_merge.glsl the per-CFA-channel
// magnitude of (merged − ref) has already been shrunk toward zero by the
// noise model. Two failure modes remain:
//   1. SIGN OVERSHOOT — one channel of (merged − ref) flips sign relative
//      to the original spatial diff (alt − ref). Example: ref blue,
//      alt cyan; after FFT round-trip one R-pixel ends up REDDER than ref
//      even though the original diff said it should have stayed blue-ward.
//   2. DIRECTION ROTATION — the (R,G,G,B) vector of (merged − ref) points
//      in a different colour-space direction than the original spatial
//      diff, producing chroma fringing along motion edges.
//
// Both are addressed in two steps (per the merge11.glsl formula):
//   (a) clamp(merged_diff, min(orig_diff, 0), max(orig_diff, 0))
//       — per channel, the merged diff is bounded between 0 and the
//         original diff. Eliminates sign overshoot.
//   (b) merged_diff = orig_diff / |orig_diff| * |clamped_merged_diff|
//       — re-rotate the diff to point in the original direction, with
//         magnitude equal to the clamped Wiener-shrunk magnitude.
//
// orig_diff is the MEAN signed (alt − ref) across all alternates, written
// by {@code burst_freq_accumulate_signed_diff.glsl} during the per-alt
// loop. This is an O(1) safety net on top of the per-bin Wiener and the
// "mean of two central" channel collapse — both of which already handle
// the common case. Compared to PhotonCamera's Laplacian-pyramid setup the
// FFT path has a much smaller residual to clean up, so the shader rarely
// fires at full strength on real content but acts as a hard cap on the
// failure modes above.
//
// IMPORTANT (OLA): this runs once per Hann overlap-add pass (×4). Using
// ref + redirected blindly can stack extra brightness across passes and
// break partition-of-unity. We therefore never output brighter than the
// Wiener IFFT result (min per channel); we only allow chroma/sign fixes
// that darken or hold luminance relative to {@code merged}.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D ifftTex;        // ifftOut: merged half-res RGBA
uniform highp sampler2D refRgbaTex;     // reference half-res RGBA
uniform highp sampler2D origDiffTex;    // accumulated mean signed (alt − ref)

layout(location = 0) out highp vec4 fragColor;

const highp float EPS = 1e-8;

void main() {
    highp ivec2 p = ivec2(gl_FragCoord.xy);
    highp vec4 ref      = texelFetch(refRgbaTex,  p, 0);
    highp vec4 merged   = texelFetch(ifftTex,     p, 0);
    highp vec4 origDiff = texelFetch(origDiffTex, p, 0);
    highp vec4 mergedDiff = merged - ref;

    // (a) Per-channel sign+magnitude clamp.
    highp vec4 lo = min(origDiff, vec4(0.0));
    highp vec4 hi = max(origDiff, vec4(0.0));
    highp vec4 clamped = clamp(mergedDiff, lo, hi);

    // (b) Direction preservation. Only act when the original diff has
    //     enough signal to define a stable direction — below EPS we fall
    //     back to the clamped diff to avoid noise amplification.
    highp float lenOrig    = length(origDiff);
    highp float lenClamped = length(clamped);
    highp vec4 redirected =
            (lenOrig > EPS)
            ? origDiff * (lenClamped / (lenOrig + EPS))
            : clamped;

    highp vec4 target = ref + redirected;
    // Per-channel min: never brighten vs Wiener IFFT (fixes 4-pass OLA blow-out).
    fragColor = min(merged, target);
}
