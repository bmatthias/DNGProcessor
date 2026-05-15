#version 300 es

precision mediump float;

uniform sampler2D rawBuffer;
uniform sampler2D greenBuffer;
uniform int rawWidth;
uniform int rawHeight;

// Sensor and picture variables
uniform sampler2D gainMap;
uniform int cfaPattern; // The Color Filter Arrangement pattern used
uniform vec4 neutralLevel; // Neutrallevel of sensor
uniform vec3 neutralPoint; // The camera neutral

// Transform
uniform mat3 sensorToXYZ; // Color transform from sensor to XYZ.

// Demosaicing method: 0 = bilinear, 1 = DHT, 2 = AAHD
uniform int demosaicingMethod;

// YUV conversion matrix for AAHD (Rec. 2020 YPbPr coefficients)
uniform mat3 yuvCamMatrix;

const int demosaicArray[16] = int[](
    0, 1, 2, 3,
    1, 0, 3, 2,
    2, 3, 0, 1,
    3, 2, 1, 0
);

// Out (must be vec4 because RGB16F is not color-renderable in GLES 3.0)
out vec4 intermediate;

#include xyztoxyy

// DHT (Directional High-quality Threshold) demosaicing
// Ported and adapted from LibRaw's DHT implementation

// Safe texture fetch with bounds checking
float safeFetch(sampler2D buf, ivec2 xy, int dx, int dy) {
    ivec2 pos = xy + ivec2(dx, dy);
    pos = clamp(pos, ivec2(0), ivec2(rawWidth - 1, rawHeight - 1));
    return texelFetch(buf, pos, 0).x;
}

// Calculate distance ratio (from LibRaw DHT)
float calc_dist(float c1, float c2) {
    return c1 > c2 ? c1 / c2 : c2 / c1;
}

// DHT thresholds
float Thot() { return 64.0; }
float Tg() { return 256.0; }
float T() { return 1.4; }

// Scale functions for highlight handling (from LibRaw DHT)
float scale_over(float ec, float base) {
    float s = base * 0.4;
    float o = ec - base;
    return base + sqrt(s * (o + s)) - s;
}

float scale_under(float ec, float base) {
    float s = base * 0.6;
    float o = base - ec;
    return base - sqrt(s * (o + s)) + s;
}

// Get horizontal/vertical direction for R/B pixels (get_hv_grb from LibRaw)
// HDR-SAFE: Clamp edge weights before ^6 to prevent overflow with HDR values
int get_hv_grb(ivec2 xy, int kc) {
    float hv1 = 2.0 * safeFetch(greenBuffer, xy, 0, -1) /
                (safeFetch(rawBuffer, xy, 0, -2) + safeFetch(rawBuffer, xy, 0, 0));
    float hv2 = 2.0 * safeFetch(greenBuffer, xy, 0, 1) /
                (safeFetch(rawBuffer, xy, 0, 2) + safeFetch(rawBuffer, xy, 0, 0));
    float kv = calc_dist(hv1, hv2) *
               calc_dist(safeFetch(rawBuffer, xy, 0, 0) * safeFetch(rawBuffer, xy, 0, 0),
                         safeFetch(rawBuffer, xy, 0, -2) * safeFetch(rawBuffer, xy, 0, 2));
    kv = min(kv, 2.0);  // HDR-SAFE: Clamp before ^6 to prevent overflow
    kv = kv * kv * kv * kv * kv * kv; // kv^6
    float dv = kv * calc_dist(
        safeFetch(greenBuffer, xy, 0, -3) * safeFetch(greenBuffer, xy, 0, 3),
        safeFetch(greenBuffer, xy, 0, -1) * safeFetch(greenBuffer, xy, 0, 1));
    
    float hh1 = 2.0 * safeFetch(greenBuffer, xy, -1, 0) /
                (safeFetch(rawBuffer, xy, -2, 0) + safeFetch(rawBuffer, xy, 0, 0));
    float hh2 = 2.0 * safeFetch(greenBuffer, xy, 1, 0) /
                (safeFetch(rawBuffer, xy, 2, 0) + safeFetch(rawBuffer, xy, 0, 0));
    float kh = calc_dist(hh1, hh2) *
               calc_dist(safeFetch(rawBuffer, xy, 0, 0) * safeFetch(rawBuffer, xy, 0, 0),
                         safeFetch(rawBuffer, xy, -2, 0) * safeFetch(rawBuffer, xy, 2, 0));
    kh = min(kh, 2.0);  // HDR-SAFE: Clamp before ^6 to prevent overflow
    kh = kh * kh * kh * kh * kh * kh; // kh^6
    float dh = kh * calc_dist(
        safeFetch(greenBuffer, xy, -3, 0) * safeFetch(greenBuffer, xy, 3, 0),
        safeFetch(greenBuffer, xy, -1, 0) * safeFetch(greenBuffer, xy, 1, 0));
    
    float e = calc_dist(dh, dv);
    // Return: 0 = HOR, 1 = VER, 2 = HORSH, 3 = VERSH
    if (dh < dv) {
        return e > Tg() ? 2 : 0; // HORSH : HOR
    } else {
        return e > Tg() ? 3 : 1; // VERSH : VER
    }
}

