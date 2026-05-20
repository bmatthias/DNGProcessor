#version 300 es

// Wronski Alg. 6: apply_noise_model + robustness_threshold.

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D refMeanTex;
uniform highp sampler2D refVarTex;
uniform highp sampler2D altMeanTex;
uniform highp sampler2D altVarTex;
uniform highp sampler2D stdCurveTex;
uniform highp sampler2D diffCurveTex;
uniform highp sampler2D flowSTex;
uniform highp vec2 tileFlowOriginSrc;
uniform highp vec2 tileFlowStrideSrc;
uniform highp ivec2 tileFlowSize;
uniform highp float robustT;
uniform highp float robustSoftWidth;

layout(location = 0) out highp float fragColor;

highp float curveSample(highp sampler2D tex, highp float brightness) {
    highp float u = clamp(brightness, 0.0, 1.0);
    return texture(tex, vec2(u, 0.5)).r;
}

void main() {
    highp ivec2 p = ivec2(gl_FragCoord.xy);
    highp vec3 refMean = texelFetch(refMeanTex, p, 0).rgb;
    highp vec3 refVar = texelFetch(refVarTex, p, 0).rgb;
    highp vec3 altMean = texelFetch(altMeanTex, p, 0).rgb;
    highp vec3 altVar = texelFetch(altVarTex, p, 0).rgb;

    highp float dSq = 0.0;
    highp float sigmaSq = 0.0;
    for (highp int ch = 0; ch < 3; ch++) {
        highp float brightness = refMean[ch];
        highp float dT = curveSample(diffCurveTex, brightness);
        highp float sigmaT = curveSample(stdCurveTex, brightness);
        sigmaSq += max(refVar[ch], sigmaT * sigmaT);
        highp float dP = abs(refMean[ch] - altMean[ch]);
        highp float dPSq = dP * dP;
        highp float shrink = dPSq / (dPSq + dT * dT);
        dSq += dPSq * shrink * shrink;
    }
    highp vec2 rawCenter = vec2(p) + vec2(0.5);
    highp vec2 gridF = (rawCenter - tileFlowOriginSrc) / tileFlowStrideSrc;
    highp ivec2 maxG = tileFlowSize - ivec2(1);
    highp ivec2 g = clamp(ivec2(round(gridF)), ivec2(0), maxG);
    highp float flowS = texelFetch(flowSTex, g, 0).r;

    highp float R = flowS * exp(-dSq / (sigmaSq + 1e-6));
    // Soft threshold reduces hard zero blocks in the merge.
    fragColor = smoothstep(robustT, robustT + robustSoftWidth, R);
}
