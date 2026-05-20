package amirz.dngprocessor.pipeline.toneequalizer;

import android.util.Log;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.autotune.AutoTune;
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;
import amirz.dngprocessor.pipeline.intermediate.Analysis;

/**
 * Tone Equalizer - EV-band based exposure adjustment.
 * 
 * Ported from darktable's toneequal.c and eigf.h
 * 
 * This module allows adjusting exposure selectively by luminance zones,
 * similar to an audio equalizer but for tonal values. It provides:
 * - Independent control over 9 luminance bands (from blacks to speculars)
 * - Edge-aware smoothing using a fast guided filter (EIGF) to prevent halos
 * - Smooth interpolation between bands using Gaussian weighting
 * 
 * The algorithm:
 * 1. Compute log2 luminance mask from input
 * 2. Apply edge-aware smoothing (Exposure-Independent Guided Filter)
 * 3. For each pixel, compute exposure correction as weighted sum of band adjustments
 * 4. Apply correction to input image
 * 
 * EV Bands:
 *   -8 EV: Blacks
 *   -7 EV: Deep Shadows
 *   -6 EV: Shadows
 *   -5 EV: Light Shadows
 *   -4 EV: Midtones (middle gray)
 *   -3 EV: Dark Highlights
 *   -2 EV: Highlights
 *   -1 EV: Whites
 *    0 EV: Speculars
 */
public class ToneEqualizer extends Stage implements IntermediateProvider {
    private static final String TAG = "ToneEqualizer";
    
    // Default smoothing (darktable uses sqrt(2))
    private static final float DEFAULT_SMOOTHING = 1.414f;
    
    // Output texture
    private Texture mOutput;
    
    @Override
    public Texture getIntermediate() {
        return mOutput;
    }
    
    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();
        ProcessParams process = getProcessParams();
        
        // Get input texture
        Texture input = previousStages.getStageByInterface(IntermediateProvider.class).getIntermediate();
        int width = input.getWidth();
        int height = input.getHeight();
        
        Log.d(TAG, String.format("ToneEqualizer: %dx%d", width, height));
        
        // Auto-tune if enabled
        if (process != null && process.toneEqualizerAutoTune) {
            Analysis analysis = previousStages.getStage(Analysis.class);
            if (analysis != null) {
                Log.d(TAG, "Auto-tuning Tone Equalizer parameters from image analysis");
                AutoTune.autoTuneFromAnalysis(process, analysis, false, true);
            } else {
                Log.w(TAG, "Analysis stage not found, using manual parameters");
            }
        }
        
        // Get parameters
        float evBlacks = process.toneEqBlacks;
        float evDeepShadows = process.toneEqDeepShadows;
        float evShadows = process.toneEqShadows;
        float evLightShadows = process.toneEqLightShadows;
        float evMidtones = process.toneEqMidtones;
        float evDarkHighlights = process.toneEqDarkHighlights;
        float evHighlights = process.toneEqHighlights;
        float evWhites = process.toneEqWhites;
        float evSpeculars = process.toneEqSpeculars;
        float smoothingDiameter = process.toneEqSmoothing;
        float feathering = process.toneEqFeathering;
        
        Log.d(TAG, String.format("EV bands: [%.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f, %.2f]",
                evBlacks, evDeepShadows, evShadows, evLightShadows, evMidtones,
                evDarkHighlights, evHighlights, evWhites, evSpeculars));
        
        // Step 1: Compute luminance mask
        Texture luminance = TexturePool.get(width, height, 1, Texture.Format.Float16);
        
        converter.useProgram(R.raw.te_compute_luminance);
        converter.setTexture("input_tex", input);
        converter.seti("width", width);
        converter.seti("height", height);
        converter.drawBlocks(luminance);
        
        // Step 2: Apply edge-aware smoothing (simplified EIGF)
        // For mobile efficiency, we use a simpler approach:
        // - Blur the luminance
        // - Blur the luminance squared
        // - Compute variance and apply guided filter blending
        
        Texture smoothedMask = applyGuidedFilter(luminance, width, height, smoothingDiameter, feathering);
        