// Get horizontal/vertical direction for G pixels (get_hv_rbg from LibRaw)
// HDR-SAFE: Clamp edge weights before ^6 to prevent overflow with HDR values
int get_hv_rbg(ivec2 xy, int hc) {
    float hv1 = 2.0 * safeFetch(rawBuffer, xy, 0, -1) /
                (safeFetch(greenBuffer, xy, 0, -2) + safeFetch(greenBuffer, xy, 0, 0));
    float hv2 = 2.0 * safeFetch(rawBuffer, xy, 0, 1) /
                (safeFetch(greenBuffer, xy, 0, 2) + safeFetch(greenBuffer, xy, 0, 0));
    float kv = calc_dist(hv1, hv2) *
               calc_dist(safeFetch(greenBuffer, xy, 0, 0) * safeFetch(greenBuffer, xy, 0, 0),
                         safeFetch(greenBuffer, xy, 0, -2) * safeFetch(greenBuffer, xy, 0, 2));
    kv = min(kv, 2.0);  // HDR-SAFE: Clamp before ^6 to prevent overflow
    kv = kv * kv * kv * kv * kv * kv; // kv^6
    float dv = kv * calc_dist(
        safeFetch(rawBuffer, xy, 0, -3) * safeFetch(rawBuffer, xy, 0, 3),
        safeFetch(rawBuffer, xy, 0, -1) * safeFetch(rawBuffer, xy, 0, 1));
    
    float hh1 = 2.0 * safeFetch(rawBuffer, xy, -1, 0) /
                (safeFetch(greenBuffer, xy, -2, 0) + safeFetch(greenBuffer, xy, 0, 0));
    float hh2 = 2.0 * safeFetch(rawBuffer, xy, 1, 0) /
                (safeFetch(greenBuffer, xy, 2, 0) + safeFetch(greenBuffer, xy, 0, 0));
    float kh = calc_dist(hh1, hh2) *
               calc_dist(safeFetch(greenBuffer, xy, 0, 0) * safeFetch(greenBuffer, xy, 0, 0),
                         safeFetch(greenBuffer, xy, -2, 0) * safeFetch(greenBuffer, xy, 2, 0));
    kh = min(kh, 2.0);  // HDR-SAFE: Clamp before ^6 to prevent overflow
    kh = kh * kh * kh * kh * kh * kh; // kh^6
    float dh = kh * calc_dist(
        safeFetch(rawBuffer, xy, -3, 0) * safeFetch(rawBuffer, xy, 3, 0),
        safeFetch(rawBuffer, xy, -1, 0) * safeFetch(rawBuffer, xy, 1, 0));
    
    float e = calc_dist(dh, dv);
    if (dh < dv) {
        return e > Tg() ? 2 : 0; // HORSH : HOR
    } else {
        return e > Tg() ? 3 : 1; // VERSH : VER
    }
}

// Get diagonal direction (get_diag_grb from LibRaw)
int get_diag_grb(ivec2 xy, int kc) {
    float hlu = safeFetch(greenBuffer, xy, -1, -1) / safeFetch(rawBuffer, xy, -1, -1);
    float hrd = safeFetch(greenBuffer, xy, 1, 1) / safeFetch(rawBuffer, xy, 1, 1);
    float dlurd = calc_dist(hlu, hrd) *
                  calc_dist(
                      safeFetch(greenBuffer, xy, -1, -1) * safeFetch(greenBuffer, xy, 1, 1),
                      safeFetch(greenBuffer, xy, 0, 0) * safeFetch(greenBuffer, xy, 0, 0));
    float druld = calc_dist(hlu, hrd) *
                  calc_dist(
                      safeFetch(greenBuffer, xy, -1, 1) * safeFetch(greenBuffer, xy, 1, -1),
                      safeFetch(greenBuffer, xy, 0, 0) * safeFetch(greenBuffer, xy, 0, 0));
    float e = calc_dist(dlurd, druld);
    // Return: 0 = LURD, 1 = RULD, 2 = LURDSH, 3 = RULDSH
    if (druld < dlurd) {
        return e > T() ? 3 : 1; // RULDSH : RULD
    } else {
        return e > T() ? 2 : 0; // LURDSH : LURD
    }
}

