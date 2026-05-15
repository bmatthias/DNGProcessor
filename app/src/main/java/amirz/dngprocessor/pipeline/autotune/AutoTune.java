package amirz.dngprocessor.pipeline.autotune;

import android.util.Log;

import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.pipeline.intermediate.Analysis;

/**
 * Auto-tuning system for Local Laplacian and Tone Equalizer.
 * 
 * This class analyzes image histograms from the Analysis stage and
 * automatically computes optimal parameters for:
 * 
 * 1. Local Laplacian:
 *    - shadows: Lift factor based on shadow density
 *    - highlights: Compression factor based on highlight clipping
 *    - clarity: Local contrast based on overall contrast
 *    - sigma: Transition width (usually kept at default)
 * 
 * 2. Tone Equalizer:
 *    - EV band adjustments based on histogram distribution
 *    - Sparse bands get lifted, overrepresented bands get compressed
 * 
 * The algorithm is inspired by darktable's auto-tuners but adapted
 * for our pipeline's specific requirements.
 */
public class AutoTune {
    private static final String TAG = "AutoTune";
    
    // Histogram analysis constants
    private static final int HIST_BINS = 1024;  // Must match Histogram.java
    
    // EV band centers (matching darktable's toneequal.c)
    private static final float[] EV_CENTERS = {
        -8.0f, -7.0f, -6.0f, -5.0f, -4.0f, -3.0f, -2.0f, -1.0f, 0.0f
    };
    
    // Default smoothing for Tone Equalizer
    private static final float TE_SMOOTHING = 1.414f;  // sqrt(2)
    
    /**
     * Auto-tune parameters based on image analysis.
     * 
     * @param process ProcessParams to update (modified in place)
     * @param analysis Analysis stage with histogram data
     * @param autoTuneLocalLaplacian Whether to auto-tune Local Laplacian
     * @param autoTuneToneEqualizer Whether to auto-tune Tone Equalizer
     */
    public static void autoTuneFromAnalysis(ProcessParams process, Analysis analysis,
                                           boolean autoTuneLocalLaplacian,
                                           boolean autoTuneToneEqualizer) {
        if (analysis == null) {
            Log.w(TAG, "Analysis is null, skipping auto-tune");
            return;
        }
        
        float[] hist = analysis.getHist();
        if (hist == null || hist.length == 0) {
            Log.w(TAG, "Histogram is null or empty, skipping auto-tune");
            return;
        }
        
        float logAvgLum = analysis.getLogAvgLuminance();
        float gamma = analysis.getGamma();
        
        Log.d(TAG, String.format("Auto-tuning: logAvgLum=%.4f, gamma=%.2f, histBins=%d",
                logAvgLum, gamma, hist.length));
        
        if (autoTuneLocalLaplacian && process.localLaplacianEnabled) {
            autoTuneLocalLaplacian(process, hist, logAvgLum, gamma);
        }
        
        if (autoTuneToneEqualizer && process.toneEqualizerEnabled) {
            autoTuneToneEqualizer(process, hist, logAvgLum, gamma);
        }
    }
    
    // Default values for Local Laplacian (from config.xml)
    private static final float LL_DEFAULT_SHADOWS = 1.0f;
    private static final float LL_DEFAULT_HIGHLIGHTS = 1.0f;
    private static final float LL_DEFAULT_CLARITY = 0.1f;
    private static final float LL_DEFAULT_SIGMA = 0.2f;
    
