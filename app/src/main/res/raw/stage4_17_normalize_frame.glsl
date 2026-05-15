#version 300 es

precision mediump float;

uniform sampler2D inputFrame;
uniform float scale;  // Scale factor for log space normalization

out float result;

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    float value = texelFetch(inputFrame, xyCenter, 0).x;
    
    // Convert to log space: log(value + epsilon) / log(scale + epsilon)
    // This compresses HDR dynamic range, making Laplacian pyramids work better
    // Add small epsilon to avoid log(0) and ensure smooth transitions
    float epsilon = 0.001;
    float logValue = log(max(value, epsilon) + epsilon);
    float logScale = log(scale + epsilon);
    
    // Normalize to [0, 1] range in log space
    // This maps the HDR range to a manageable range for pyramid operations
    result = logValue / max(logScale, 0.001);
}
