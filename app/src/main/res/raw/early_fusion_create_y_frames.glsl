#version 300 es

precision highp float;

// xyY input texture (from LinearRawEdgeMirror)
uniform sampler2D intermediate;

// Baseline exposure multiplier (2^EV)
uniform float baselineExposure;

// Frame type: 0 = under, 1 = center, 2 = over
uniform int frameType;

out float result;

// =============================================================================
// Extract Y from xyY and apply tone mapping for exposure fusion
//
// This shader:
// 1. Reads xyY (with HDR encoding)
// 2. Decodes Y (luminance)
// 3. Applies tone mapping to Y based on frame type
// 4. Outputs single-channel tone-mapped Y
// =============================================================================

// Numerically stable tone mapping functions
float exponentialTonemap(float Y, float exposure) {
    return 1.0 - exp(-Y * exposure); // NO DIVISION AT ALL
}

float reinhardTonemap(float Y, float exposure) {
    float x = Y * exposure;
    return x / (1.0 + x); // Denominator >= 1.0, always safe
}

void main() {
    ivec2 xyPos = ivec2(gl_FragCoord.xy);
    
    // Read xyY (with HDR encoding)
    vec4 xyYEnc = texelFetch(intermediate, xyPos, 0);
    
    // Decode Y from HDR encoding
    float invScale = max(xyYEnc.w, 0.001);
    float Y = xyYEnc.z / invScale;
    
    // Handle negative values
    Y = max(Y, 0.0);
    
    // Apply tone mapping based on frame type
    float toneMapped;
    
    if (frameType == 0) {
        // Under: Use original Y (no tone mapping) to preserve highlights
        // Just normalize to [0, 1] range
        toneMapped = min(Y / 10.0, 1.0);
    } else if (frameType == 1) {
        // Center: Reinhard (balanced midtones)
        toneMapped = reinhardTonemap(Y, baselineExposure);
    } else {
        // Over: Exponential with 2x exposure (reveals shadows)
        toneMapped = exponentialTonemap(Y, baselineExposure * 2.0);
    }
    
    result = clamp(toneMapped, 0.0, 1.0);
}