    /**
     * Auto-tune Local Laplacian parameters based on histogram analysis.
     * 
     * Strategy:
     * - Measure shadow density (bottom 10% of histogram)
     * - Measure highlight density (top 10% of histogram)
     * - Compute dynamic range
     * - Set shadows/highlights/clarity accordingly
     * 
     * Manual sliders act as adjustments:
     * - shadows/highlights: multiplicative (centered at 1.0)
     * - clarity/sigma: additive (centered at defaults)
     */
    private static void autoTuneLocalLaplacian(ProcessParams process, float[] hist,
                                               float logAvgLum, float gamma) {
        // Store manual values before auto-tuning
        float manualShadows = process.localLaplacianShadows;
        float manualHighlights = process.localLaplacianHighlights;
        float manualClarity = process.localLaplacianClarity;
        float manualSigma = process.localLaplacianSigma;
        int numBins = hist.length;
        
        // Compute percentiles (cumulative histogram already)
        float p01 = findValueAtPercentile(hist, 0.01f);  // 1st percentile
        float p05 = findValueAtPercentile(hist, 0.05f);  // 5th percentile
        float p10 = findValueAtPercentile(hist, 0.10f);  // 10th percentile
        float p50 = findValueAtPercentile(hist, 0.50f);  // 50th percentile (median)
        float p90 = findValueAtPercentile(hist, 0.90f);  // 90th percentile
        float p95 = findValueAtPercentile(hist, 0.95f);  // 95th percentile
        float p99 = findValueAtPercentile(hist, 0.99f);  // 99th percentile
        
        Log.d(TAG, String.format("LL Percentiles: p01=%.3f, p10=%.3f, p50=%.3f, p90=%.3f, p99=%.3f",
                p01, p10, p50, p90, p99));
        
        // Compute shadow density: how much data is in the bottom 10%
        float shadowDensity = hist[(int)((numBins - 1) * 0.10f)];
        
        // Compute highlight density: how much data is in the top 10%
        float highlightDensity = 1.0f - hist[(int)((numBins - 1) * 0.90f)];
        
        // Compute dynamic range (ratio of percentiles)
        float dynamicRange = (p99 + 0.001f) / (p01 + 0.001f);
        float evRange = (float) (Math.log(dynamicRange) / Math.log(2));
        
        Log.d(TAG, String.format("LL Analysis: shadowDensity=%.2f, highlightDensity=%.2f, DR=%.1f (%.1f EV)",
                shadowDensity, highlightDensity, dynamicRange, evRange));
        
        // Auto-tune shadows (bidirectional)
        // In darktable: shadows < 1.0 = lift shadows (fill light), shadows > 1.0 = increase shadow contrast
        // Strategy: If shadows are too dark (low percentile), lift them (< 1.0)
        //           If shadows have good detail but need contrast, increase contrast (> 1.0)
        float shadows;
        if (p10 < 0.05f && shadowDensity > 0.2f) {
            // Very dark shadows with significant density -> lift (fill light effect)
            shadows = 0.6f;  // Strong shadow lift
        } else if (p10 < 0.1f && shadowDensity > 0.15f) {
            // Dark shadows -> moderate lift
            shadows = 0.75f;
        } else if (p10 < 0.15f && shadowDensity > 0.1f) {
            // Somewhat dark shadows -> subtle lift
            shadows = 0.85f;
        } else if (p10 > 0.3f && shadowDensity > 0.2f) {
            // Shadows are bright but need contrast -> increase contrast
            shadows = 1.3f;
        } else if (p10 > 0.25f && shadowDensity > 0.15f) {
            // Moderately bright shadows -> moderate contrast boost
            shadows = 1.15f;
        } else {
            shadows = 1.0f;  // Neutral
        }
        
        // Also consider median luminance - if median is very low, prioritize lifting
        if (p50 < 0.2f && p10 < 0.1f) {
            shadows = Math.min(shadows, 0.7f);  // Force lift for very dark images
        }
        
        // Auto-tune highlights (bidirectional)
        // In darktable: highlights < 1.0 = compress, highlights > 1.0 = expand
        // Strategy: If highlights are blown (high percentile), compress (< 1.0)
        //           If highlights are too dark, expand (> 1.0)
        float highlights;
        if (p90 > 0.95f && highlightDensity > 0.2f) {
            // Blown highlights -> strong compression
            highlights = 0.6f;
        } else if (p90 > 0.9f && highlightDensity > 0.15f) {
            // Very bright highlights -> moderate compression
            highlights = 0.75f;
        } else if (p90 > 0.85f && highlightDensity > 0.1f) {
            // Bright highlights -> subtle compression
            highlights = 0.9f;
        } else if (p90 < 0.6f && highlightDensity < 0.05f) {
            // Highlights are too dark -> expand them
            highlights = 1.3f;
        } else if (p90 < 0.7f && highlightDensity < 0.08f) {
            // Moderately dark highlights -> moderate expansion
            highlights = 1.15f;
        } else {
            highlights = 1.0f;  // Neutral
        }
        
        // Auto-tune clarity based on scene characteristics
        // High contrast scenes (high DR) -> reduce clarity to avoid over-processing
        // Low contrast/flat scenes (low DR) -> increase clarity to add pop
        // Also consider contrast in midtones (p50 vs p10/p90 spread)
        float midtoneSpread = (p90 - p10) / (p50 + 0.001f);  // Relative spread
        float clarity;
        if (evRange > 12.0f) {
            // Very high DR: reduce clarity to avoid halos
            clarity = 0.05f;
        } else if (evRange > 8.0f) {
            // High DR: subtle clarity
            clarity = 0.1f;
        } else if (evRange < 3.0f || midtoneSpread < 1.5f) {
            // Low DR or flat scene: add clarity for pop
            clarity = 0.25f;
        } else if (evRange < 5.0f) {
            // Medium-low DR: moderate clarity
            clarity = 0.2f;
        } else {
            // Medium DR: balanced clarity
            clarity = 0.15f;
        }
        
        // Sigma: keep at default for most cases
        // Could be tuned based on image characteristics if needed
        float sigma = LL_DEFAULT_SIGMA;
        
        // Apply manual adjustments on top of auto-tuned base values
        // Shadows/Highlights: multiplicative (centered at 1.0)
        // If manual is 1.0 (default), result = autoTuned
        // If manual is 1.2, result = autoTuned * 1.2 (20% boost)
        process.localLaplacianShadows = shadows * manualShadows;
        process.localLaplacianHighlights = highlights * manualHighlights;
        
        // Clarity/Sigma: additive (centered at defaults)
        // If manual is at default, result = autoTuned
        // If manual is 0.2 (default 0.1), result = autoTuned + 0.1 (additive adjustment)
        process.localLaplacianClarity = clarity + (manualClarity - LL_DEFAULT_CLARITY);
        process.localLaplacianSigma = sigma + (manualSigma - LL_DEFAULT_SIGMA);
        
        Log.d(TAG, String.format("LL Auto-tuned base: shadows=%.2f, highlights=%.2f, clarity=%.2f, sigma=%.2f",
                shadows, highlights, clarity, sigma));
        Log.d(TAG, String.format("LL Manual adjustments: shadows=%.2f, highlights=%.2f, clarity=%.2f, sigma=%.2f",
                manualShadows, manualHighlights, manualClarity, manualSigma));
        Log.d(TAG, String.format("LL Final values: shadows=%.2f, highlights=%.2f, clarity=%.2f, sigma=%.2f",
                process.localLaplacianShadows, process.localLaplacianHighlights,
                process.localLaplacianClarity, process.localLaplacianSigma));
    }
    