// Get diagonal direction for G pixels (get_diag_rbg from LibRaw)
int get_diag_rbg(ivec2 xy) {
    float dlurd = calc_dist(
        safeFetch(greenBuffer, xy, -1, -1) * safeFetch(greenBuffer, xy, 1, 1),
        safeFetch(greenBuffer, xy, 0, 0) * safeFetch(greenBuffer, xy, 0, 0));
    float druld = calc_dist(
        safeFetch(greenBuffer, xy, -1, 1) * safeFetch(greenBuffer, xy, 1, -1),
        safeFetch(greenBuffer, xy, 0, 0) * safeFetch(greenBuffer, xy, 0, 0));
    float e = calc_dist(dlurd, druld);
    if (druld < dlurd) {
        return e > T() ? 3 : 1; // RULDSH : RULD
    } else {
        return e > T() ? 2 : 0; // LURDSH : LURD
    }
}

// Helper functions for bilinear interpolation
vec4 getCross(sampler2D buf, ivec2 xy) {
    return vec4(
        safeFetch(buf, xy, -1, -1),
        safeFetch(buf, xy, 1, -1),
        safeFetch(buf, xy, -1, 1),
        safeFetch(buf, xy, 1, 1)
    );
}

vec2 getHorz(sampler2D buf, ivec2 xy) {
    return vec2(
        safeFetch(buf, xy, -1, 0),
        safeFetch(buf, xy, 1, 0)
    );
}

vec2 getVert(sampler2D buf, ivec2 xy) {
    return vec2(
        safeFetch(buf, xy, 0, -1),
        safeFetch(buf, xy, 0, 1)
    );
}

float getScale(vec2 raw, vec2 green, float minG) {
    return dot(raw / max(green, minG), vec2(0.5f));
}

float getScale(vec4 raw, vec4 green, float minG) {
    return dot(raw / max(green, minG), vec4(0.25f));
}

// Old bilinear demosaicing (Laroche-Prescott/Su)
vec3 demosaic_bilinear(ivec2 xy) {
    int x = xy.x;
    int y = xy.y;

    int index = (x & 1) | ((y & 1) << 1);
    index |= (cfaPattern << 2);
    vec3 pRGB;
    int pxType = demosaicArray[index];

    // We already computed green
    pRGB.g = texelFetch(greenBuffer, xy, 0).x;
    float minG = 0.01f;
    float g = max(pRGB.g, minG);

    if (pxType == 0 || pxType == 3) {
        float p = texelFetch(rawBuffer, xy, 0).x;
        float cross = g * getScale(
            getCross(rawBuffer, xy),
            getCross(greenBuffer, xy),
            minG
        );
        if (pxType == 0) {
            pRGB.r = p;
            pRGB.b = cross;
        } else {
            pRGB.r = cross;
            pRGB.b = p;
        }
    } else if (pxType == 1 || pxType == 2) {
        float horz = g * getScale(
            getHorz(rawBuffer, xy),
            getHorz(greenBuffer, xy),
            minG
        );
        float vert = g * getScale(
            getVert(rawBuffer, xy),
            getVert(greenBuffer, xy),
            minG
        );
        if (pxType == 1) {
            pRGB.r = horz;
            pRGB.b = vert;
        } else {
            pRGB.r = vert;
            pRGB.b = horz;
        }
    }

    return pRGB;
}

// ============================================================================
// AAHD (Adaptive Anti-aliasing High-quality Demosaicing)
// Ported from LibRaw's AAHD implementation by Anton Petrusevich
// ============================================================================

// AAHD constants
const int AAHD_OverFraction = 8;

// Convert camera RGB to YUV for homogeneity evaluation
vec3 rgbToYUV(vec3 rgb) {
    return yuvCamMatrix * rgb;
}

// AAHD green interpolation for horizontal direction
float aahd_green_hor(ivec2 xy, int pxType) {
    // For R/B centered pixels, interpolate green horizontally
    float c = texelFetch(rawBuffer, xy, 0).x;
    float g_w = safeFetch(greenBuffer, xy, -1, 0);
    float g_e = safeFetch(greenBuffer, xy, 1, 0);
    float c_ww = safeFetch(rawBuffer, xy, -2, 0);
    float c_ee = safeFetch(rawBuffer, xy, 2, 0);
    
    // h1 = 2*g_neighbor - (c_neighbor + c_center)
    float h1 = 2.0 * g_w - (c_ww + c);
    float h2 = 2.0 * g_e - (c_ee + c);
    float h0 = (h1 + h2) / 4.0;
    float eg = c + h0;
    
    // Clamp to reasonable range with margin
    float minG = min(g_w, g_e);
    float maxG = max(g_w, g_e);
    minG -= minG / float(AAHD_OverFraction);
    maxG += maxG / float(AAHD_OverFraction);
    
    if (eg < minG) {
        eg = minG - sqrt(minG - eg);
    } else if (eg > maxG) {
        eg = maxG + sqrt(eg - maxG);
    }
    
    return max(eg, 0.0);
}

