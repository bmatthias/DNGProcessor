#version 300 es

precision mediump float;

uniform sampler2D compressedGradientTexture;
uniform sampler2D previousIteration;  // For iterative Jacobi solver
uniform ivec2 textureSize;
uniform float alpha;  // Jacobi iteration parameter (typically 0.25 for 2D)
uniform int iteration;  // Current iteration number (0 = use original as initial guess)

// Output: Reconstructed RGB
out vec4 reconstructed;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    
    // Sample compressed gradients
    vec4 grad = texelFetch(compressedGradientTexture, xy, 0);
    float gradX = grad.r;
    float gradY = grad.g;
    float originalValue = grad.b;
    
    // Sample neighbors from previous iteration
    ivec2 rightXY = ivec2(min(xy.x + 1, textureSize.x - 1), xy.y);
    ivec2 leftXY = ivec2(max(xy.x - 1, 0), xy.y);
    ivec2 topXY = ivec2(xy.x, min(xy.y + 1, textureSize.y - 1));
    ivec2 bottomXY = ivec2(xy.x, max(xy.y - 1, 0));
    
    float center = texelFetch(previousIteration, xy, 0).r;
    float right = texelFetch(previousIteration, rightXY, 0).r;
    float left = texelFetch(previousIteration, leftXY, 0).r;
    float top = texelFetch(previousIteration, topXY, 0).r;
    float bottom = texelFetch(previousIteration, bottomXY, 0).r;
    
    // Compute divergence of compressed gradient
    vec4 gradRight = texelFetch(compressedGradientTexture, rightXY, 0);
    vec4 gradLeft = texelFetch(compressedGradientTexture, leftXY, 0);
    vec4 gradTop = texelFetch(compressedGradientTexture, topXY, 0);
    vec4 gradBottom = texelFetch(compressedGradientTexture, bottomXY, 0);
    
    float divX = (gradRight.r - gradLeft.r) * 0.5;
    float divY = (gradTop.g - gradBottom.g) * 0.5;
    float divergence = divX + divY;
    
    // Jacobi iteration for Poisson equation: ∇²u = div(compressed_gradient)
    // Discrete form: u[i,j] = α * (u[i+1,j] + u[i-1,j] + u[i,j+1] + u[i,j-1] - h² * div(g))
    // For unit spacing, h = 1, so h² = 1
    float neighborsSum = right + left + top + bottom;
    float newValue = alpha * (neighborsSum - divergence);
    
    // Use original value as initial guess for first iteration
    // For subsequent iterations, blend with previous
    float result;
    if (iteration == 0) {
        result = mix(originalValue, newValue, 0.5);
    } else {
        result = mix(center, newValue, 0.7);  // More aggressive update for later iterations
    }
    
    // Output as grayscale (we'll convert back to RGB in a final pass if needed)
    reconstructed = vec4(result, result, result, 1.0);
}

