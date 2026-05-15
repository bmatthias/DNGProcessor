#version 300 es

precision mediump float;

uniform sampler2D intermediate;

out vec4 result;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    
    // Extract luminance (Y channel) from xyY intermediate format
    // Decode HDR luminance: Y = z / alpha (alpha = 1/scale)
    // If alpha == 1.0, no encoding was applied
    vec4 encoded = texelFetch(intermediate, xy, 0);
    float luma;
    if (encoded.w >= 0.9999) {
        // No encoding was applied - Y is already correct
        luma = encoded.z;
    } else {
        // Decode: Y = encoded.z / encoded.w
        float invScale = max(encoded.w, 0.0001);
        luma = encoded.z / invScale;
    }
    
    // Output luminance in red channel
    result = vec4(luma, 0.0, 0.0, 1.0);
}

