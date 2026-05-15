#version 300 es

#define WEIGHTS vec3(vec2(96.f), 3.f)

precision mediump float;

uniform sampler2D intermediate;
uniform ivec2 bufSize;

uniform int radius;

// NoiseProfile parameters for signal-dependent noise estimation
// noise_variance = noiseModelA * signal + noiseModelB
uniform float noiseModelA;  // Shot noise coefficient (signal-dependent)
uniform float noiseModelB;  // Read noise coefficient (signal-independent)
uniform int useNoiseModel;  // 1 = use noise model, 0 = use gradient estimation

// Out (must be vec4 because RGB16F is not color-renderable in GLES 3.0)
out vec4 result;

#include load3x3v3

// Helper to decode HDR xyY (alpha = 1/scale)
// If alpha == 1.0, no encoding was applied
vec3 decodeHDRxyY(vec4 encoded) {
    if (encoded.w >= 0.9999) {
        // No encoding was applied - Y is already correct
        return vec3(encoded.x, encoded.y, encoded.z);
    }
    // Otherwise, decode: Y = encoded.z / encoded.w
    float invScale = max(encoded.w, 0.0001);
    return vec3(encoded.x, encoded.y, encoded.z / invScale);
}

// Compute noise standard deviation from NoiseProfile model
// noise_variance = a * signal + b, so noise_stddev = sqrt(a * signal + b)
// Values are already transformed from sensor space to xyY Y space
float computeNoiseStdDev(float signal) {
    // Clamp signal to valid range
    float clampedSignal = max(signal, 0.0);
    
    // Compute variance: var = a * signal + b
    float variance = noiseModelA * clampedSignal + noiseModelB;
    
    // Return standard deviation (sqrt of variance)
    // Values are in xyY Y space, so they match the signal units
    return sqrt(max(variance, 0.0));
}

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy) * 4;

    ivec2 minxy = xyCenter - 1;
    ivec2 maxxy = xyCenter + 1;

    ivec2 xyPixel;
    vec3[9] impatch;
    int i = 0;
    for (int y = minxy.y; y <= maxxy.y; y += 1) {
        for (int x = minxy.x; x <= maxxy.x; x += 1) {
            // Decode HDR luminance
            impatch[i++] = decodeHDRxyY(texelFetch(intermediate, clamp(ivec2(x, y), ivec2(0), bufSize - 1), 0));
        }
    }

    // Gradient-based noise estimation
    vec3 gradientHor = abs(impatch[4] - impatch[3]) + abs(impatch[4] - impatch[5]);
    vec3 gradientVert = abs(impatch[4] - impatch[1]) + abs(impatch[4] - impatch[7]);
    vec3 gradientNE = abs(impatch[4] - impatch[2]) + abs(impatch[4] - impatch[6]);
    vec3 gradientNW = abs(impatch[4] - impatch[0]) + abs(impatch[4] - impatch[8]);

    vec3 gradientMax = max(max(gradientHor, gradientVert), max(gradientNE, gradientNW));
    vec3 gradientMin = min(min(gradientHor, gradientVert), min(gradientNE, gradientNW));
    vec3 gradientNoise = WEIGHTS * max(3.f * gradientMin - gradientMax, 0.f);

    // Use noise model if available
    if (useNoiseModel == 1) {
        // Get the center pixel's signal level (luminance Y in xyY space)
        float signalLevel = impatch[4].z;  // Y component in xyY
        
        // Compute model-based noise estimate (stddev in xyY Y space)
        float modelNoiseStdDev = computeNoiseStdDev(signalLevel);
        
        // Convert noise stddev to match the gradient noise format
        // The gradient noise uses WEIGHTS, so we need to scale appropriately
        // The noise stddev is in xyY Y units. After proper transformation,
        // typical values are in range 0.001-0.01 for good sensors
        // Gradient noise values are typically 0.01-0.1, so we need scaling
        // Use a scale factor that brings model noise into similar range as gradient noise
        // This scale factor may need tuning based on actual results
        float noiseScale = 100.0;  // Scale factor to match gradient noise range
        vec3 modelNoise = vec3(modelNoiseStdDev * noiseScale) * WEIGHTS;
        
        // Combine model-based and gradient-based estimates
        // Model provides signal-dependent baseline noise
        // Gradient provides local texture/noise variation
        // Use model as baseline, add gradient for local variations
        // In smooth areas (low gradient), use mostly model
        // In textured areas (high gradient), add gradient component
        float gradientMagnitude = length(gradientMax);
        float modelWeight = 1.0 - clamp(gradientMagnitude * 3.0, 0.0, 0.7);  // 30-100% model
        
        // Combine: model provides baseline, gradient adds local variation
        vec3 combinedNoise = modelNoise * modelWeight + gradientNoise * (1.0 - modelWeight);
        
        result = vec4(combinedNoise, 1.0);
    } else {
        // Fall back to pure gradient-based estimation
        result = vec4(gradientNoise, 1.0);
    }
}
