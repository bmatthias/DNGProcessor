#version 300 es

// Edge-Aware Wavelet Synthesize
// Based on darktable's eaw_synthesize and accumulate
// Applies soft thresholding to detail and accumulates into output
precision highp float;

uniform sampler2D bufAccum;   // Accumulated output so far
uniform sampler2D bufDetail;  // Detail at current scale
uniform ivec2 bufSize;

// Threshold for soft thresholding (higher = more denoising)
uniform vec3 threshold;

// Boost factor for detail (>1 = sharpen, <1 = soften, =1 = neutral)
uniform vec3 boost;

out vec4 result;

// Helper to decode HDR xyY
vec3 decodeHDRxyY(vec4 encoded) {
    float invScale = max(encoded.w, 0.0001);
    return vec3(encoded.x, encoded.y, encoded.z / invScale);
}

// Helper to encode HDR xyY
vec4 encodeHDRxyY(vec3 xyY) {
    float Y = xyY.z;
    float hdrScale = max(Y, 1.0);
    return vec4(xyY.x, xyY.y, Y / hdrScale, 1.0 / hdrScale);
}

// Soft thresholding (darktable's accumulate function)
// Reduces magnitude of detail by threshold while preserving sign
// amount = MAX(detail - thresh, 0) + MIN(detail + thresh, 0)
vec3 softThreshold(vec3 detail, vec3 thresh) {
    // This is equivalent to: sign(detail) * max(abs(detail) - thresh, 0.0)
    // But vectorizes better
    vec3 sum = detail + thresh;
    vec3 diff = detail - thresh;
    
    sum = min(sum, vec3(0.0));
    diff = max(diff, vec3(0.0));
    
    return sum + diff;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    
    // Fetch accumulated output and current detail
    vec3 accum = decodeHDRxyY(texelFetch(bufAccum, xy, 0));
    vec3 detail = decodeHDRxyY(texelFetch(bufDetail, xy, 0));
    
    // Apply soft thresholding to detail
    // This reduces noise while preserving edges
    vec3 thresholded = softThreshold(detail, threshold);
    
    // Add thresholded detail to accumulator with boost
    // boost > 1 sharpens, boost < 1 softens, boost = 1 is neutral
    accum += boost * thresholded;
    
    // Output updated accumulator
    result = encodeHDRxyY(accum);
}
