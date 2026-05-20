#version 300 es

// Explicit highp required by GLES 3.0 §4.5.3 for stable accumulation.
// Without `precision highp int;` and `precision highp sampler2D;` the int conversion
// chain (float→int via floor) silently falls back to mediump/fp16 on many drivers,
// which corrupts source coordinates once outPos exceeds ~1024.
precision highp float;
precision highp int;
precision highp sampler2D;

// Merge mode (must match BurstSrUpsample.MERGE_SR_EQUAL/MERGE_HDR_BRACKET constants).
const int MERGE_SR_EQUAL    = 0;
const int MERGE_HDR_BRACKET = 1;

// Normalised linear-RGB (Float16, RGBA) sensor texture for the current frame
uniform highp sampler2D frameBuffer;

// Running accumulation (.rgb = weighted_sum, .a = weight_sum)
uniform highp sampler2D accumBuffer;

// Median shift for this frame (in 1× source pixels, already negated)
uniform highp vec2 shift;

// Merge mode: 0 = SR_EQUAL, 1 = HDR_BRACKET (see constants above).
uniform highp int mergeMode;

// Exposure time in seconds (kept for legacy logging only).
uniform highp float exposureTime;

// Per-frame relative EV vs the reference (darkest) frame. 0 for ref.
uniform highp float frameRelativeEv;

// Per-frame exposure-equalisation multiplier (= 1 / 2^frameRelativeEv).
uniform highp float frameScale;

// Per-frame highlight-clip threshold in the FRAME's own EV.
uniform highp float highlightClipThreshold;

// 1× source frame dimensions (output texture is 2×)
uniform highp int inWidth;
uniform highp int inHeight;

layout(location = 0) out highp vec4 fragColor;

// Lanczos2 kernel: sinc(x) * sinc(x/2), support |x| < 2 source pixels.
highp float lanczos2(highp float x) {
    if (abs(x) < 1e-5) return 1.0;
    if (abs(x) >= 2.0)  return 0.0;
    highp float px = 3.14159265 * x;
    return (sin(px) / px) * (sin(px * 0.5) / (px * 0.5));
}

// hdr-plus-swift `add_texture_exposure` radiometric weight, RGB version.
// `frameLum` is BEFORE frameScale (the frame's own value), `refLum` is in ref EV.
highp float hdrBracketWeight(highp float frameLum, highp float refLum) {
    highp float wExposure = exp2(frameRelativeEv);
    highp float refClamp = clamp(refLum, 0.0, 1.0);
    highp float shadowMask = 1.0 - smoothstep(0.0, 0.25, refClamp);
    highp float wLum = mix(sqrt(wExposure), wExposure, shadowMask);

    highp float clipLo = highlightClipThreshold * 0.25;
    highp float clipHi = max(highlightClipThreshold, clipLo + 1e-3);
    highp float wHigh  = 1.0 - smoothstep(clipLo, clipHi, frameLum);
    return max(wLum * wHigh, 1e-4);
}

void main() {
    highp ivec2 outPos = ivec2(gl_FragCoord.xy);
    highp vec4 accum = texelFetch(accumBuffer, outPos, 0);

    // 2× SR: output pixel centre → 1× source coordinate, subtract shift.
    highp vec2 srcF = (vec2(outPos) + 0.5) * 0.5 - shift;

    if (srcF.x < -2.0 || srcF.y < -2.0 ||
        srcF.x >= float(inWidth)  + 2.0 ||
        srcF.y >= float(inHeight) + 2.0) {
        fragColor = accum;
        return;
    }

    highp ivec2 p0 = ivec2(floor(srcF));
    highp vec2  fx = srcF - vec2(p0);

    // 4×4 Lanczos2 — no CFA constraint for linear-RGB.
    highp vec3  valRaw = vec3(0.0);  // in this frame's own EV
    highp float wSum   = 0.0;
    for (highp int j = 0; j < 4; j++) {
        highp float wy = lanczos2(fx.y - float(j - 1));
        highp int sy = clamp(p0.y + j - 1, 0, inHeight - 1);
        for (highp int i = 0; i < 4; i++) {
            highp float wx = lanczos2(fx.x - float(i - 1));
            highp int sx = clamp(p0.x + i - 1, 0, inWidth - 1);
            highp float w = wx * wy;
            valRaw += w * texelFetch(frameBuffer, ivec2(sx, sy), 0).rgb;
            wSum   += w;
        }
    }

    if (wSum < 1e-6) {
        fragColor = accum;
        return;
    }
    highp vec3 frameRgb = valRaw / wSum;            // frame EV
    highp vec3 frameEq  = frameRgb * frameScale;    // ref EV

    highp float frameLuma = 0.2126 * frameRgb.r + 0.7152 * frameRgb.g + 0.0722 * frameRgb.b;
    highp float refLuma   = 0.2126 * frameEq.r  + 0.7152 * frameEq.g  + 0.0722 * frameEq.b;

    highp float weight;
    if (mergeMode == MERGE_HDR_BRACKET) {
        weight = hdrBracketWeight(frameLuma, refLuma);
    } else {
        // SR_EQUAL: legacy well-exposedness weighting (Mertens-style on luma)
        // gives slightly nicer night-mode merges than pure equal weights.
        highp float exposedness = exp(-12.5 * (refLuma - 0.5) * (refLuma - 0.5));
        weight = max(exposedness, 1e-4);
    }

    fragColor = accum + vec4(frameEq * weight, weight);
}
