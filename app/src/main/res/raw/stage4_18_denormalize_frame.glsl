#version 300 es

precision mediump float;

uniform sampler2D normalizedFrame;
uniform float scale;  // Scale factor for log space denormalization

out float result;

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    float normalized = texelFetch(normalizedFrame, xyCenter, 0).x;
    
    // Convert from log space: exp(normalized * log(scale + epsilon)) - epsilon
    // This restores the original HDR dynamic range from log-compressed space
    float epsilon = 0.001;
    float logScale = log(scale + epsilon);
    float logValue = normalized * logScale;
    
    // Convert back from log space
    result = exp(logValue) - epsilon;
    
    // Ensure result is non-negative (should be, but be safe)
    result = max(result, 0.0);
}