// AAHD green interpolation for vertical direction
float aahd_green_ver(ivec2 xy, int pxType) {
    // For R/B centered pixels, interpolate green vertically
    float c = texelFetch(rawBuffer, xy, 0).x;
    float g_n = safeFetch(greenBuffer, xy, 0, -1);
    float g_s = safeFetch(greenBuffer, xy, 0, 1);
    float c_nn = safeFetch(rawBuffer, xy, 0, -2);
    float c_ss = safeFetch(rawBuffer, xy, 0, 2);
    
    float h1 = 2.0 * g_n - (c_nn + c);
    float h2 = 2.0 * g_s - (c_ss + c);
    float h0 = (h1 + h2) / 4.0;
    float eg = c + h0;
    
    float minG = min(g_n, g_s);
    float maxG = max(g_n, g_s);
    minG -= minG / float(AAHD_OverFraction);
    maxG += maxG / float(AAHD_OverFraction);
    
    if (eg < minG) {
        eg = minG - sqrt(minG - eg);
    } else if (eg > maxG) {
        eg = maxG + sqrt(eg - maxG);
    }
    
    return max(eg, 0.0);
}

// AAHD R/B interpolation for horizontal direction (at green-centered pixels)
float aahd_rb_hor(ivec2 xy, int colorChannel) {
    float g = texelFetch(greenBuffer, xy, 0).x;
    float c_w = safeFetch(rawBuffer, xy, -1, 0);
    float c_e = safeFetch(rawBuffer, xy, 1, 0);
    float g_w = safeFetch(greenBuffer, xy, -1, 0);
    float g_e = safeFetch(greenBuffer, xy, 1, 0);
    
    float h1 = c_w - g_w;
    float h2 = c_e - g_e;
    float h0 = (h1 + h2) / 2.0;
    float eg = g + h0;
    
    return max(eg, 0.0);
}

// AAHD R/B interpolation for vertical direction (at green-centered pixels)
float aahd_rb_ver(ivec2 xy, int colorChannel) {
    float g = texelFetch(greenBuffer, xy, 0).x;
    float c_n = safeFetch(rawBuffer, xy, 0, -1);
    float c_s = safeFetch(rawBuffer, xy, 0, 1);
    float g_n = safeFetch(greenBuffer, xy, 0, -1);
    float g_s = safeFetch(greenBuffer, xy, 0, 1);
    
    float h1 = c_n - g_n;
    float h2 = c_s - g_s;
    float h0 = (h1 + h2) / 2.0;
    float eg = g + h0;
    
    return max(eg, 0.0);
}

// AAHD diagonal R/B interpolation for R/B centered pixels
// Uses gradient-based direction selection
float aahd_rb_diag(ivec2 xy, float g) {
    // Diagonal neighbors: NW row (y=-1) and SW row (y=+1)
    // x offsets: -1, 0, +1 for indices 0, 1, 2
    
    // Find best diagonal pair by minimizing gradient
    float bestGd = 1e10;
    int bestDx_k = -1, bestDx_h = -1;
    
    // Iterate over NW row (k: x=-1,0,1, y=-1) and SW row (h: x=-1,0,1, y=+1)
    for (int kx = -1; kx <= 1; kx++) {
        for (int hx = -1; hx <= 1; hx++) {
            float g_k = safeFetch(greenBuffer, xy, kx, -1);
            float g_h = safeFetch(greenBuffer, xy, hx, 1);
            float c_k = safeFetch(rawBuffer, xy, kx, -1);
            float c_h = safeFetch(rawBuffer, xy, hx, 1);
            
            // Gradient = |2*g_center - (g_k + g_h)| + |c_k - c_h|/4
            float gd = abs(2.0 * g - (g_k + g_h)) + 
                       abs(c_k - c_h) / 4.0 +
                       abs(c_k - g_k + g_h - c_h) / 4.0;
            
            if (gd < bestGd) {
                bestGd = gd;
                bestDx_k = kx;
                bestDx_h = hx;
            }
        }
    }
    
    float g_k = safeFetch(greenBuffer, xy, bestDx_k, -1);
    float g_h = safeFetch(greenBuffer, xy, bestDx_h, 1);
    float c_k = safeFetch(rawBuffer, xy, bestDx_k, -1);
    float c_h = safeFetch(rawBuffer, xy, bestDx_h, 1);
    
    float h1 = c_k - g_k;
    float h2 = c_h - g_h;
    float eg = g + (h1 + h2) / 2.0;
    
    return max(eg, 0.0);
}

