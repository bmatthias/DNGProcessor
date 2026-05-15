#version 300 es

#define PI 3.1415926535897932384626433832795f
#define hPI 1.57079632679489661923f
#define qPI 0.785398163397448309616f

precision mediump float;

uniform sampler2D inBuffer;
uniform ivec2 inBufferSize;
uniform sampler2D noiseTex;

out vec4 result;

vec3[9] load3x3(ivec2 xy, int n, sampler2D buf) {
    vec3 outputArray[9];
    ivec2 xyPx;
    for (int i = 0; i < 9; i++) {
        xyPx = xy + n * ivec2((i % 3) - 1, (i / 3) - 1);
        xyPx = clamp(xyPx, ivec2(0), inBufferSize);
        // Decode HDR luminance: Y = z * w
        vec4 texData = texelFetch(buf, xyPx, 0);
        float invScale = max(texData.w, 0.001);
        outputArray[i] = vec3(texData.x, texData.y, texData.z / invScale);
    }
    return outputArray;
}

void main() {
    ivec2 xyPos = ivec2(gl_FragCoord.xy);

    vec3 noiseLevel = texelFetch(noiseTex, xyPos / 2, 0).xyz; // Sigma
    vec3 maxDiff = noiseLevel * 2.f;

    vec3[9] impatch = load3x3(xyPos, 2, inBuffer);
    vec3 mid = impatch[4];

    vec2 sum = mid.xy;
    int weight = 1;

    vec3 val, diff;
    for (int i = 0; i < 9; i++) {
        if (i != 4) {
            val = impatch[i];
            diff = abs(val - mid);
            if (diff.x < maxDiff.x && diff.y < maxDiff.y && diff.z < maxDiff.z) {
                sum += val.xy;
                weight += 1;
            }
        }
    }

    // Weight is never zero as mid is always included.
    float Y = mid.z;
    result.xy = sum / float(weight);
    // Only encode if Y > 1.0 to avoid quantization
    if (Y <= 1.0) {
        // Y already in [0,1] - no encoding needed, use alpha=1.0 as marker
        result.z = Y;
        result.w = 1.0;
    } else {
        // HDR value - encode with scaling
        float hdrScale = Y;
        result.z = Y / hdrScale;
        result.w = 1.0 / hdrScale;  // Store 1/scale so it won't be clamped
    }
}
