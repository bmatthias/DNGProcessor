#version 300 es

// Explicit highp required by GLES 3.0 §4.5.3 for stable accumulation.
precision highp float;
precision highp int;
precision highp sampler2D;

// 4-channel accumulation texture: .rgb = weighted_rgb_sum, .a = weight_sum
uniform highp sampler2D accumBuffer;

// Post-merge linear exposure boost with Reinhard soft-clip on highlights.
uniform highp float postMergeGain;

// Output: normalised RGBA (alpha = 1.0)
out highp vec4 fragColor;

void main() {
    highp ivec2 pos = ivec2(gl_FragCoord.xy);
    highp vec4 accum = texelFetch(accumBuffer, pos, 0);
    highp float weightSum = accum.a;
    highp vec3 merged = (weightSum > 1e-6) ? (accum.rgb / weightSum) : vec3(0.0);

    if (postMergeGain > 1.0001) {
        highp float k = postMergeGain - 1.0;
        // Apply per-channel so a coloured highlight doesn't shift hue.
        merged = merged * postMergeGain / (1.0 + k * merged);
    }
    fragColor = vec4(merged, 1.0);
}
