#version 300 es
/*
 * Local Laplacian Filter - Gaussian Expand (Upsample with interpolation)
 * Ported from darktable's locallaplacian.cl
 * 
 * This shader upsamples the coarse level by 2x using Gaussian interpolation
 * to create a blurry version at the finer resolution.
 */

precision highp float;

uniform sampler2D coarse_tex;  // Coarse level input
uniform int fine_width;        // Output (fine) width
uniform int fine_height;       // Output (fine) height

out float result;

// Gaussian expand using 5-tap kernel [1, 4, 6, 4, 1] / 16
float expand_gaussian(int i, int j) {
    // 5-tap kernel weights
    const float w[5] = float[5](1.0/16.0, 4.0/16.0, 6.0/16.0, 4.0/16.0, 1.0/16.0);
    
    int cx = i / 2;
    int cy = j / 2;
    
    float c = 0.0;
    
    // Different stencils based on whether i,j are odd or even
    int caseId = (i & 1) + 2 * (j & 1);
    
    if (caseId == 0) {
        // Both even: 3x3 stencil centered on coarse pixel
        for (int jj = -1; jj <= 1; jj++) {
            for (int ii = -1; ii <= 1; ii++) {
                float pixel = texelFetch(coarse_tex, ivec2(cx + ii, cy + jj), 0).x;
                c += pixel * w[2 * jj + 2] * w[2 * ii + 2];
            }
        }
    } else if (caseId == 1) {
        // i odd: 2x3 stencil
        for (int jj = -1; jj <= 1; jj++) {
            for (int ii = 0; ii <= 1; ii++) {
                float pixel = texelFetch(coarse_tex, ivec2(cx + ii, cy + jj), 0).x;
                c += pixel * w[2 * jj + 2] * w[2 * ii + 1];
            }
        }
    } else if (caseId == 2) {
        // j odd: 3x2 stencil
        for (int jj = 0; jj <= 1; jj++) {
            for (int ii = -1; ii <= 1; ii++) {
                float pixel = texelFetch(coarse_tex, ivec2(cx + ii, cy + jj), 0).x;
                c += pixel * w[2 * jj + 1] * w[2 * ii + 2];
            }
        }
    } else {
        // Both odd: 2x2 stencil
        for (int jj = 0; jj <= 1; jj++) {
            for (int ii = 0; ii <= 1; ii++) {
                float pixel = texelFetch(coarse_tex, ivec2(cx + ii, cy + jj), 0).x;
                c += pixel * w[2 * jj + 1] * w[2 * ii + 1];
            }
        }
    }
    
    return 4.0 * c;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    int x = xy.x;
    int y = xy.y;
    
    if (x >= fine_width || y >= fine_height) {
        result = 0.0;
        return;
    }
    
    // Clamp to valid boundary (1 or 2 px depending on even/odd dimensions)
    int cx = x;
    int cy = y;
    
    if ((fine_width & 1) != 0) {
        if (x > fine_width - 2) cx = fine_width - 2;
    } else {
        if (x > fine_width - 3) cx = fine_width - 3;
    }
    
    if ((fine_height & 1) != 0) {
        if (y > fine_height - 2) cy = fine_height - 2;
    } else {
        if (y > fine_height - 3) cy = fine_height - 3;
    }
    
    if (cx <= 0) cx = 1;
    if (cy <= 0) cy = 1;
    
    result = expand_gaussian(cx, cy);
}
