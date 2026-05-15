#version 300 es

precision mediump float;

uniform sampler2D bilateral;
uniform sampler2D intermediate;

uniform sampler2D hist;
uniform vec2 histOffset;
uniform vec2 histMinMax;  // min and max luminance for normalization
uniform float histFactor;
uniform float gamma;
uniform int useEdgeAware;

uniform sampler2D noiseTex;

// Out (must be vec4 because RGB16F is not color-renderable in GLES 3.0)
out vec4 processed;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);

    // Read and decode HDR xyY: z_decoded = z * w
    vec4 intermediateEnc = texelFetch(intermediate, xy, 0);
    float invScale1 = max(intermediateEnc.w, 0.001);
    vec3 intermediateValXyz = vec3(intermediateEnc.xy, intermediateEnc.z / invScale1);
    
    vec4 bilateralEnc = useEdgeAware > 0 ? texelFetch(bilateral, xy, 0) : intermediateEnc;
    float invScale2 = max(bilateralEnc.w, 0.001);
    vec3 bilateralValXyz = vec3(bilateralEnc.xy, bilateralEnc.z / invScale2);

    float intermediateVal = intermediateValXyz.z;
    float bilateralVal = bilateralValXyz.z;

    float z = intermediateVal;
    if (intermediateVal > 0.0001f) {
        // When histFactor is 0, skip histogram equalization entirely to prevent any processing
        if (histFactor > 0.0001f) {
            // STEP 1: Normalize/stretch the input value to use full [0,1] range
            // This maps the actual min/max values to 0/1, allowing histogram equalization
            // to work on the full dynamic range and lift dark subjects
            float minLum = histMinMax.x;
            float maxLum = histMinMax.y;
            float lumRange = maxLum - minLum;
            
            // Normalize: map [minLum, maxLum] to [0, 1]
            // BUT preserve highlights above p99 (maxLum) by storing the excess
            float normalizedVal = (intermediateVal - minLum) / max(lumRange, 0.001f);
            
            // Store excess above 1.0 (values above p99) to preserve highlights
            // When baseline exposure is high, values can exceed p99, and clamping loses highlight detail
            float highlightExcess = max(0.0f, normalizedVal - 1.0f);
            float normalizedValClamped = clamp(normalizedVal, 0.0f, 1.0f);
            
            // STEP 2: Apply histogram equalization to the normalized value
            // (Original Reflectance * Original Luminosity)
            // * (Corrected Luminosity / Original Luminosity)
            float texCoord = histOffset.x + histOffset.y * normalizedValClamped;
            float correctLuminanceHistEq = texture(hist, vec2(texCoord, 0.5f)).x;

            if (useEdgeAware > 0) {
                // Edge-aware histogram equalization:
                // Use bilateral filter value to determine how much to apply histogram equalization
                // Near edges (where bilateral differs from original), apply less equalization
                // In smooth areas (where bilateral matches original), apply full equalization
                float bilateralNormalized = clamp((bilateralVal - minLum) / max(lumRange, 0.001f), 0.0f, 1.0f);
                float edgeStrength = abs(normalizedValClamped - bilateralNormalized) / max(normalizedValClamped, 0.001f);
                float edgeAwareFactor = 1.0f - min(edgeStrength * 2.0f, 0.5f);  // Reduce by up to 50% near edges
                
                // Apply histogram equalization to normalized value
                float normalizedResult = normalizedValClamped * pow(correctLuminanceHistEq / max(normalizedValClamped, 0.001f), histFactor * edgeAwareFactor);
                
                // STEP 3: Map back from normalized [0,1] to original range [minLum, maxLum]
                // Then add back the excess above p99 to preserve highlights
                z = normalizedResult * lumRange + minLum + highlightExcess * lumRange;
            } else {
                // Standard histogram equalization
                // Apply histogram equalization to normalized value
                float normalizedResult = normalizedValClamped * pow(correctLuminanceHistEq / max(normalizedValClamped, 0.001f), histFactor);
                
                // Map back from normalized [0,1] to original range [minLum, maxLum]
                // Then add back the excess above p99 to preserve highlights
                z = normalizedResult * lumRange + minLum + highlightExcess * lumRange;
            }
        }
        // Only apply gamma if it's not 1.0 (when histFactor = 0, gamma should be 1.0)
        if (abs(gamma - 1.0) > 0.001f) {
            z = pow(z, gamma);
        }
    }

    processed.xy = intermediateValXyz.xy;
    // Only clamp negatives - preserve HDR values > 1.0 for highlight detail
    // HDR compression happens later in the pipeline, so we need to preserve the full dynamic range here
    z = max(z, 0.f);
    
    // Re-encode HDR luminance: store Y/scale in z, scale in w
    float hdrScale = max(z, 1.0);
    processed.z = z / hdrScale;
    processed.w = 1.0 / hdrScale;
}
