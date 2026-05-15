#version 300 es

// Default values - can be overridden via uniforms for HDR
#define DEFAULT_TARGET_Z 0.6f
#define DEFAULT_GAUSS_Z 0.5f

precision mediump float;

uniform bool useUpscaled;
uniform sampler2D upscaled;

// Weighting is done using these.
uniform sampler2D gaussUnder;
uniform sampler2D gaussOver;

// Blending is done using these.
uniform sampler2D blendUnder;
uniform sampler2D blendOver;

uniform int level;

// HDR-tunable parameters
// targetZ: optimal luminance value (pixels closer to this get higher weight)
// gaussZ: width of the Gaussian weighting function (larger = broader blend region)
uniform float targetZ;  // Set to 0.0 to use default 0.6
uniform float gaussZ;   // Set to 0.0 to use default 0.5

out float result;

#include gaussian
#include sigmoid
#include gamma

// From hdr-plus repo, modified for configurable target.
float dist(float z, float target, float sigma) {
    return unscaledGaussian(z - target, sigma);
}

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);

    // Use configured values or defaults
    float effectiveTargetZ = (targetZ > 0.01) ? targetZ : DEFAULT_TARGET_Z;
    float effectiveGaussZ = (gaussZ > 0.01) ? gaussZ : DEFAULT_GAUSS_Z;

    // If this is the lowest layer, start with zero.
    float base = useUpscaled
        ? texelFetch(upscaled, xyCenter, 0).x
        : 0.f;

    // How are we going to blend these two?
    float blendUnderVal = texelFetch(blendUnder, xyCenter, 0).x;
    float blendOverVal = texelFetch(blendOver, xyCenter, 0).x;

    // Look at result to compute weights.
    float gaussUnderVal = texelFetch(gaussUnder, xyCenter, 0).x;
    float gaussOverVal = texelFetch(gaussOver, xyCenter, 0).x;

    float gaussUnderValDev = dist(gaussUnderVal, effectiveTargetZ, effectiveGaussZ);
    float gaussOverValDev = dist(gaussOverVal, effectiveTargetZ, effectiveGaussZ);

    float blend = gaussOverValDev / (gaussUnderValDev + gaussOverValDev); // [0, 1]
    float blendVal = mix(blendUnderVal, blendOverVal, blend);

    blendVal *= max(1.f, 1.5f - 0.05f * float(level));

    float res = base + blendVal;
    if (level == 0) {
        res = gammaDecode(res);
    }
    result = res;
}
