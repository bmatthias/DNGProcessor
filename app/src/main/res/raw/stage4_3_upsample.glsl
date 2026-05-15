#version 300 es

precision mediump float;

uniform sampler2D buf;

// Maximum valid coordinates (width-1, height-1) for edge clamping
uniform ivec2 maxxy;

out float result;

void main() {
    ivec2 xyCenter = ivec2(gl_FragCoord.xy);
    ivec2 xyDownscaled = xyCenter / 2;
    ivec2 xyAlign = xyCenter % 2;

    // Clamp coordinates to valid texture bounds to avoid reading garbage at edges
    ivec2 maxCoord = (maxxy.x > 0) ? maxxy : ivec2(65535, 65535);
    
    ivec2 xyTL = xyDownscaled;
    ivec2 xyTR = min(xyDownscaled + ivec2(1, 0), maxCoord);
    ivec2 xyBL = min(xyDownscaled + ivec2(0, 1), maxCoord);
    ivec2 xyBR = min(xyDownscaled + ivec2(1, 1), maxCoord);

    // Fetch all four neighbors with proper edge clamping
    float topLeft = texelFetch(buf, xyTL, 0).x;
    float topRight = texelFetch(buf, xyTR, 0).x;
    float bottomLeft = texelFetch(buf, xyBL, 0).x;
    float bottomRight = texelFetch(buf, xyBR, 0).x;

    // Linear interpolation over 2x upscaling
    // Use bilinear interpolation based on alignment within the 2x2 block
    int pxFour = 2 * xyAlign.y + xyAlign.x;
    switch (pxFour) {
        case 0: // TL - exact sample
            result = topLeft;
            break;
        case 1: // TR - horizontal interpolation
            result = (topLeft + topRight) * 0.5;
            break;
        case 2: // BL - vertical interpolation
            result = (topLeft + bottomLeft) * 0.5;
            break;
        case 3: // BR - full bilinear interpolation
            result = (topLeft + topRight + bottomLeft + bottomRight) * 0.25;
            break;
    }
}
