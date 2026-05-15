#version 300 es

precision mediump float;

uniform sampler2D gradientTexture;
uniform float compressionStrength;  // Controls how aggressively gradients are compressed (0.0-1.0)
uniform float baselineExposure;      // Exposure multiplier for scaling compression threshold

// Output: R=compressed horizontal, G=compressed vertical, B=original value, A=unused
out vec4 compressedGradient;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    vec4 grad = texelFetch(gradientTexture, xy, 0);
    float gradX = grad.r;
    float gradY = grad.g;
    float originalValue = grad.b;
    
    // Compute gradient magnitude
    float gradMag = sqrt(gradX * gradX + gradY * gradY);
    
    // Compression function: compress large gradients more than small ones
    // Using a modified Reinhard-like compression: compressed = grad / (threshold + grad)
    // Higher compressionStrength = more aggressive compression
    float threshold = 0.1 * (1.0 - compressionStrength) + 0.01 * compressionStrength;
    threshold *= baselineExposure;  // Scale threshold with exposure
    
    float compressedMag = gradMag / (threshold + gradMag);
    
    // Preserve direction, scale magnitude
    float scale = gradMag > 0.001 ? compressedMag / gradMag : 1.0;
    float compressedX = gradX * scale;
    float compressedY = gradY * scale;
    
    compressedGradient = vec4(compressedX, compressedY, originalValue, 1.0);
}

