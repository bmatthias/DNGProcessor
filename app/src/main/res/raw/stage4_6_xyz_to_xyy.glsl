#version 300 es

precision mediump float;

uniform sampler2D buf;

out vec4 result;

#include xyztoxyy

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    vec3 xyY = XYZtoxyY(texelFetch(buf, xyCenter, 0).xyz);
    
    // Encode HDR luminance - only if Y > 1.0 to avoid quantization
    float Y = xyY.z;
    if (Y <= 1.0) {
        // Y already in [0,1] - no encoding needed, use alpha=1.0 as marker
        result = vec4(xyY.x, xyY.y, Y, 1.0);
    } else {
        // HDR value - encode with scaling
        float hdrScale = Y;
        result = vec4(xyY.x, xyY.y, Y / hdrScale, 1.0 / hdrScale);
    }
}
