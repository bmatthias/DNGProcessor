#version 300 es

precision highp float;

// Chroma from original xyY input
uniform sampler2D bufChroma;

// Merged luma from exposure fusion
uniform sampler2D bufLuma;

out vec4 result;

void main() {
    ivec2 xyPos = ivec2(gl_FragCoord.xy);

    // Recombine chroma from original with merged luma
    result.xy = texelFetch(bufChroma, xyPos, 0).xy;
    
    // Get merged luma
    float Y = texelFetch(bufLuma, xyPos, 0).x;
    
    // Store in xyY format
    result.z = Y;
    result.w = 1.0;
}
