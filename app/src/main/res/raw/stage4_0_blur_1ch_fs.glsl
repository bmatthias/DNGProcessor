#version 300 es

precision mediump float;

uniform sampler2D buf;
uniform ivec2 bufSize;

uniform float sigma;
uniform int radius;
uniform ivec2 dir;

out float result;

#include gaussian

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);

    float I = 0.f;
    float W = 0.f;
    float Y, scale;

    for (int i = -radius; i <= radius; i++) {
        ivec2 xy = xyCenter + i * dir;
        if (xy.x >= 0 && xy.y >= 0 && xy.x < bufSize.x && xy.y < bufSize.y) {
            Y = texelFetch(buf, xyCenter + i * dir, 0).x;
            scale = unscaledGaussian(float(i), sigma);
            I += Y * scale;
            W += scale;
        }
    }

    // Gaussian blur is REQUIRED for proper Laplacian pyramid operations
    // Without blur, aliasing artifacts cause banding in the final image
    // The Nyquist theorem requires low-pass filtering before downsampling
    result = I / W;
}
