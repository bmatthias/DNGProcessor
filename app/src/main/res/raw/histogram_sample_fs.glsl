#version 300 es

precision mediump float;

uniform sampler2D highRes;  // xyY format input
uniform int intermediateWidth;
uniform int intermediateHeight;
uniform int outputWidth;   // Downsampled output width
uniform int outputHeight;  // Downsampled output height

// Color space conversion matrices
uniform mat3 XYZtoProPhoto;
uniform mat3 proPhotoToSRGB;

out vec4 result;  // Output gamma-encoded luminance in red channel

#include xyytoxyz
#include gamma

void main() {
    // Scale coordinates to sample from full-resolution highRes texture
    // gl_FragCoord is in the downsampled output space (outputWidth x outputHeight)
    // Scale to normalized texture coordinates [0,1] for the full-res texture
    // This samples evenly across the image
    vec2 texCoord = gl_FragCoord.xy / vec2(float(outputWidth), float(outputHeight));
    
    // Clamp to valid texture coordinates [0,1]
    texCoord = clamp(texCoord, vec2(0.0), vec2(1.0));
    
    // Sample highRes texture (xyY format) using normalized coordinates
    // This automatically handles the downsampling with proper filtering
    vec3 xyY = texture(highRes, texCoord).xyz;
    
    // Convert xyY to XYZ
    vec3 XYZ = xyYtoXYZ(xyY);
    
    // Convert XYZ to ProPhoto RGB
    vec3 proPhoto = XYZtoProPhoto * XYZ;
    
    // Convert ProPhoto to sRGB
    vec3 sRGB = proPhotoToSRGB * proPhoto;
    
    // Clamp negatives only
    sRGB = max(sRGB, vec3(0.0));
    
    // Apply gamma encoding
    vec3 rgbGamma = vec3(gammaEncode(sRGB.r), gammaEncode(sRGB.g), gammaEncode(sRGB.b));
    
    // Calculate luminance in gamma space (Rec. 709)
    float luma = 0.2126 * rgbGamma.r + 0.7152 * rgbGamma.g + 0.0722 * rgbGamma.b;
    
    // Clamp to [0, 1]
    luma = clamp(luma, 0.0, 1.0);
    
    // Output luminance in red channel (other channels unused)
    result = vec4(luma, 0.0, 0.0, 1.0);
}


