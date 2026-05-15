package amirz.dngprocessor.math;

public class Histogram {
    // Increased from 256 to 1024 to reduce quantization banding
    // 1024 bins provides 4x better precision for histogram equalization
    private static final int HIST_BINS = 1024;
    private static final double EPSILON = 0.01;
    private static final float LINEARIZE_PERCEPTION = 2.4f;

    public final float[] sigma = new float[3];
    public final float[] hist;
    public final float gamma;
    public final float logAvgLuminance;
    public final float minLuminance;
    public final float maxLuminance;
    public final float p01Luminance;  // 1st percentile (robust to outliers)
    public final float p99Luminance;  // 99th percentile (robust to outliers)

    public Histogram(float[] f, int whPixels) {
        int[] histv = new int[HIST_BINS];

        double logTotalLuminance = 0d;
        float minLum = Float.MAX_VALUE;
        float maxLum = 0.0f;
        
        // Loop over all values
        for (int i = 0; i < f.length; i += 4) {
            for (int j = 0; j < 3; j++) {
                sigma[j] += f[i + j];
            }

            float luma = f[i + 3];
            if (luma > 0.0001f) {  // Ignore pure black pixels
                if (luma < minLum) minLum = luma;
                if (luma > maxLum) maxLum = luma;
            }

            int bin = (int) (luma * HIST_BINS);
            if (bin < 0) bin = 0;
            if (bin >= HIST_BINS) bin = HIST_BINS - 1;
            histv[bin]++;

            logTotalLuminance += Math.log(luma + EPSILON);
        }
        
        // Ensure we have valid min/max (fallback if all pixels are black)
        if (minLum >= maxLum || minLum == Float.MAX_VALUE) {
            minLum = 0.0f;
            maxLum = 1.0f;
        }
        
        minLuminance = minLum;
        maxLuminance = maxLum;

        logAvgLuminance = (float) Math.exp(logTotalLuminance * 4 / f.length);
        for (int j = 0; j < 3; j++) {
            sigma[j] /= whPixels;
        }

        // Calculate percentiles from raw histogram before processing
        // This provides robust normalization that ignores outliers and prevents
        // extreme contrast when baseline exposure is high
        float[] tempCumulative = buildCumulativeHist(histv);
        float p01 = findPercentile(tempCumulative, 0.01f);
        float p99 = findPercentile(tempCumulative, 0.99f);
        
        // Ensure percentiles are valid
        if (p01 >= p99 || p01 < 0.0f || p99 > 1.0f) {
            p01 = 0.0f;
            p99 = 1.0f;
        }
        
        p01Luminance = p01;
        p99Luminance = p99;

        //limitHighlightContrast(histv, f.length / 4);
        float[] cumulativeHist = buildCumulativeHist(histv);

        // Find gamma: Inverse of the average exponent.
        gamma = findGamma(cumulativeHist);

        // Compensate for the gamma being applied first.
        for (int i = 1; i <= HIST_BINS; i++) {
            double id = (double) i / HIST_BINS;
            cumulativeHist[i] *= id / Math.pow(id, gamma);
        }

        // Limit contrast and banding.
        float[] tmp = new float[cumulativeHist.length];
        for (int i = cumulativeHist.length - 1; i > 0; i--) {
            System.arraycopy(cumulativeHist, 0, tmp, 0, i);
            for (int j = i; j < cumulativeHist.length - 1; j++) {
                tmp[j] = (cumulativeHist[j - 1] + cumulativeHist[j + 1]) * 0.5f;
            }
            tmp[tmp.length - 1] = cumulativeHist[cumulativeHist.length - 1];

            float[] swp = tmp;
            tmp = cumulativeHist;
            cumulativeHist = swp;
        }

        // Crush shadows.
        crushShadows(cumulativeHist);

        hist = cumulativeHist;
    }

    private static float[] buildCumulativeHist(int[] hist) {
        float[] cumulativeHist = new float[HIST_BINS + 1];
        for (int i = 1; i < cumulativeHist.length; i++) {
            cumulativeHist[i] = cumulativeHist[i - 1] + hist[i - 1];
        }
        float max = cumulativeHist[HIST_BINS];
        for (int i = 0; i < cumulativeHist.length; i++) {
            cumulativeHist[i] /= max;
        }
        return cumulativeHist;
    }

    /**
     * Find the luminance value at a given percentile using the cumulative histogram.
     * 
     * @param cumulativeHist Cumulative histogram (values in [0,1])
     * @param percentile Target percentile (e.g., 0.01 for 1st percentile, 0.99 for 99th)
     * @return Normalized value (0-1) at the percentile
     */
    private static float findPercentile(float[] cumulativeHist, float percentile) {
        int numBins = cumulativeHist.length - 1;
        for (int i = 0; i <= numBins; i++) {
            if (cumulativeHist[i] >= percentile) {
                return (float) i / numBins;
            }
        }
        return 1.0f;
    }

    private static float findGamma(float[] cumulativeHist) {
        float sumExponent = 0.f;
        int exponentCounted = 0;
        for (int i = 0; i <= HIST_BINS; i++) {
            float val = cumulativeHist[i];
            if (val > 0.001f) {
                // Which power of the input is the output.
                double exponent = Math.log(cumulativeHist[i]) / Math.log((double) i / HIST_BINS);
                if (exponent > 0f && exponent < 10f) {
                    sumExponent += exponent;
                    exponentCounted++;
                }
            }
        }
        return LINEARIZE_PERCEPTION * sumExponent / exponentCounted;
    }

    private static void crushShadows(float[] cumulativeHist) {
        for (int i = 0; i < cumulativeHist.length; i++) {
            float og = (float) i / cumulativeHist.length;
            float a = Math.min(1f, og / 0.02f);
            if (a == 1f) {
                break;
            }
            cumulativeHist[i] *= Math.pow(a, 3.f);
        }
    }

    // Shift highlights down
    private static void limitHighlightContrast(int[] clippedHist, int valueCount) {
        for (int i = clippedHist.length - 1; i >= clippedHist.length / 4; i--) {
            int limit = 4 * valueCount / i;

            if (clippedHist[i] > limit) {
                int removed = clippedHist[i] - limit;
                clippedHist[i] = limit;

                for (int j = i - 1; j >= 0; j--) {
                    int space = limit - clippedHist[j];
                    if (space > 0) {
                        int allocate = Math.min(removed, space);
                        clippedHist[j] += allocate;
                        removed -= allocate;
                        if (removed == 0) {
                            break;
                        }
                    }
                }
            }
        }
    }
}
