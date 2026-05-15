#version 300 es

precision mediump float;

uniform sampler2D inputTexture;
uniform ivec2 textureSize;  // Width and height of texture

// Output: R=horizontal gradient, G=vertical gradient, B=original value, A=unused
out vec4 gradient;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    
    // Sample center and neighbors using texelFetch for exact pixel access
    vec3 center = texelFetch(inputTexture, xy, 0).rgb;
    vec3 right = texelFetch(inputTexture, ivec2(min(xy.x + 1, textureSize.x - 1), xy.y), 0).rgb;
    vec3 left = texelFetch(inputTexture, ivec2(max(xy.x - 1, 0), xy.y), 0).rgb;
    vec3 top = texelFetch(inputTexture, ivec2(xy.x, min(xy.y + 1, textureSize.y - 1)), 0).rgb;
    vec3 bottom = texelFetch(inputTexture, ivec2(xy.x, max(xy.y - 1, 0)), 0).rgb;
    
    // Convert to luminance for gradient computation
    float centerLuma = dot(center, vec3(0.2126, 0.7152, 0.0722));
    float rightLuma = dot(right, vec3(0.2126, 0.7152, 0.0722));
    float leftLuma = dot(left, vec3(0.2126, 0.7152, 0.0722));
    float topLuma = dot(top, vec3(0.2126, 0.7152, 0.0722));
    float bottomLuma = dot(bottom, vec3(0.2126, 0.7152, 0.0722));
    
    // Compute gradients using central differences
    float gradX = (rightLuma - leftLuma) * 0.5;
    float gradY = (topLuma - bottomLuma) * 0.5;
    
    // Store gradients and original RGB value
    gradient = vec4(gradX, gradY, centerLuma, 1.0);
}

