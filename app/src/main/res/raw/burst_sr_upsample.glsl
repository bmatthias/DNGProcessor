#version 300 es

// Wronski anisotropic-Gaussian 1× → 2× Bayer upsampler.
//
// Structure-tensor-driven anisotropic Gaussian kernel (Wronski et al. 2019),
// operating on a SINGLE input frame (the already-merged 1× Bayer from
// BurstFrequencyMerge) and producing a 2× output without any robustness /
// HDR / per-frame loop machinery.
//
// The structure tensor of the merged Bayer is computed once by
// `burst_structure_tensor.glsl` and passed in here.
//
// Output: 2× Bayer texture (1 channel R16F). Each output pixel is the
// CFA-aware Gaussian-weighted sum of same-channel taps in the merged 1×.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D mergedBayerTex;    // 1× Bayer
uniform highp sampler2D structureTensor;   // 1× RGBA (Ixx, Iyy, Ixy)
uniform highp int inWidth;
uniform highp int inHeight;
uniform highp float edgeStrengthGain;
uniform highp float maxAnisotropyRatio;
uniform highp float baseSigma;
uniform highp int useAnisotropicKernel;

layout(location = 0) out highp float fragColor;

// Structure tensor is stored per Bayer site (same-channel gradients only).
// Bilinear fetch MUST stay on the same CFA lattice as the output pixel — mixing
// tensors from R/G/B neighbours gives different blur axes per channel and
// produces red/cyan fringing on vertical edges after 2× SR.
highp vec3 sampleTensorSameCfa(highp int px, highp int py,
                               highp float src_x, highp float src_y) {
    highp vec2 ch = vec2(src_x - float(px), src_y - float(py)) * 0.5;
    highp ivec2 i0 = ivec2(floor(ch)) * 2 + ivec2(px, py);
    highp vec2 f = ch - floor(ch);
    highp ivec2 i1 = min(i0 + ivec2(2), ivec2(inWidth - 1, inHeight - 1));
    i0 = clamp(i0, ivec2(0), ivec2(inWidth - 1, inHeight - 1));
    highp vec3 t00 = texelFetch(structureTensor, i0,              0).rgb;
    highp vec3 t10 = texelFetch(structureTensor, ivec2(i1.x, i0.y), 0).rgb;
    highp vec3 t01 = texelFetch(structureTensor, ivec2(i0.x, i1.y), 0).rgb;
    highp vec3 t11 = texelFetch(structureTensor, i1,              0).rgb;
    return mix(mix(t00, t10, f.x), mix(t01, t11, f.x), f.y);
}

highp vec3 deriveOmega(highp vec3 T) {
    highp float invSigma2 = 1.0 / (baseSigma * baseSigma);
    if (useAnisotropicKernel == 0) {
        return vec3(invSigma2, 0.0, invSigma2);
    }
    highp float Ixx = T.x, Iyy = T.y, Ixy = T.z;
    highp float halfTrace = (Ixx + Iyy) * 0.5;
    highp float disc = max(((Ixx - Iyy) * 0.5) * ((Ixx - Iyy) * 0.5) + Ixy * Ixy, 0.0);
    highp float root = sqrt(disc);
    highp float lambda1 = halfTrace + root;
    highp float lambda2 = halfTrace - root;
    highp float coherence = (lambda1 - lambda2) / (lambda1 + lambda2 + 1e-8);
    highp float strength = 1.0 - exp(-lambda1 * edgeStrengthGain);
    highp float anisotropy = mix(1.0, maxAnisotropyRatio, clamp(strength * coherence, 0.0, 1.0));

    highp vec2 e1;
    if (abs(Ixy) > 1e-8) {
        e1 = normalize(vec2(Ixy, lambda1 - Ixx));
    } else {
        e1 = (Ixx >= Iyy) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
    }
    highp float sigmaAcross = baseSigma;
    highp float sigmaAlong = baseSigma * anisotropy;
    highp float invAc2 = 1.0 / (sigmaAcross * sigmaAcross);
    highp float invAl2 = 1.0 / (sigmaAlong * sigmaAlong);
    highp float a = invAc2 * e1.x * e1.x + invAl2 * e1.y * e1.y;
    highp float c = invAc2 * e1.y * e1.y + invAl2 * e1.x * e1.x;
    highp float b = (invAc2 - invAl2) * e1.x * e1.y;
    return vec3(a, b, c);
}

void main() {
    highp ivec2 outPos = ivec2(gl_FragCoord.xy);
    highp int px = outPos.x & 1;
    highp int py = outPos.y & 1;

    // 2× output → source coord at half-pixel offsets.
    highp float src_x = (float(outPos.x) + 0.5) * 0.5;
    highp float src_y = (float(outPos.y) + 0.5) * 0.5;
    highp int cx = int(floor(src_x));
    highp int cy = int(floor(src_y));
    highp float lrX = src_x - float(cx) - 0.5;
    highp float lrY = src_y - float(cy) - 0.5;

    highp vec3 T = (useAnisotropicKernel != 0)
            ? sampleTensorSameCfa(px, py, src_x, src_y)
            : vec3(0.0);
    highp vec3 Omega = deriveOmega(T);
    highp float a = Omega.x, b = Omega.y, c = Omega.z;

    highp float val = 0.0;
    highp float wSum = 0.0;
    for (highp int dy = -2; dy <= 2; dy++) {
        for (highp int dx = -2; dx <= 2; dx++) {
            highp int sx = cx + dx;
            highp int sy = cy + dy;
            if (sx < 0 || sy < 0 || sx >= inWidth || sy >= inHeight) continue;
            if ((sx & 1) != px || (sy & 1) != py) continue;
            highp float distX = float(dx) - lrX;
            highp float distY = float(dy) - lrY;
            highp float quad = a * distX * distX + 2.0 * b * distX * distY + c * distY * distY;
            highp float w = exp(-0.5 * quad);
            val += w * texelFetch(mergedBayerTex, ivec2(sx, sy), 0).r;
            wSum += w;
        }
    }
    fragColor = (wSum > 1e-8) ? (val / wSum) : 0.0;
}
