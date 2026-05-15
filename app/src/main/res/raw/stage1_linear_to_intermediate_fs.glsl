#version 300 es

precision mediump float;

uniform sampler2D rgbBuffer;  // Normalized RGB input (can be HDR after baseline exposure)
uniform int rawWidth;
uniform int rawHeight;

uniform sampler2D gainMap;

uniform vec3 neutralPoint;
uniform mat3 sensorToXYZ;

// Out - intermediate format (xyY)
// Note: must be vec4 because RGB16F is not color-renderable in GLES 3.0
out vec4 intermediate;

// Convert XYZ to xyY with gamut protection
// Color matrices can produce negative XYZ for saturated/clipped colors
vec3 XYZtoxyY(vec3 XYZ) {
    // Gamut clipping: ensure XYZ values are non-negative
    // This handles out-of-gamut colors from the color matrix
    XYZ = max(XYZ, vec3(0.0));
    
    float sum = XYZ.x + XYZ.y + XYZ.z;
    if (sum <= 0.0001) {
        // Very dark pixel - use D65 white point chromaticity
        return vec3(0.3127, 0.3290, 0.0);
    }
    
    // Chromaticity coordinates
    float x = XYZ.x / sum;
    float y = XYZ.y / sum;
    
    // Luminance is Y (always non-negative after clamp)
    float Y = XYZ.y;
    
    return vec3(x, y, Y);
}

// Maximum HDR value - must match stage1
const float HDR_MAX = 1024.0;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);

    // Read HDR-encoded RGB: RGB normalized to [0,1], alpha = 1/scale
    vec4 encoded = texelFetch(rgbBuffer, xy, 0);
    
    // Decode HDR: divide RGB by alpha to restore original values
    // Use 1/HDR_MAX as floor (not 0.001 which is too high for HDR_MAX=1024)
    float invScale = max(encoded.a, 1.0 / HDR_MAX);
    vec3 rgb = encoded.rgb / invScale;
    
    // Check for Inf/NaN after decoding - treat as max brightness white
    if (isinf(rgb.r) || isinf(rgb.g) || isinf(rgb.b) ||
        isnan(rgb.r) || isnan(rgb.g) || isnan(rgb.b)) {
        // Output white at max brightness with D65 chromaticity
        intermediate = vec4(0.3127, 0.3290, 1.0, 1.0 / HDR_MAX);
        return;
    }

    // Apply white balance
    vec3 safeNeutral = max(neutralPoint, vec3(0.001));
    rgb /= safeNeutral;
    
    // Check for Inf/NaN after white balance
    if (isinf(rgb.r) || isinf(rgb.g) || isinf(rgb.b) ||
        isnan(rgb.r) || isnan(rgb.g) || isnan(rgb.b)) {
        intermediate = vec4(0.3127, 0.3290, 1.0, 1.0 / HDR_MAX);
        return;
    }

    // Convert to XYZ then xyY
    vec3 XYZ = sensorToXYZ * rgb;
    
    // Check for Inf/NaN after matrix
    if (isinf(XYZ.x) || isinf(XYZ.y) || isinf(XYZ.z) ||
        isnan(XYZ.x) || isnan(XYZ.y) || isnan(XYZ.z)) {
        intermediate = vec4(0.3127, 0.3290, 1.0, 1.0 / HDR_MAX);
        return;
    }
    
    vec3 result = XYZtoxyY(XYZ);
    
    // Handle NaN in chromaticity
    if (isnan(result.x)) result.x = 0.3127;
    if (isnan(result.y)) result.y = 0.3290;
    if (isnan(result.z)) result.z = 0.0;
    
    // HDR encode Y for output
    float Y = max(result.z, 0.0);
    
    // If Y is Inf or NaN, encode as maximum brightness
    if (isinf(Y) || isnan(Y)) {
        intermediate = vec4(result.x, result.y, 1.0, 1.0 / HDR_MAX);
        return;
    }
    
    // Only encode if Y > 1.0 to avoid quantization when already in [0,1]
    if (Y <= 1.0) {
        // Y already in [0,1] - no encoding needed, use alpha=1.0 as marker
        intermediate = vec4(result.x, result.y, Y, 1.0);
    } else {
        // HDR value - encode with scaling
        float scaleFactor = Y;
        float encodedY = Y / scaleFactor;
        float encodedAlpha = 1.0 / scaleFactor;
        intermediate = vec4(result.x, result.y, encodedY, encodedAlpha);
    }
}