    /**
     * Auto-tune Tone Equalizer parameters based on histogram analysis.
     * 
     * Strategy:
     * - Convert histogram to EV (log2) space
     * - For each EV band, compute density
     * - Sparse bands get positive adjustment (lift)
     * - Overrepresented bands get negative adjustment (compress)
     * - Target: more uniform distribution across EV range
     * 
     * Manual sliders act as additive adjustments (centered at 0.0):
     * - If manual is 0.0 (default), result = autoTuned
     * - If manual is +0.5, result = autoTuned + 0.5 (additional lift)
     */
    private static void autoTuneToneEqualizer(ProcessParams process, float[] hist,
                                              float logAvgLum, float gamma) {
        // Store manual values before auto-tuning
        float manualBlacks = process.toneEqBlacks;
        float manualDeepShadows = process.toneEqDeepShadows;
        float manualShadows = process.toneEqShadows;
        float manualLightShadows = process.toneEqLightShadows;
        float manualMidtones = process.toneEqMidtones;
        float manualDarkHighlights = process.toneEqDarkHighlights;
        float manualHighlights = process.toneEqHighlights;
        float manualWhites = process.toneEqWhites;
        float manualSpeculars = process.toneEqSpeculars;
        int numBins = hist.length;
        
        // Compute percentiles for scene analysis
        float p01 = findValueAtPercentile(hist, 0.01f);
        float p10 = findValueAtPercentile(hist, 0.10f);
        float p50 = findValueAtPercentile(hist, 0.50f);
        float p90 = findValueAtPercentile(hist, 0.90f);
        float p99 = findValueAtPercentile(hist, 0.99f);
        
        // Compute EV band densities
        // Note: histogram is cumulative [0,1], convert to per-band density
        float[] evDensity = new float[9];
        
        for (int i = 0; i < 9; i++) {
            float evCenter = EV_CENTERS[i];  // -8 to 0
            
            // Convert EV to normalized luminance [0,1]
            // EV = log2(L), so L = 2^EV
            // For -8 to 0 range, this gives L from 1/256 to 1
            float lumCenter = (float) Math.pow(2.0, evCenter);
            
            // Find histogram bin for this EV
            // Need to account for gamma since histogram is in gamma space
            float gammaLum = (float) Math.pow(lumCenter, 1.0 / 2.2);
            int binCenter = (int) (gammaLum * (numBins - 1));
            
            // Compute density in a band around this center (±0.5 EV)
            float lumLow = (float) Math.pow(2.0, evCenter - 0.5);
            float lumHigh = (float) Math.pow(2.0, evCenter + 0.5);
            float gammaLow = (float) Math.pow(Math.max(0.001, lumLow), 1.0 / 2.2);
            float gammaHigh = (float) Math.pow(Math.min(1.0, lumHigh), 1.0 / 2.2);
            
            int binLow = Math.max(0, (int) (gammaLow * (numBins - 1)));
            int binHigh = Math.min(numBins - 1, (int) (gammaHigh * (numBins - 1)));
            
            // Density is difference in cumulative histogram
            evDensity[i] = hist[binHigh] - hist[binLow];
        }
        
        Log.d(TAG, String.format("TE EV densities: [%.3f, %.3f, %.3f, %.3f, %.3f, %.3f, %.3f, %.3f, %.3f]",
                evDensity[0], evDensity[1], evDensity[2], evDensity[3], evDensity[4],
                evDensity[5], evDensity[6], evDensity[7], evDensity[8]));
        
        // Target: balanced distribution, but adapt to scene type
        // For high DR scenes: target more uniform distribution
        // For low DR scenes: preserve natural distribution, just enhance
        float targetDensity = 0.125f;  // Base target (uniform across 8 bands)
        
        // Adjust target based on scene characteristics
        // If scene has natural contrast, don't force uniformity
        float sceneContrast = (p99 - p01) / (p50 + 0.001f);
        if (sceneContrast > 10.0f) {
            // High contrast scene: allow more variation, target lower
            targetDensity = 0.08f;
        } else if (sceneContrast < 3.0f) {
            // Low contrast/flat scene: target higher density to add contrast
            targetDensity = 0.15f;
        }
        
        // Compute adjustments (bidirectional: lift sparse, compress dense)
        float[] adjustments = new float[9];
        
        for (int i = 0; i < 9; i++) {
            float density = evDensity[i];
            
            if (density < 0.001f) {
                // Empty band: lift if it's a shadow band (likely underexposed)
                // Don't lift highlight bands (might be intentional)
                if (i <= 3) {  // Shadow bands
                    adjustments[i] = 0.8f;  // Lift empty shadow bands
                } else {
                    adjustments[i] = 0.0f;  // Don't adjust empty highlight bands
                }
            } else if (density < targetDensity * 0.3f) {
                // Very sparse: lift significantly
                if (i <= 3) {  // Shadow bands
                    adjustments[i] = 1.2f;  // Very strong lift for sparse shadows
                } else if (i >= 6) {  // Highlight bands
                    adjustments[i] = 0.3f;  // Moderate lift for sparse highlights (avoid blowing)
                } else {
                    adjustments[i] = 0.6f;  // Moderate lift for midtones
                }
            } else if (density < targetDensity * 0.6f) {
                // Sparse: lift moderately
                if (i <= 3) {  // Shadow bands
                    adjustments[i] = 0.8f;  // Strong lift for sparse shadows
                } else {
                    adjustments[i] = 0.4f;  // Moderate lift for other bands
                }
            } else if (density < targetDensity) {
                // Somewhat sparse: subtle lift
                adjustments[i] = 0.3f;
            } else if (density > targetDensity * 4.0f) {
                // Very overrepresented: compress significantly
                adjustments[i] = -0.5f;
            } else if (density > targetDensity * 2.5f) {
                // Overrepresented: compress moderately
                adjustments[i] = -0.3f;
            } else if (density > targetDensity * 1.5f) {
                // Somewhat overrepresented: subtle compression
                adjustments[i] = -0.15f;
            } else {
                // Near target: no adjustment
                adjustments[i] = 0.0f;
            }
        }
        
        // Special handling for extremes based on scene analysis
        // Blacks (-8 EV): lift if very dark scene, but don't over-lift if scene is bright
        if (p10 < 0.1f && evDensity[0] < targetDensity * 0.5f) {
            // Dark scene with sparse blacks -> strong lift
            adjustments[0] = Math.max(adjustments[0], 1.0f);
        } else if (p10 > 0.2f) {
            // Bright scene -> cap black lift to avoid unnatural look
            adjustments[0] = Math.min(adjustments[0], 0.5f);
        }
        
        // Shadow bands: prioritize lifting in dark scenes
        for (int i = 1; i <= 3; i++) {
            if (p10 < 0.15f && evDensity[i] < targetDensity * 0.5f) {
                // Dark scene with sparse shadows -> very strong lift
                adjustments[i] = Math.max(adjustments[i], 1.0f);
            }
        }
        
        // Highlight bands: compress if blown, expand if too dark
        for (int i = 5; i <= 7; i++) {  // Dark highlights, highlights, whites
            if (p90 > 0.9f && evDensity[i] > targetDensity * 2.0f) {
                // Blown highlights -> compress
                adjustments[i] = Math.min(adjustments[i], -0.4f);
            } else if (p90 < 0.7f && evDensity[i] < targetDensity * 0.5f) {
                // Dark highlights -> expand
                adjustments[i] = Math.max(adjustments[i], 0.5f);
            }
        }
        
        // Speculars (0 EV): be conservative
        if (p99 > 0.95f) {
            // Very bright scene -> don't lift speculars (might blow)
            adjustments[8] = Math.min(adjustments[8], 0.2f);
        } else {
            adjustments[8] = Math.min(adjustments[8], 0.4f);
        }
        
        // Apply smoothing to prevent adjacent bands from fighting
        float[] smoothed = new float[9];
        for (int i = 0; i < 9; i++) {
            float sum = adjustments[i] * 2.0f;
            float weight = 2.0f;
            if (i > 0) {
                sum += adjustments[i-1];
                weight += 1.0f;
            }
            if (i < 8) {
                sum += adjustments[i+1];
                weight += 1.0f;
            }
            smoothed[i] = sum / weight;
        }
        
        // Apply manual adjustments on top of auto-tuned base values
        // All EV bands: additive (centered at 0.0)
        // If manual is 0.0 (default), result = autoTuned
        // If manual is +0.5, result = autoTuned + 0.5 (additional adjustment)
        process.toneEqBlacks = smoothed[0] + manualBlacks;
        process.toneEqDeepShadows = smoothed[1] + manualDeepShadows;
        process.toneEqShadows = smoothed[2] + manualShadows;
        process.toneEqLightShadows = smoothed[3] + manualLightShadows;
        process.toneEqMidtones = smoothed[4] + manualMidtones;
        process.toneEqDarkHighlights = smoothed[5] + manualDarkHighlights;
        process.toneEqHighlights = smoothed[6] + manualHighlights;
        process.toneEqWhites = smoothed[7] + manualWhites;
        process.toneEqSpeculars = smoothed[8] + manualSpeculars;
        
        Log.d(TAG, String.format("TE Auto-tuned base: [%.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f]",
                smoothed[0], smoothed[1], smoothed[2], smoothed[3], smoothed[4],
                smoothed[5], smoothed[6], smoothed[7], smoothed[8]));
        Log.d(TAG, String.format("TE Manual adjustments: [%.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f]",
                manualBlacks, manualDeepShadows, manualShadows, manualLightShadows, manualMidtones,
                manualDarkHighlights, manualHighlights, manualWhites, manualSpeculars));
        Log.d(TAG, String.format("TE Final values: [%.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f]",
                process.toneEqBlacks, process.toneEqDeepShadows, process.toneEqShadows,
                process.toneEqLightShadows, process.toneEqMidtones, process.toneEqDarkHighlights,
                process.toneEqHighlights, process.toneEqWhites, process.toneEqSpeculars));
    }
    
    /**
     * Find the histogram value at a given percentile.
     * 
     * @param cumulativeHist Cumulative histogram (values in [0,1])
     * @param percentile Target percentile (e.g., 0.05 for 5th percentile)
     * @return Normalized value (0-1) at the percentile
     */
    private static float findValueAtPercentile(float[] cumulativeHist, float percentile) {
        int numBins = cumulativeHist.length;
        for (int i = 0; i < numBins; i++) {
            if (cumulativeHist[i] >= percentile) {
                return (float) i / (numBins - 1);
            }
        }
        return 1.0f;
    }
}
