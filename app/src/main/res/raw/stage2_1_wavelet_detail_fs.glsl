#version 300 es

// Compute wavelet detail as difference between input and coarse
precision highp float;

uniform sampler2D bufInput;
uniform sampler2D bufCoarse;
uniform ivec2 bufSize;

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

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    
    vec3 inputXYY = decodeHDRxyY(texelFetch(bufInput, xy, 0));
    vec3 coarse = decodeHDRxyY(texelFetch(bufCoarse, xy, 0));
    
    // Detail is the high-frequency component
    vec3 detail = inputXYY - coarse;
    
    result = encodeHDRxyY(detail);
}
