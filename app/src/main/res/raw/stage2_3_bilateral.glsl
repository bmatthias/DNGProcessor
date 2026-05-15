#version 300 es

// Bilateral filter
precision mediump float;

// Use buf to blur luma while keeping chroma.
uniform sampler2D buf;
uniform ivec2 bufSize;

uniform vec2 sigma;
uniform ivec2 radius;

// Out (must be vec4 because RGB16F is not color-renderable in GLES 3.0)
out vec4 result;

#include gaussian

// Difference
float fr(float diffi) {
    return unscaledGaussian(diffi, sigma.x);
}

// Distance
float gs(float diffx) {
    //return 1.f / (diffx * diffx + 1.f);
    return unscaledGaussian(diffx, sigma.y);
}

vec3 xyYtoXYZ(vec3 xyY) {
    vec3 result = vec3(0.f, xyY.z, 0.f);
    if (xyY.y > 0.f) {
        result.x = xyY.x * xyY.z / xyY.y;
        result.z = (1.f - xyY.x - xyY.y) * xyY.z / xyY.y;
    }
    return result;
}

float pixDiff(vec3 pix1, vec3 pix2) {
    return distance(xyYtoXYZ(pix1), xyYtoXYZ(pix2));
}

/*
float pixDiff(vec3 pix1, vec3 pix2, float noise) {
    // pix1 is input/output pixel position.
    float z = 8.f * mix(pix1.z, min(pix1.z, pix2.z), 0.25f);
    z *= max(0.f, 1.f - 5.f * noise);
    return length((pix2 - pix1) * vec3(z, z, 1.f));
}
*/

// Helper to decode HDR xyY (alpha = 1/scale)
vec3 decodeHDRxyY(vec4 encoded) {
    float invScale = max(encoded.w, 0.0001);
    return vec3(encoded.x, encoded.y, encoded.z / invScale);
}

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);

    // Decode HDR luminance
    vec3 XYZCenter = decodeHDRxyY(texelFetch(buf, xyCenter, 0));

    ivec2 minxy = max(ivec2(0, 0), xyCenter - radius.x);
    ivec2 maxxy = min(bufSize - 1, xyCenter + radius.x);

    vec3 I = vec3(0.f);
    float W = 0.f;

    for (int y = minxy.y; y <= maxxy.y; y += radius.y) {
        for (int x = minxy.x; x <= maxxy.x; x += radius.y) {
            ivec2 xyPixel = ivec2(x, y);

            // Decode HDR luminance for each pixel
            vec3 XYZPixel = decodeHDRxyY(texelFetch(buf, xyPixel, 0));

            vec2 dxy = vec2(xyPixel - xyCenter);

            float scale = fr(pixDiff(XYZCenter, XYZPixel)) * gs(length(dxy));
            I += XYZPixel * scale;
            W += scale;
        }
    }

    vec3 blurred;
    if (W < 0.0001f) {
        blurred = XYZCenter;
    } else {
        blurred = I / W;
    }
    
    // Re-encode HDR luminance
    float Y = blurred.z;
    float hdrScale = max(Y, 1.0);
    result = vec4(blurred.x, blurred.y, Y / hdrScale, 1.0 / hdrScale);
}
