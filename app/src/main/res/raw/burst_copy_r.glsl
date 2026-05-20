#version 300 es

// Single-channel (R16F) passthrough copy. Used by BurstFrequencyMerge's
// ENABLE_FREQ_MERGE=false bypass mode to populate mMergedTex with the
// preprocessed reference frame instead of running the actual merge — lets
// us A/B-test whether artefacts in the side-car DNG are introduced by the
// merge stage or by something downstream (SR upsample, DNG export…).

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp sampler2D srcTex;

out highp float fragColor;

void main() {
    fragColor = texelFetch(srcTex, ivec2(gl_FragCoord.xy), 0).r;
}
