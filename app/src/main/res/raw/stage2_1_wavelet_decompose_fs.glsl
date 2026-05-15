#version 300 es

// Edge-Aware Wavelet Decompose
// Based on darktable's eaw_dn_decompose
// Extracts detail at current scale using 5x5 binomial filter with edge-aware weighting
precision highp float;

uniform sampler2D buf;
uniform ivec2 bufSize;

// Scale parameter (0, 1, 2) controls filter spacing
// mult = 1 << scale (1, 2, 4)
uniform int scale;

// Edge-awareness parameter (higher = more edge preservation)
uniform float edgeAwareness;

// Output coarse approximation (detail computed separately as input - coarse)
out vec4 result;

// 5x5 binomial filter coefficients (normalized to sum=1)
const float filterKernel[25] = float[](
    1.0/256.0,  4.0/256.0,  6.0/256.0,  4.0/256.0,  1.0/256.0,
    4.0/256.0, 16.0/256.0, 24.0/256.0, 16.0/256.0,  4.0/256.0,
    6.0/256.0, 24.0/256.0, 36.0/256.0, 24.0/256.0,  6.0/256.0,
    4.0/256.0, 16.0/256.0, 24.0/256.0, 16.0/256.0,  4.0/256.0,
    1.0/256.0,  4.0/256.0,  6.0/256.0,  4.0/256.0,  1.0/256.0
);

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

// Edge-aware weight based on color distance (darktable's dn_weight)
float edgeWeight(vec3 center, vec3 neighbor) {
    vec3 diff = neighbor - center;
    float dot = dot(diff, diff);
    
    // Exponential falloff based on color distance
    // Higher edge awareness = more weight on similar colors
    float var = 0.02;
    float off2 = 9.0; // (3 sigma)^2
    return exp2(max(0.0, dot * var * edgeAwareness - off2));
}

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    
    // Decode center pixel
    vec3 centerXYY = decodeHDRxyY(texelFetch(buf, xyCenter, 0));
    
    int mult = 1 << scale;  // Sampling interval (1, 2, 4)
    
    // Accumulate weighted sum for coarse approximation
    vec3 sum = vec3(0.0);
    float wgt = 0.0;
    int filterIdx = 0;
    
    // 5x5 filter with edge-aware weighting
    for (int jj = -2; jj <= 2; jj++) {
        for (int ii = -2; ii <= 2; ii++) {
            ivec2 offset = ivec2(ii * mult, jj * mult);
            ivec2 samplePos = xyCenter + offset;
            
            // Clamp to image boundaries
            samplePos = clamp(samplePos, ivec2(0), bufSize - 1);
            
            vec3 neighborXYY = decodeHDRxyY(texelFetch(buf, samplePos, 0));
            
            // Combine binomial filter with edge-aware weight
            float f = filterKernel[filterIdx++];
            float wp = edgeWeight(centerXYY, neighborXYY);
            float w = f * wp;
            
            sum += w * neighborXYY;
            wgt += w;
        }
    }
    
    // Normalize coarse approximation
    vec3 coarse = (wgt > 0.0001) ? (sum / wgt) : centerXYY;
    
    // Output coarse approximation
    // Detail will be computed as: detail = input - coarse
    result = encodeHDRxyY(coarse);
}
