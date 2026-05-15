#version 300 es
/*
 * Local Laplacian Filter - Laplacian Assemble (Pyramid Reconstruction)
 * Ported from darktable's locallaplacian.cl
 * 
 * This shader reconstructs the output image by:
 * 1. Upsampling the coarser output level
 * 2. Computing Laplacian coefficients from the processed gamma images
 * 3. Interpolating between gamma levels based on local luminance
 * 4. Adding the interpolated Laplacian to the upsampled coarse output
 * 
 * The key insight: instead of using the original image's Laplacian directly,
 * we interpolate between Laplacians computed from multiple gamma-adjusted 
 * versions. This allows local tone mapping without halos.
 */

precision highp float;

// Input textures
uniform sampler2D input_tex;       // Original padded input at this level (for luminance lookup)
uniform sampler2D output_coarse;   // Output from coarser level (to be expanded)

// Processed gamma images at current (fine) level and next (coarse) level
// We use 6 gamma levels: 0.0833, 0.25, 0.4167, 0.5833, 0.75, 0.9167
uniform sampler2D gamma0_fine;
uniform sampler2D gamma0_coarse;
uniform sampler2D gamma1_fine;
uniform sampler2D gamma1_coarse;
uniform sampler2D gamma2_fine;
uniform sampler2D gamma2_coarse;
uniform sampler2D gamma3_fine;
uniform sampler2D gamma3_coarse;
uniform sampler2D gamma4_fine;
uniform sampler2D gamma4_coarse;
uniform sampler2D gamma5_fine;
uniform sampler2D gamma5_coarse;

uniform int fine_width;
uniform int fine_height;

out float result;

// Number of gamma levels
const int NUM_GAMMA = 6;

// Gaussian expand for upsampling coarse level
float expand_gaussian(sampler2D coarse_tex, int i, int j, int fw, int fh) {
    const float w[5] = float[5](1.0/16.0, 4.0/16.0, 6.0/16.0, 4.0/16.0, 1.0/16.0);
    
    int cx = i / 2;
    int cy = j / 2;
    
    float c = 0.0;
    int caseId = (i & 1) + 2 * (j & 1);
    
    if (caseId == 0) {
        for (int jj = -1; jj <= 1; jj++) {
            for (int ii = -1; ii <= 1; ii++) {
                float pixel = texelFetch(coarse_tex, ivec2(cx + ii, cy + jj), 0).x;
                c += pixel * w[2 * jj + 2] * w[2 * ii + 2];
            }
        }
    } else if (caseId == 1) {
        for (int jj = -1; jj <= 1; jj++) {
            for (int ii = 0; ii <= 1; ii++) {
                float pixel = texelFetch(coarse_tex, ivec2(cx + ii, cy + jj), 0).x;
                c += pixel * w[2 * jj + 2] * w[2 * ii + 1];
            }
        }
    } else if (caseId == 2) {
        for (int jj = 0; jj <= 1; jj++) {
            for (int ii = -1; ii <= 1; ii++) {
                float pixel = texelFetch(coarse_tex, ivec2(cx + ii, cy + jj), 0).x;
                c += pixel * w[2 * jj + 1] * w[2 * ii + 2];
            }
        }
    } else {
        for (int jj = 0; jj <= 1; jj++) {
            for (int ii = 0; ii <= 1; ii++) {
                float pixel = texelFetch(coarse_tex, ivec2(cx + ii, cy + jj), 0).x;
                c += pixel * w[2 * jj + 1] * w[2 * ii + 1];
            }
        }
    }
    
    return 4.0 * c;
}

// Compute Laplacian coefficient: fine - expand(coarse)
float laplacian(sampler2D fine_tex, sampler2D coarse_tex, int x, int y, int ci, int cj, int fw, int fh) {
    float expanded = expand_gaussian(coarse_tex, ci, cj, fw, fh);
    float fine = texelFetch(fine_tex, ivec2(x, y), 0).x;
    return fine - expanded;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    int x = xy.x;
    int y = xy.y;
    
    if (x >= fine_width || y >= fine_height) {
        result = 0.0;
        return;
    }
    
    // Compute clamped coordinates for boundary handling
    int ci = x;
    int cj = y;
    
    if ((fine_width & 1) != 0) {
        if (x > fine_width - 2) ci = fine_width - 2;
    } else {
        if (x > fine_width - 3) ci = fine_width - 3;
    }
    
    if ((fine_height & 1) != 0) {
        if (y > fine_height - 2) cj = fine_height - 2;
    } else {
        if (y > fine_height - 3) cj = fine_height - 3;
    }
    
    if (ci <= 0) ci = 1;
    if (cj <= 0) cj = 1;
    
    // Start with upsampled coarse output
    float pixel = expand_gaussian(output_coarse, ci, cj, fine_width, fine_height);
    
    // Get the local luminance from original input (determines which gamma images to interpolate)
    float v = texelFetch(input_tex, xy, 0).x;
    
    // Find which two gamma levels to interpolate between
    // Gamma levels are at: 0.0833, 0.25, 0.4167, 0.5833, 0.75, 0.9167 (i.e., (k+0.5)/6)
    int hi = 1;
    for (; hi < NUM_GAMMA - 1; hi++) {
        float gamma_hi = (float(hi) + 0.5) / float(NUM_GAMMA);
        if (gamma_hi > v) break;
    }
    int lo = hi - 1;
    
    // Interpolation weight
    float gamma_lo = (float(lo) + 0.5) / float(NUM_GAMMA);
    float gamma_hi = (float(hi) + 0.5) / float(NUM_GAMMA);
    float a = clamp((v - gamma_lo) / (gamma_hi - gamma_lo), 0.0, 1.0);
    
    // Compute Laplacian from the two bracketing gamma images and interpolate
    float l0, l1;
    
    // Unfortunately GLSL ES doesn't support texture arrays easily, so we use switch
    if (lo == 0) {
        l0 = laplacian(gamma0_fine, gamma0_coarse, x, y, ci, cj, fine_width, fine_height);
        l1 = laplacian(gamma1_fine, gamma1_coarse, x, y, ci, cj, fine_width, fine_height);
    } else if (lo == 1) {
        l0 = laplacian(gamma1_fine, gamma1_coarse, x, y, ci, cj, fine_width, fine_height);
        l1 = laplacian(gamma2_fine, gamma2_coarse, x, y, ci, cj, fine_width, fine_height);
    } else if (lo == 2) {
        l0 = laplacian(gamma2_fine, gamma2_coarse, x, y, ci, cj, fine_width, fine_height);
        l1 = laplacian(gamma3_fine, gamma3_coarse, x, y, ci, cj, fine_width, fine_height);
    } else if (lo == 3) {
        l0 = laplacian(gamma3_fine, gamma3_coarse, x, y, ci, cj, fine_width, fine_height);
        l1 = laplacian(gamma4_fine, gamma4_coarse, x, y, ci, cj, fine_width, fine_height);
    } else { // lo >= 4
        l0 = laplacian(gamma4_fine, gamma4_coarse, x, y, ci, cj, fine_width, fine_height);
        l1 = laplacian(gamma5_fine, gamma5_coarse, x, y, ci, cj, fine_width, fine_height);
    }
    
    // Add interpolated Laplacian to the reconstruction
    pixel += l0 * (1.0 - a) + l1 * a;
    
    result = pixel;
}