// Full AAHD interpolation for horizontal direction
vec3 aahd_interpolate_hor(ivec2 xy) {
    int x = xy.x;
    int y = xy.y;
    int index = (x & 1) | ((y & 1) << 1);
    index |= (cfaPattern << 2);
    int pxType = demosaicArray[index];
    
    vec3 rgb;
    
    if (pxType == 0 || pxType == 3) {
        // R or B centered pixel
        float c = texelFetch(rawBuffer, xy, 0).x;
        rgb.g = aahd_green_hor(xy, pxType);
        
        if (pxType == 0) {
            rgb.r = c;
            rgb.b = aahd_rb_diag(xy, rgb.g);
        } else {
            rgb.b = c;
            rgb.r = aahd_rb_diag(xy, rgb.g);
        }
    } else {
        // G centered pixel (pxType 1 or 2)
        rgb.g = texelFetch(greenBuffer, xy, 0).x;
        
        if (pxType == 1) {
            // R-G-R row: R horizontal, B vertical
            rgb.r = aahd_rb_hor(xy, 0);
            rgb.b = aahd_rb_ver(xy, 2);
        } else {
            // B-G-B row: B horizontal, R vertical
            rgb.b = aahd_rb_hor(xy, 2);
            rgb.r = aahd_rb_ver(xy, 0);
        }
    }
    
    return max(rgb, vec3(0.0));
}

// Full AAHD interpolation for vertical direction
vec3 aahd_interpolate_ver(ivec2 xy) {
    int x = xy.x;
    int y = xy.y;
    int index = (x & 1) | ((y & 1) << 1);
    index |= (cfaPattern << 2);
    int pxType = demosaicArray[index];
    
    vec3 rgb;
    
    if (pxType == 0 || pxType == 3) {
        // R or B centered pixel
        float c = texelFetch(rawBuffer, xy, 0).x;
        rgb.g = aahd_green_ver(xy, pxType);
        
        if (pxType == 0) {
            rgb.r = c;
            rgb.b = aahd_rb_diag(xy, rgb.g);
        } else {
            rgb.b = c;
            rgb.r = aahd_rb_diag(xy, rgb.g);
        }
    } else {
        // G centered pixel (pxType 1 or 2)
        rgb.g = texelFetch(greenBuffer, xy, 0).x;
        
        if (pxType == 1) {
            // R-G-R row: for vertical, R comes from vertical, B from horizontal
            rgb.r = aahd_rb_ver(xy, 0);
            rgb.b = aahd_rb_hor(xy, 2);
        } else {
            // B-G-B row: for vertical, B comes from vertical, R from horizontal
            rgb.b = aahd_rb_ver(xy, 2);
            rgb.r = aahd_rb_hor(xy, 0);
        }
    }
    
    return max(rgb, vec3(0.0));
}

// Evaluate homogeneity in YUV space for a given direction
// Returns combined Y and UV differences with neighbors
vec2 aahd_evaluate_homogeneity(ivec2 xy, vec3 yuv, int direction) {
    // direction: 0 = horizontal (evaluate E/W), 1 = vertical (evaluate N/S)
    float yDiff = 0.0;
    float uvDiff = 0.0;
    
    if (direction == 0) {
        // Horizontal: check W and E neighbors
        ivec2 nxy_w = clamp(xy + ivec2(-1, 0), ivec2(1), ivec2(rawWidth - 2, rawHeight - 2));
        ivec2 nxy_e = clamp(xy + ivec2(1, 0), ivec2(1), ivec2(rawWidth - 2, rawHeight - 2));
        
        vec3 nYuv_w = rgbToYUV(aahd_interpolate_hor(nxy_w));
        vec3 nYuv_e = rgbToYUV(aahd_interpolate_hor(nxy_e));
        
        yDiff = abs(yuv.x - nYuv_w.x) + abs(yuv.x - nYuv_e.x);
        uvDiff = (yuv.y - nYuv_w.y) * (yuv.y - nYuv_w.y) + (yuv.z - nYuv_w.z) * (yuv.z - nYuv_w.z) +
                 (yuv.y - nYuv_e.y) * (yuv.y - nYuv_e.y) + (yuv.z - nYuv_e.z) * (yuv.z - nYuv_e.z);
    } else {
        // Vertical: check N and S neighbors
        ivec2 nxy_n = clamp(xy + ivec2(0, -1), ivec2(1), ivec2(rawWidth - 2, rawHeight - 2));
        ivec2 nxy_s = clamp(xy + ivec2(0, 1), ivec2(1), ivec2(rawWidth - 2, rawHeight - 2));
        
        vec3 nYuv_n = rgbToYUV(aahd_interpolate_ver(nxy_n));
        vec3 nYuv_s = rgbToYUV(aahd_interpolate_ver(nxy_s));
        
        yDiff = abs(yuv.x - nYuv_n.x) + abs(yuv.x - nYuv_s.x);
        uvDiff = (yuv.y - nYuv_n.y) * (yuv.y - nYuv_n.y) + (yuv.z - nYuv_n.z) * (yuv.z - nYuv_n.z) +
                 (yuv.y - nYuv_s.y) * (yuv.y - nYuv_s.y) + (yuv.z - nYuv_s.z) * (yuv.z - nYuv_s.z);
    }
    
    return vec2(yDiff, uvDiff);
}

