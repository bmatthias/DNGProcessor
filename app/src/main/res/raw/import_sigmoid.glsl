float sigmoid(float val, float transfer) {
    if (val > transfer) {
        /* This variable maps the cut off point in the linear curve to the sigmoid */
        float a = log((1.f + transfer) / (1.f - transfer)) / transfer;

        /* Transform val using the sigmoid curve */
        val = 2.f / (1.f + exp(-a * val)) - 1.f;
    }
    return val;
}

float calculateSigmoidalContrastFromGamma(float gamma) {
    if (gamma <= 1.0) {
        return 1.0;
    }
    float expTerm = 4.0 * gamma - 4.0;
    float powTerm = pow(0.5, expTerm);
    float powTerm2 = pow(0.5, 1.0 - 1.0 / gamma);
    float contrastStrength = 1.8 * (1.0 - powTerm) * gamma * powTerm2;
    return clamp(contrastStrength, 1.0, 6.0);
}