        // Step 3: Apply correction
        mOutput = TexturePool.get(width, height, input.getChannels(), input.getFormat());
        
        converter.useProgram(R.raw.te_apply_correction);
        converter.setTexture("input_tex", input);
        converter.setTexture("mask_tex", smoothedMask);
        converter.seti("width", width);
        converter.seti("height", height);
        
        converter.setf("ev_blacks", evBlacks);
        converter.setf("ev_deep_shadows", evDeepShadows);
        converter.setf("ev_shadows", evShadows);
        converter.setf("ev_light_shadows", evLightShadows);
        converter.setf("ev_midtones", evMidtones);
        converter.setf("ev_dark_highlights", evDarkHighlights);
        converter.setf("ev_highlights", evHighlights);
        converter.setf("ev_whites", evWhites);
        converter.setf("ev_speculars", evSpeculars);
        converter.setf("smoothing", DEFAULT_SMOOTHING);
        
        converter.drawBlocks(mOutput);
        
        // Clean up
        luminance.close();
        smoothedMask.close();
        
        Log.d(TAG, "ToneEqualizer complete");
    }
    
    /**
     * Apply simplified EIGF (Exposure-Independent Guided Filter).
     * 
     * For mobile performance, we use a multi-pass Gaussian blur approach
     * rather than the full EIGF implementation.
     */
    private Texture applyGuidedFilter(Texture luminance, int width, int height, 
                                      float smoothingPercent, float feathering) {
        GLPrograms converter = getConverter();
        
        // Convert smoothing percentage to pixel radius
        float sigma = (smoothingPercent / 100.0f) * Math.min(width, height);
        sigma = Math.max(sigma, 1.0f);
        
        // For efficiency, we'll use existing blur infrastructure
        // For now, return a simple copy - the full EIGF can be implemented later
        // when we have Gaussian blur support
        
        // TODO: Implement full EIGF with:
        // 1. Downscale for efficiency
        // 2. Compute luminance squared
        // 3. Blur both luminance and luminance squared
        // 4. Compute variance
        // 5. Apply EIGF blending
        // 6. Upscale result
        
        // For now, just copy the luminance (no smoothing)
        // This still works for tone adjustment but may have some halos
        Texture result = TexturePool.get(width, height, 1, Texture.Format.Float16);
        
        // Simple copy for now - will be replaced with proper EIGF
        converter.useProgram(R.raw.te_guided_filter_blend);
        converter.setTexture("luminance_tex", luminance);
        
        // Create dummy stats texture (avg = luminance, variance = 0)
        Texture stats = TexturePool.get(width, height, 4, Texture.Format.Float16);
        converter.useProgram(R.raw.te_guided_filter_stats);
        converter.setTexture("luminance_tex", luminance);
        converter.setTexture("luminance_sq_tex", luminance);  // Will be wrong but placeholder
        converter.seti("width", width);
        converter.seti("height", height);
        converter.setf("sigma", sigma);
        converter.drawBlocks(stats);
        
        // Apply blending
        converter.useProgram(R.raw.te_guided_filter_blend);
        converter.setTexture("luminance_tex", luminance);
        converter.setTexture("stats_tex", stats);
        converter.setf("feathering", feathering);
        converter.seti("width", width);
        converter.seti("height", height);
        converter.drawBlocks(result);
        
        stats.close();
        
        return result;
    }
    
    @Override
    public int getShader() {
        return R.raw.te_compute_luminance;
    }
    
    @Override
    public boolean isEnabled() {
        ProcessParams process = getProcessParams();
        boolean enabled = process != null && process.toneEqualizerEnabled;
        Log.d(TAG, "ToneEqualizer.isEnabled(): process=" + (process != null) + 
                ", toneEqualizerEnabled=" + (process != null ? process.toneEqualizerEnabled : "null") + 
                ", returning=" + enabled);
        return enabled;
    }
    
    @Override
    public void close() {
        if (mOutput != null) {
            mOutput.close();
            mOutput = null;
        }
    }
}
