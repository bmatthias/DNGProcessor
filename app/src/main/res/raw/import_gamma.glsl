/* sRGB Gamma Function - highp precision for banding prevention */
/* Use highp precision and exp2/log2 for better precision than pow() */
highp float gammaEncode(highp float x) {
    if (x <= 0.0031308f) {
        return x * 12.92f;
    } else {
        /* pow(x, 0.4166667) = pow(x, 1/2.4) = exp2(log2(x) / 2.4) */
        /* This is more precise than pow() for smooth gradients */
        highp float log2x = log2(max(x, 1e-10));
        return 1.055f * exp2(log2x * 0.4166667f) - 0.055f;
    }
}

/* Inverse - highp precision for banding prevention */
highp float gammaDecode(highp float x) {
    if (x <= 0.0404500f) {
        return x * 0.0773994f;
    } else {
        /* pow(0.9478673 * (x + 0.055), 2.4) = exp2(log2(0.9478673 * (x + 0.055)) * 2.4) */
        /* This is more precise than pow() for smooth gradients */
        highp float arg = 0.9478673f * (x + 0.055f);
        highp float log2arg = log2(max(arg, 1e-10));
        return exp2(log2arg * 2.4f);
    }
}
