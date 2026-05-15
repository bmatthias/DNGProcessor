#version 300 es

precision mediump float;

// Original input texture (4-channel: chroma xy + luma Y + HDR scale)
uniform sampler2D originalInput;
uniform ivec2 maxxy;

out float result;

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    ivec2 xySource = min(xyCenter * 2, maxxy);
    
    // Extract luma from xyY format: Y = z / w
    vec4 originalEnc = texelFetch(originalInput, xySource, 0);
    float invScale = max(originalEnc.w, 0.001);
    float luma = originalEnc.z / invScale;
    
    result = luma;
}