// AAHD main demosaicing function
vec3 demosaic_aahd(ivec2 xy) {
    // Get both interpolations
    vec3 rgb_hor = aahd_interpolate_hor(xy);
    vec3 rgb_ver = aahd_interpolate_ver(xy);
    
    // Convert to YUV for evaluation
    vec3 yuv_hor = rgbToYUV(rgb_hor);
    vec3 yuv_ver = rgbToYUV(rgb_ver);
    
    // Simple gradient-based direction selection
    // This is a simplified version of AAHD's full homogeneity evaluation
    // (Full version would require multiple passes)
    
    // Calculate directional gradients using green channel (most reliable)
    float g_w = safeFetch(greenBuffer, xy, -1, 0);
    float g_e = safeFetch(greenBuffer, xy, 1, 0);
    float g_n = safeFetch(greenBuffer, xy, 0, -1);
    float g_s = safeFetch(greenBuffer, xy, 0, 1);
    float g_c = texelFetch(greenBuffer, xy, 0).x;
    
    // Also check raw buffer for non-green pixels
    float c_w = safeFetch(rawBuffer, xy, -1, 0);
    float c_e = safeFetch(rawBuffer, xy, 1, 0);
    float c_n = safeFetch(rawBuffer, xy, 0, -1);
    float c_s = safeFetch(rawBuffer, xy, 0, 1);
    
    // Horizontal gradient
    float dh = abs(g_w - g_e) + abs(c_w - c_e) + abs(2.0 * g_c - g_w - g_e);
    // Vertical gradient  
    float dv = abs(g_n - g_s) + abs(c_n - c_s) + abs(2.0 * g_c - g_n - g_s);
    
    // YUV-based homogeneity (simplified: just compare Y differences)
    float y_diff_hor = abs(yuv_hor.x - rgbToYUV(aahd_interpolate_hor(xy + ivec2(-1, 0))).x) +
                       abs(yuv_hor.x - rgbToYUV(aahd_interpolate_hor(xy + ivec2(1, 0))).x);
    float y_diff_ver = abs(yuv_ver.x - rgbToYUV(aahd_interpolate_ver(xy + ivec2(0, -1))).x) +
                       abs(yuv_ver.x - rgbToYUV(aahd_interpolate_ver(xy + ivec2(0, 1))).x);
    
    // Combine gradient and YUV metrics
    float score_hor = dh + y_diff_hor * 0.5;
    float score_ver = dv + y_diff_ver * 0.5;
    
    // Select direction with lower score (more homogeneous)
    // Add hysteresis to prevent noise in flat regions
    float threshold = 1.2;
    
    if (score_ver > score_hor * threshold) {
        // Prefer horizontal interpolation
        return rgb_hor;
    } else if (score_hor > score_ver * threshold) {
        // Prefer vertical interpolation
        return rgb_ver;
    } else {
        // Blend when ambiguous
        float blend = 0.5;
        if (score_hor + score_ver > 0.001) {
            blend = score_ver / (score_hor + score_ver);
        }
        return mix(rgb_ver, rgb_hor, blend);
    }
}

