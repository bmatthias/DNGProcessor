#version 300 es
/*
 * Local Laplacian Filter - Gaussian Reduce (Downsample with blur)
 * Ported from darktable's locallaplacian.cl
 * 
 * This shader downsamples the input by 2x while applying a 5x5 Gaussian blur
 * to create the Gaussian pyramid levels.
 */

precision highp float;

uniform sampler2D input_tex;
uniform int input_width;
uniform int input_height;

out float result;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    int x = xy.x;
    int y = xy.y;
    
    // Output dimensions (coarse)
    int cw = (input_width - 1) / 2 + 1;
    int ch = (input_height - 1) / 2 + 1;
    
    if (x >= cw || y >= ch) {
        result = 0.0;
        return;
    }
    
    // Clamp to valid range (1 pixel boundary)
    int cx = clamp(x, 1, cw - 2);
    int cy = clamp(y, 1, ch - 2);
    
    // 5-tap Gaussian kernel: [1, 4, 6, 4, 1] / 16
    const float w[5] = float[5](1.0/16.0, 4.0/16.0, 6.0/16.0, 4.0/16.0, 1.0/16.0);
    
    // Apply 5x5 Gaussian blur at 2x input coordinates
    float sum = 0.0;
    for (int jj = -2; jj <= 2; jj++) {
        for (int ii = -2; ii <= 2; ii++) {
            ivec2 samplePos = ivec2(2 * cx + ii, 2 * cy + jj);
            samplePos = clamp(samplePos, ivec2(0), ivec2(input_width - 1, input_height - 1));
            float pixel = texelFetch(input_tex, samplePos, 0).x;
            sum += pixel * w[ii + 2] * w[jj + 2];
        }
    }
    
    result = sum;
}
