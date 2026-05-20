#version 300 es

// Per-pixel STRUCTURE TENSOR of a Bayer reference frame, used by
// burst_sr_upsample.glsl to adapt the SR Gaussian kernel to local image
// orientation (Wronski et al., "Handheld Multi-Frame Super-Resolution", 2019).
//
//   T(x,y) = box_3x3 of  [ Ix²   IxIy ]
//                        [ IxIy  Iy²  ]
//
// Per-pixel gradients (Ix, Iy) are taken as central differences over a
// same-channel ±2-pixel span — this works on a raw Bayer mosaic because
// (sx±2, sy) and (sx, sy±2) are guaranteed to be the SAME CFA colour as
// (sx, sy), so the difference reflects luminance change of that channel
// rather than CFA pattern. The result is a stable, demosaic-free gradient
// estimate at source resolution.
//
// Output (RGBA16F):
//   .r = mean Ix²   (Ixx)
//   .g = mean Iy²   (Iyy)
//   .b = mean Ix·Iy (Ixy)
//   .a = 1.0        (unused, for RGBA16F renderability)
//
// See burst_sr_upsample.glsl for the derivation of the local kernel
// covariance Ω from this tensor.

// Precision — explicit highp required by GLES 3.0 §4.5.3 for stable accumulation.
precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D refFrame;
uniform highp int inWidth;
uniform highp int inHeight;

layout(location = 0) out highp vec4 fragColor;

highp float fetchClamped(int x, int y) {
    x = clamp(x, 0, inWidth - 1);
    y = clamp(y, 0, inHeight - 1);
    return texelFetch(refFrame, ivec2(x, y), 0).r;
}

void main() {
    highp ivec2 pos = ivec2(gl_FragCoord.xy);

    highp float sxx = 0.0;
    highp float syy = 0.0;
    highp float sxy = 0.0;

    // 3×3 box average of per-pixel outer-product gradients.
    // 9 inner samples × 4 same-channel fetches each = 36 fetches/pixel.
    // Runs ONCE per burst on the reference frame, not per-frame.
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            int sx = pos.x + dx;
            int sy = pos.y + dy;
            // Same-channel central difference, span ±2 source pixels.
            // Divisor 4.0 = spacing × 2 (central diff: (f(+2) - f(-2)) / (2 * 2)).
            highp float ix = (fetchClamped(sx + 2, sy) - fetchClamped(sx - 2, sy)) * 0.25;
            highp float iy = (fetchClamped(sx, sy + 2) - fetchClamped(sx, sy - 2)) * 0.25;
            sxx += ix * ix;
            syy += iy * iy;
            sxy += ix * iy;
        }
    }

    sxx /= 9.0;
    syy /= 9.0;
    sxy /= 9.0;

    fragColor = vec4(sxx, syy, sxy, 1.0);
}