// DHT demosaicing - main function
vec3 demosaic_DHT(ivec2 xy) {
    int x = xy.x;
    int y = xy.y;
    
    int index = (x & 1) | ((y & 1) << 1);
    index |= (cfaPattern << 2);
    vec3 pRGB;
    int pxType = demosaicArray[index];
    
    // Green is already computed
    pRGB.g = texelFetch(greenBuffer, xy, 0).x;
    float g = max(pRGB.g, 0.01);
    
    if (pxType == 0 || pxType == 3) {
        // Red or Blue centered pixel
        float c = texelFetch(rawBuffer, xy, 0).x;
        int kc = (pxType == 0) ? 0 : 2; // 0 = red, 2 = blue
        int cl = kc ^ 2; // opposite color
        
        if (pxType == 0) {
            pRGB.r = c;
        } else {
            pRGB.b = c;
        }
        
        // Interpolate the missing color using DHT diagonal interpolation
        int diag_dir = get_diag_grb(xy, kc);
        int dx, dy, dx2, dy2;
        
        if (diag_dir == 0 || diag_dir == 2) { // LURD
            dx = -1; dx2 = 1; dy = -1; dy2 = 1;
        } else { // RULD
            dx = -1; dx2 = 1; dy = 1; dy2 = -1;
        }
        
        float g1 = 1.0 / calc_dist(g, safeFetch(greenBuffer, xy, dx, dy));
        float g2 = 1.0 / calc_dist(g, safeFetch(greenBuffer, xy, dx2, dy2));
        g1 = g1 * g1 * g1; // g1^3
        g2 = g2 * g2 * g2; // g2^3
        
        float eg = g * (g1 * safeFetch(rawBuffer, xy, dx, dy) / safeFetch(greenBuffer, xy, dx, dy) +
                       g2 * safeFetch(rawBuffer, xy, dx2, dy2) / safeFetch(greenBuffer, xy, dx2, dy2)) /
                   (g1 + g2);
        
        // Clamp to reasonable range
        float min_val = min(safeFetch(rawBuffer, xy, dx, dy), safeFetch(rawBuffer, xy, dx2, dy2)) / 1.2;
        float max_val = max(safeFetch(rawBuffer, xy, dx, dy), safeFetch(rawBuffer, xy, dx2, dy2)) * 1.2;
        
        if (eg < min_val) {
            eg = scale_under(eg, min_val);
        } else if (eg > max_val) {
            eg = scale_over(eg, max_val);
        }
        
        if (pxType == 0) {
            pRGB.b = max(eg, 0.0);
        } else {
            pRGB.r = max(eg, 0.0);
        }
        
    } else {
        // Green centered pixel - interpolate R and B using DHT horizontal/vertical
        // For GRBG pattern: pxType 1 = R-G-R row (R horizontal), pxType 2 = B-G-B row (B horizontal)
        int hv_dir = get_hv_rbg(xy, 0); // Use red channel for direction (could also use blue)
        
        int dx, dy, dx2, dy2;
        if (hv_dir == 1 || hv_dir == 3) { // VER or VERSH
            dx = dx2 = 0;
            dy = -1;
            dy2 = 1;
        } else { // HOR or HORSH
            dy = dy2 = 0;
            dx = 1;
            dx2 = -1;
        }
        
        float g1 = 1.0 / calc_dist(g, safeFetch(greenBuffer, xy, dx, dy));
        float g2 = 1.0 / calc_dist(g, safeFetch(greenBuffer, xy, dx2, dy2));
        g1 = g1 * g1; // g1^2
        g2 = g2 * g2; // g2^2
        
        // For green-centered pixels, we need to get R and B from adjacent pixels
        // pxType 1: R-G-R row, so R is horizontal (dx direction), B is vertical (dy direction)
        // pxType 2: B-G-B row, so B is horizontal (dx direction), R is vertical (dy direction)
        
        float r_h1, r_h2, b_v1, b_v2;
        if (pxType == 1) {
            // R-G-R row: get R from horizontal neighbors, B from vertical neighbors
            r_h1 = safeFetch(rawBuffer, xy, dx, 0);
            r_h2 = safeFetch(rawBuffer, xy, dx2, 0);
            b_v1 = safeFetch(rawBuffer, xy, 0, dy);
            b_v2 = safeFetch(rawBuffer, xy, 0, dy2);
        } else {
            // B-G-B row: get B from horizontal neighbors, R from vertical neighbors
            b_v1 = safeFetch(rawBuffer, xy, dx, 0);
            b_v2 = safeFetch(rawBuffer, xy, dx2, 0);
            r_h1 = safeFetch(rawBuffer, xy, 0, dy);
            r_h2 = safeFetch(rawBuffer, xy, 0, dy2);
        }
        
        // Interpolate red using horizontal direction
        float g_r1 = 1.0 / calc_dist(g, safeFetch(greenBuffer, xy, dx, 0));
        float g_r2 = 1.0 / calc_dist(g, safeFetch(greenBuffer, xy, dx2, 0));
        g_r1 = g_r1 * g_r1;
        g_r2 = g_r2 * g_r2;
        float eg_r = g * (g_r1 * r_h1 / safeFetch(greenBuffer, xy, dx, 0) +
                          g_r2 * r_h2 / safeFetch(greenBuffer, xy, dx2, 0)) /
                      (g_r1 + g_r2);
        
        // Interpolate blue using vertical direction
        float g_b1 = 1.0 / calc_dist(g, safeFetch(greenBuffer, xy, 0, dy));
        float g_b2 = 1.0 / calc_dist(g, safeFetch(greenBuffer, xy, 0, dy2));
        g_b1 = g_b1 * g_b1;
        g_b2 = g_b2 * g_b2;
        float eg_b = g * (g_b1 * b_v1 / safeFetch(greenBuffer, xy, 0, dy) +
                          g_b2 * b_v2 / safeFetch(greenBuffer, xy, 0, dy2)) /
                      (g_b1 + g_b2);
        
        // Clamp to reasonable range
        float min_r = min(r_h1, r_h2) / 1.2;
        float max_r = max(r_h1, r_h2) * 1.2;
        float min_b = min(b_v1, b_v2) / 1.2;
        float max_b = max(b_v1, b_v2) * 1.2;
        
        if (eg_r < min_r) {
            eg_r = scale_under(eg_r, min_r);
        } else if (eg_r > max_r) {
            eg_r = scale_over(eg_r, max_r);
        }
        if (eg_b < min_b) {
            eg_b = scale_under(eg_b, min_b);
        } else if (eg_b > max_b) {
            eg_b = scale_over(eg_b, max_b);
        }
        
        pRGB.r = max(eg_r, 0.0);
        pRGB.b = max(eg_b, 0.0);
    }
    
    return max(pRGB, vec3(0.0));
}

