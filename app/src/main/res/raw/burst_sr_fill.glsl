#version 300 es

// DEBUG shader: writes a known deterministic pattern into the 2× output
// texture so we can verify whether the readback / DNG-writer path mangles
// the data. The pattern is:
//
//   R-parity pixels (output (X,Y) with (X&1, Y&1) == (0,0)) :=  X / outW
//   G1-parity pixels                            (1,0)      :=  Y / outH
//   G2-parity pixels                            (0,1)      :=  (X / outW + Y / outH) * 0.5
//   B-parity pixels                             (1,1)      :=  1.0 - X / outW
//
// In the resulting DNG (viewed as Bayer), each CFA channel encodes a
// different smooth gradient. If the readback path is intact, every row in
// the DNG must show a clean linear ramp; if it isn't, the corruption
// pattern will be visually obvious and reveals the kind of bug
// (row-stride mis-step, mis-sized buffer, byte-swapped halves, etc).

precision highp float;
precision highp int;
precision highp sampler2D;

uniform highp int outWidth;
uniform highp int outHeight;

out highp float fragColor;

void main() {
    highp ivec2 outPos = ivec2(gl_FragCoord.xy);
    highp float xN = float(outPos.x) / float(outWidth);
    highp float yN = float(outPos.y) / float(outHeight);
    highp int px = outPos.x & 1;
    highp int py = outPos.y & 1;
    highp float v;
    if      (px == 0 && py == 0) v = xN;                 // R
    else if (px == 1 && py == 0) v = yN;                 // G1
    else if (px == 0 && py == 1) v = (xN + yN) * 0.5;    // G2
    else                          v = 1.0 - xN;          // B
    fragColor = clamp(v, 0.0, 1.0);
}
