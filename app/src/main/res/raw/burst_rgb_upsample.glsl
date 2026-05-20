#version 300 es

// Bilinear 1× → 2× upscale of the demosaiced intermediate (RGBA / xyY).
//
// Bayer-domain SR upsamples R, G, and B on separate lattices; merge
// misregistration becomes red/cyan fringing after demosaic. Upscaling
// already-demosaiced RGB keeps channels aligned.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D srcTex;
uniform highp ivec2 srcSize;

layout(location = 0) out highp vec4 fragColor;

highp vec4 fetchBilinear(highp vec2 pos) {
    highp vec2 p = clamp(pos, vec2(0.0), vec2(float(srcSize.x - 1), float(srcSize.y - 1)));
    highp ivec2 i0 = ivec2(floor(p));
    highp vec2 f = p - vec2(i0);
    highp ivec2 i1 = min(i0 + ivec2(1), srcSize - ivec2(1));
    highp vec4 v00 = texelFetch(srcTex, i0,              0);
    highp vec4 v10 = texelFetch(srcTex, ivec2(i1.x, i0.y), 0);
    highp vec4 v01 = texelFetch(srcTex, ivec2(i0.x, i1.y), 0);
    highp vec4 v11 = texelFetch(srcTex, i1,              0);
    return mix(mix(v00, v10, f.x), mix(v01, v11, f.x), f.y);
}

void main() {
    highp vec2 srcPos = (vec2(gl_FragCoord.xy) + 0.5) * 0.5 - 0.5;
    fragColor = fetchBilinear(srcPos);
}
