#version 300 es

precision mediump float;

uniform sampler2D buf;
uniform ivec2 bufSize;

uniform float sigma;
uniform int radius;
uniform ivec2 dir;

// Output (must be vec4 because RGB16F is not color-renderable in GLES 3.0)
out vec4 result;

#include gaussian

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);

    vec3 I = vec3(0.f);  // Accumulated (x, y, Y) where Y is decoded HDR
    float W = 0.f;
    float gaussScale;

    for (int i = -radius; i <= radius; i++) {
        ivec2 xy = xyCenter + i * dir;
        if (xy.x >= 0 && xy.y >= 0 && xy.x < bufSize.x && xy.y < bufSize.y) {
            vec4 texData = texelFetch(buf, xyCenter + i * dir, 0);
            // Decode HDR luminance: Y = z * w (normalized * scale)
            float invScale = max(texData.w, 0.001);
            vec3 xyY = vec3(texData.x, texData.y, texData.z / invScale);
            gaussScale = unscaledGaussian(float(i), sigma);
            I += xyY * gaussScale;
            W += gaussScale;
        }
    }

    // Compute blurred values
    vec3 blurred = I / W;
    
    // Re-encode HDR luminance for output
    float Y = blurred.z;
    float hdrScale = max(Y, 1.0);
    result = vec4(blurred.x, blurred.y, Y / hdrScale, 1.0 / hdrScale);
}
