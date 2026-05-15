#version 300 es

precision mediump float;

uniform sampler2D buf;

// Out (must be vec4 because RGB16F is not color-renderable in GLES 3.0)
out vec4 filtered;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    float unfiltered[9];
    float tmp;
    int j;

    for (int i = 0; i < 9; i++) {
        // Decode HDR luminance: Y = z * w
        vec4 texData = texelFetch(buf, xy + ivec2((i % 3) - 1, (i / 3) - 1), 0);
        float invScale = max(texData.w, 0.001);
        tmp = texData.z / invScale;
        j = i;
        // Shift larger values forward, starting from the right.
        while (j > 0 && tmp < unfiltered[j - 1]) {
            unfiltered[j] = unfiltered[--j];
        }
        unfiltered[j] = tmp;
    }

    filtered.xy = texelFetch(buf, xy, 0).xy;
    float medianY = unfiltered[4];
    
    // Re-encode HDR luminance
    float hdrScale = max(medianY, 1.0);
    filtered.z = medianY / hdrScale;
    filtered.w = 1.0 / hdrScale;  // Store 1/scale so it won't be clamped
}
