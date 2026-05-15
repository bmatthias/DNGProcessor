#version 300 es

precision mediump float;

uniform sampler2D buf;
uniform ivec2 bufEdge;

uniform float blendY;

out vec4 result;

vec3[9] load3x3(ivec2 xy) {
    vec3 outputArray[9];
    ivec2 xyPx;
    for (int i = 0; i < 9; i++) {
        xyPx = xy + ivec2((i % 3) - 1, (i / 3) - 1);
        xyPx = clamp(xyPx, ivec2(0), bufEdge);
        // Decode HDR luminance: Y = z * w
        vec4 texData = texelFetch(buf, xyPx, 0);
        float invScale = max(texData.w, 0.001);
        outputArray[i] = vec3(texData.x, texData.y, texData.z / invScale);
    }
    return outputArray;
}

void main() {
    ivec2 xyPos = ivec2(gl_FragCoord.xy);

    vec3[9] impatch = load3x3(xyPos);

    vec3 minTop = min(min(impatch[0], impatch[1]), impatch[2]);
    vec3 maxTop = max(max(impatch[0], impatch[1]), impatch[2]);
    vec3 medianTop = impatch[0] + impatch[1] + impatch[2] - minTop - maxTop;

    vec3 minMid = min(min(impatch[3], impatch[4]), impatch[5]);
    vec3 maxMid = max(max(impatch[3], impatch[4]), impatch[5]);
    vec3 medianMid = impatch[3] + impatch[4] + impatch[5] - minMid - maxMid;

    vec3 minBot = min(min(impatch[6], impatch[7]), impatch[8]);
    vec3 maxBot = max(max(impatch[6], impatch[7]), impatch[8]);
    vec3 medianBot = impatch[6] + impatch[7] + impatch[8] - minBot - maxBot;

    vec3 minVert = min(min(medianTop, medianMid), medianBot);
    vec3 maxVert = max(max(medianTop, medianMid), medianBot);

    vec3 tmp = medianTop + medianMid + medianBot - minVert - maxVert;
    float Y = mix(tmp.z, impatch[4].z, blendY);
    
    // Re-encode HDR luminance - only if Y > 1.0 to avoid quantization
    if (Y <= 1.0) {
        // Y already in [0,1] - no encoding needed, use alpha=1.0 as marker
        result = vec4(tmp.xy, Y, 1.0);
    } else {
        // HDR value - encode with scaling
        float hdrScale = Y;
        result = vec4(tmp.xy, Y / hdrScale, 1.0 / hdrScale);
    }
}
