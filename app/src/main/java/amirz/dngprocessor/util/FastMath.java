package amirz.dngprocessor.util;

/**
 * Fast math approximations using lookup tables for performance-critical operations.
 * These are used in HDR capture and gain map generation where millions of operations
 * are performed per image.
 */
public class FastMath {
    // Precomputed log2(17) constant
    private static final float LOG2_17 = (float) (Math.log(17) / Math.log(2));
    private static final float INV_LOG2_17 = 1.0f / LOG2_17;
    
    // Lookup table for log2(1 + x) where x in [0, 16]
    // We encode: log2(1 + x) / log2(17) for x in [0, 16]
    // This maps [0, 16] → [0, 1]
    private static final int LOG2_LUT_SIZE = 256;
    private static final float[] LOG2_LUT = new float[LOG2_LUT_SIZE];
    
    // Lookup table for x^2.4 where x in [0, 1]
    // Used for sRGB to linear conversion
    private static final int POW24_LUT_SIZE = 256;
    private static final float[] POW24_LUT = new float[POW24_LUT_SIZE];
    
    static {
        // Precompute log2(1 + x) / log2(17) for x in [0, 16]
        for (int i = 0; i < LOG2_LUT_SIZE; i++) {
            float x = (float) i / (LOG2_LUT_SIZE - 1) * 16.0f;
            LOG2_LUT[i] = (float) (Math.log(1.0 + x) / Math.log(2)) * INV_LOG2_17;
        }
        
        // Precompute x^2.4 for x in [0, 1]
        for (int i = 0; i < POW24_LUT_SIZE; i++) {
            float x = (float) i / (POW24_LUT_SIZE - 1);
            POW24_LUT[i] = (float) Math.pow(x, 2.4);
        }
    }
    
    /**
     * Fast approximation of log2(1 + x) / log2(17) for x in [0, 16].
     * This is used for logarithmic encoding of HDR values.
     * Maps [0, 16] → [0, 1]
     * 
     * @param x Input value in [0, 16]
     * @return Encoded value in [0, 1]
     */
    public static float fastLog2Encode(float x) {
        if (x <= 0.0f) {
            return 0.0f;
        }
        if (x >= 16.0f) {
            return 1.0f;
        }
        
        // Use lookup table with linear interpolation
        float index = x / 16.0f * (LOG2_LUT_SIZE - 1);
        int i = (int) index;
        if (i >= LOG2_LUT_SIZE - 1) {
            return LOG2_LUT[LOG2_LUT_SIZE - 1];
        }
        float t = index - i;
        return LOG2_LUT[i] * (1.0f - t) + LOG2_LUT[i + 1] * t;
    }
    
    /**
     * Fast approximation of x^2.4 for x in [0, 1].
     * Used for sRGB to linear RGB conversion.
     * 
     * @param x Input value in [0, 1]
     * @return x^2.4
     */
    public static float fastPow24(float x) {
        if (x <= 0.0f) {
            return 0.0f;
        }
        if (x >= 1.0f) {
            return 1.0f;
        }
        
        // Use lookup table with linear interpolation
        float index = x * (POW24_LUT_SIZE - 1);
        int i = (int) index;
        if (i >= POW24_LUT_SIZE - 1) {
            return POW24_LUT[POW24_LUT_SIZE - 1];
        }
        float t = index - i;
        return POW24_LUT[i] * (1.0f - t) + POW24_LUT[i + 1] * t;
    }
    
    /**
     * Gets the precomputed log2(17) constant.
     */
    public static float getLog2_17() {
        return LOG2_17;
    }
}