// Main demosaicing function that switches based on method
vec3 demosaic(ivec2 xy) {
    if (demosaicingMethod == 0) {
        return demosaic_bilinear(xy);
    } else if (demosaicingMethod == 1) {
        return demosaic_DHT(xy);
    } else { // demosaicingMethod == 2 (AAHD)
        return demosaic_aahd(xy);
    }
}

vec3 convertSensorToIntermediate(ivec2 xy, vec3 sensor) {
    // Use gainmap to increase dynamic range.
    vec2 xyInterp = vec2(float(xy.x) / float(rawWidth), float(xy.y) / float(rawHeight));
    vec4 gains = texture(gainMap, xyInterp);
    
    // Protect against zero gains which would cause division by zero
    float minGain = max(min(min(gains.x, gains.y), min(gains.z, gains.w)), 0.001);
    vec3 neutralScaled = minGain * max(neutralPoint, vec3(0.001));

    vec3 npf = sensor / neutralScaled;
    // BEST PRACTICE: Don't clip sensor values - preserve HDR headroom
    // Baseline exposure was applied in stage1, values can exceed 1.0
    // sensor = min(sensor, neutralScaled);  // DISABLED for HDR support
    
    // Clamp npf to prevent Inf from corrupting calculations
    npf = clamp(npf, vec3(0.0), vec3(10.0));

    // When both red and blue channels are above white point, assume green is too
    // So extend dynamic range by scaling white point
    // Use a bias so only high green values become higher
    // In highlights, bias should be one
    float bias = clamp(npf.g * npf.g * npf.g, 0.0, 1.0);
    sensor *= mix(1.f, max(npf.r + npf.b, 2.f) * 0.5f, bias);

    vec3 XYZ = sensorToXYZ * sensor;
    vec3 intermediate = XYZtoxyY(XYZ);

    return intermediate;
}

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    ivec2 xyClamped = clamp(xy, ivec2(1), ivec2(rawWidth, rawHeight) - 2);

    vec3 sensor = demosaic(xyClamped);
    vec3 xyY = convertSensorToIntermediate(xy, sensor);
    
    // HDR ENCODING for Y luminance
    // Store Y normalized to [0,1] with scale in alpha channel
    // This works around OpenGL ES 3.0 Float16 framebuffer clamping
    // Only encode if Y > 1.0 to avoid quantization when already in [0,1]
    float Y = xyY.z;
    if (Y <= 1.0) {
        // Y already in [0,1] - no encoding needed, use alpha=1.0 as marker
        intermediate = vec4(xyY.x, xyY.y, Y, 1.0);
    } else {
        // HDR value - encode with scaling
        float hdrScale = Y;
        intermediate = vec4(xyY.x, xyY.y, Y / hdrScale, 1.0 / hdrScale);
    }
}
