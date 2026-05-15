package amirz.dngprocessor.pipeline.noisereduce;

import android.util.Log;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.params.SensorParams;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;

public class NoiseReduce extends Stage implements IntermediateProvider {
    private static final String TAG = "NoiseReduce";

    private final SensorParams mSensorParams;
    private final ProcessParams mProcessParams;
    private final float[] mSensorToXYZ_D50;
    private Texture mDenoised;

    public NoiseReduce(SensorParams sensor, ProcessParams process, float[] sensorToXYZ_D50) {
        mSensorParams = sensor;
        mProcessParams = process;
        mSensorToXYZ_D50 = sensorToXYZ_D50;
    }

    public Texture getDenoised() {
        return mDenoised;
    }

    @Override
    public Texture getIntermediate() {
        return mDenoised;
    }

    @Override
    protected void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();

        Texture[] layers = previousStages.getStage(Decompose.class).getLayers();
        
        // Check NoiseReductionApplied - if any NR has been applied (> 0), skip additional NR
        // by default to avoid double-processing. Apply NR only when NoiseReductionApplied == 0
        // (indicating the DNG creator did not apply any noise reduction)
        // However, if forceNoiseReduction is enabled, ignore EXIF and apply NR anyway
        float nrAppliedExif = mSensorParams.noiseReductionApplied;
        float nrApplied = mProcessParams.forceNoiseReduction ? 0f : nrAppliedExif;
        
        // Early return: skip noise reduction if ANY NR has been applied (unless forced)
        if (!mProcessParams.forceNoiseReduction && nrAppliedExif > 0f) {
            Log.d(TAG, String.format("Skipping noise reduction: NoiseReductionApplied=%.2f (>0), forceNoiseReduction=%s", 
                    nrAppliedExif, mProcessParams.forceNoiseReduction));
            mDenoised = layers[0]; // Use the original high-res layer
            for (int i = 1; i < layers.length; i++) {
                layers[i].close();
            }
            return;  // Exit early - no denoising
        }
        
        // Debug: log when we proceed with noise reduction
        Log.d(TAG, String.format("Applying noise reduction: NoiseReductionApplied=%.2f, forceNoiseReduction=%s", 
                nrAppliedExif, mProcessParams.forceNoiseReduction));
        
        // Scale denoiseFactor based on how much NR has already been applied
        // If nrApplied=0.5, we only need half the noise reduction
        // If forceNoiseReduction is enabled, nrApplied is 0, so use full strength
        float nrScale = 1.0f - nrApplied;
        int effectiveDenoiseFactor = Math.round(mProcessParams.denoiseFactor * nrScale);
        
        if (mProcessParams.forceNoiseReduction) {
            Log.d(TAG, String.format("Force noise reduction enabled, using full denoiseFactor %d (ignoring EXIF value %.2f)",
                    effectiveDenoiseFactor, nrAppliedExif));
        } else if (nrAppliedExif > 0) {
            Log.d(TAG, String.format("NoiseReductionApplied=%.2f, scaling denoiseFactor %d -> %d",
                    nrAppliedExif, mProcessParams.denoiseFactor, effectiveDenoiseFactor));
        }
        
        // If denoiseFactor is 0, just pass through the original texture without modification
        if (effectiveDenoiseFactor == 0) {
            mDenoised = layers[0]; // Use the original high-res layer
            for (int i = 1; i < layers.length; i++) {
                layers[i].close();
            }
            return;
        }
        
        // Otherwise, we'll process and set mDenoised at the end
        // For now, initialize it to layers[0] (will be overwritten by denoised result)
        mDenoised = layers[0];

        Texture[] denoised = new Texture[layers.length];
        Texture[] noise = previousStages.getStage(NoiseMap.class).getNoiseTex();

        // Compute noise model parameters from DNG NoiseProfile or BaselineNoise fallback
        // (Still needed for bilateral filtering even with Anscombe disabled)
        float[] noiseModelXYZ = computeNoiseModelXYZ(mSensorParams.noiseProfile, mSensorToXYZ_D50);
        boolean useNoiseProfile = noiseModelXYZ != null;
        
        // If NoiseProfile wasn't available, try BaselineNoise as fallback
        float baselineNoiseScale = 1.0f;
        if (!useNoiseProfile && mSensorParams.baselineNoise != 1.0f) {
            // BaselineNoise is relative to a reference camera at ISO 100
            // Higher values = noisier camera, need stronger denoising
            baselineNoiseScale = mSensorParams.baselineNoise;
            Log.d(TAG, String.format("Using BaselineNoise fallback: %.2f", baselineNoiseScale));
        }

        // TODO: Anscombe variance-stabilizing transform (disabled - causes black highlights)
        /*
        PROBLEM: Anscombe transform causes highlights to turn black
        
        ROOT CAUSE:
        - DNG NoiseProfile values are very small (e.g., noiseA=0.0001)
        - Anscombe formula divides by sqrt(noiseA): 2 / (sqrt(0.0001) * 2) = 100x amplification
        - For HDR content where Y can be >> 1.0:
          * Input: Y = 10 (bright highlight)
          * Anscombe: f(10) ≈ 100 * 10 = 1000 (massive value!)
          * HDR encoding/decoding loses precision or overflows
          * Inverse transform fails for extreme values
          * Result: Black pixels or NaN
        
        DIFFERENCES FROM DARKTABLE:
        - Darktable works on normalized [0,1] RGB values
        - We work on xyY where Y is unnormalized and can be >> 1.0 for HDR content
        - Noise parameters are calibrated for [0,1] range, not HDR range
        
        POTENTIAL FIXES TO TRY:
        1. Normalize Y to [0,1] before Anscombe, denormalize after:
           - Store max Y value, divide by it before transform, multiply after
        2. Scale noise parameters by value range:
           - Multiply noiseA by (maxY)^2, noiseB by (maxY)^2
        3. HDR-aware transform:
           - Only transform Y values in [0,1], pass HDR (Y>1) through unchanged
           - Or use log-space transform for HDR content
        4. Use sensor-space transform instead of xyY:
           - Transform in linear sensor RGB before color conversion
        
        End of disabled Anscombe section - revisit later
        float noiseA = 0.0001f;
        float noiseB = 0.00001f;
        if (useNoiseProfile && noiseModelXYZ.length >= 4) {
            noiseA = noiseModelXYZ[2];
            noiseB = noiseModelXYZ[3];
            Log.d(TAG, String.format("Anscombe parameters: noiseA=%.6f, noiseB=%.6f", noiseA, noiseB));
        } else if (mSensorParams.baselineNoise != 1.0f) {
            float baselineScale = mSensorParams.baselineNoise * mSensorParams.baselineNoise;
            noiseA = 0.0001f * baselineScale;
            noiseB = 0.00001f * baselineScale;
            Log.d(TAG, String.format("Anscombe from BaselineNoise=%.2f: noiseA=%.6f, noiseB=%.6f",
                    mSensorParams.baselineNoise, noiseA, noiseB));
        }

        Texture[] preconditioned = new Texture[layers.length];
        converter.useProgram(R.raw.stage2_0_anscombe_precondition_fs);
        converter.setf("noiseA", noiseA);
        converter.setf("noiseB", noiseB);
        converter.setf("shadowsParam", 0.0f);

        for (int i = 0; i < layers.length; i++) {
            converter.setTexture("buf", layers[i]);
            converter.seti("bufSize", layers[i].getWidth(), layers[i].getHeight());
            preconditioned[i] = new Texture(layers[i]);
            converter.drawBlocks(preconditioned[i]);
            Log.d(TAG, String.format("Applied Anscombe precondition to layer %d", i));
        }
        */
        
        // Without Anscombe, work directly on original layers
        Texture[] preconditioned = layers;

        // Choose denoising method based on user preference
        Texture[] denoisedStabilized = new Texture[layers.length];
        
        // TODO: Wavelet noise reduction - temporarily disabled, needs debugging
        // The wavelet implementation is causing black images - needs investigation
        /*
        if (mProcessParams.waveletNoiseReduction) {
            // Edge-aware wavelet denoising on variance-stabilized data (darktable approach)
            final int numWaveletScales = 3;  // Number of wavelet decomposition scales
            
            for (int layerIdx = 0; layerIdx < layers.length; layerIdx++) {
                Texture current = preconditioned[layerIdx];
                int width = current.getWidth();
                int height = current.getHeight();
                
                // First pass: decompose all scales to get coarsest approximation and all details
                Texture[] coarseLevels = new Texture[numWaveletScales + 1];
                Texture[] detailLevels = new Texture[numWaveletScales];
                coarseLevels[0] = current;  // Start with original
                
                for (int scale = 0; scale < numWaveletScales; scale++) {
                    // Decompose into coarse approximation
                    converter.useProgram(R.raw.stage2_1_wavelet_decompose_fs);
                    converter.setTexture("buf", coarseLevels[scale]);
                    converter.seti("bufSize", width, height);
                    converter.seti("scale", scale);
                    converter.setf("edgeAwareness", 1.0f);  // Edge preservation strength
                    
                    Texture coarse = new Texture(width, height, 4, Texture.Format.Float16, null);
                    converter.drawBlocks(coarse);
                    coarseLevels[scale + 1] = coarse;
                    
                    // Compute detail = input - coarse
                    converter.useProgram(R.raw.stage2_1_wavelet_detail_fs);
                    converter.setTexture("bufInput", coarseLevels[scale]);
                    converter.setTexture("bufCoarse", coarse);
                    converter.seti("bufSize", width, height);
                    
                    Texture detail = new Texture(width, height, 4, Texture.Format.Float16, null);
                    converter.drawBlocks(detail);
                    detailLevels[scale] = detail;
                }
                
                // Second pass: start with coarsest approximation, add back thresholded details
                // Start accumulator with the coarsest approximation (not the full image!)
                Texture accumulator = new Texture(coarseLevels[numWaveletScales]);
                
                // Add details back from coarsest to finest scale
                for (int scale = numWaveletScales - 1; scale >= 0; scale--) {
                    // Compute threshold based on scale and noise level
                    // After Anscombe transform, noise variance ≈ 1, but values are scaled
                    // Threshold should be much smaller - try 0.01-0.1 range
                    float baseThreshold = 0.05f * nrScale;  // Much smaller threshold
                    float scaleMultiplier = (float)Math.pow(2.0, scale);  // Increase threshold at coarser scales
                    float threshold = baseThreshold * scaleMultiplier;
                    
                    // Synthesize: soft threshold detail and accumulate
                    converter.useProgram(R.raw.stage2_2_wavelet_synthesize_fs);
                    converter.setTexture("bufAccum", accumulator);
                    converter.setTexture("bufDetail", detailLevels[scale]);
                    converter.seti("bufSize", width, height);
                    converter.setf("threshold", threshold, threshold, threshold);
                    converter.setf("boost", 1.0f, 1.0f, 1.0f);  // Neutral boost
                    
                    Texture newAccumulator = new Texture(width, height, 4, Texture.Format.Float16, null);
                    converter.drawBlocks(newAccumulator);
                    
                    // Clean up
                    accumulator.close();
                    accumulator = newAccumulator;
                    
                    Log.d(TAG, String.format("Layer %d, wavelet scale %d: threshold=%.4f", 
                            layerIdx, scale, threshold));
                }
                
                // Clean up intermediate textures
                for (int i = 0; i <= numWaveletScales; i++) {
                    if (i > 0 || layerIdx > 0) coarseLevels[i].close();
                }
                for (int i = 0; i < numWaveletScales; i++) {
                    detailLevels[i].close();
                }
                
                denoisedStabilized[layerIdx] = accumulator;
            }
            
            Log.d(TAG, "Using edge-aware wavelet denoising (darktable-style)");
        } else {
        */
            // Use bilateral filtering (without Anscombe transform)
            for (int i = 0; i < layers.length; i++) {
                converter.useProgram(R.raw.stage3_1_noise_reduce_fs);

                converter.seti("bufSize", layers[i].getWidth(), layers[i].getHeight());
                converter.setTexture("noiseTex", noise[i]);
                converter.seti("radius", 3 << (i * 2), 1 << (i * 2)); // Radius, Sampling
                
                // Compute adaptive sigma values based on NoiseProfile if available
                float spatialSigma, colorSigma;
                if (useNoiseProfile) {
                    // Use NoiseProfile to scale sigma values
                    float noiseScale = computeNoiseScale(noiseModelXYZ, i);
                    spatialSigma = 2f * (1 << (i * 2)) * noiseScale * nrScale;
                    colorSigma = (3f / (i + 1)) * noiseScale * nrScale;
                    Log.d(TAG, String.format("Layer %d: noiseScale=%.3f, nrScale=%.2f, spatial=%.2f, color=%.2f", 
                            i, noiseScale, nrScale, spatialSigma, colorSigma));
                } else {
                    // Fallback to BaselineNoise-scaled values
                    spatialSigma = 2f * (1 << (i * 2)) * baselineNoiseScale * nrScale;
                    colorSigma = (3f / (i + 1)) * baselineNoiseScale * nrScale;
                    Log.d(TAG, String.format("Layer %d: baselineNoise=%.2f, nrScale=%.2f, spatial=%.2f, color=%.2f", 
                            i, baselineNoiseScale, nrScale, spatialSigma, colorSigma));
                }
                
                converter.setf("sigma", spatialSigma, colorSigma);
                converter.setf("blendY", 3f / (2f + layers.length - i));

                denoisedStabilized[i] = new Texture(layers[i]);

                converter.setTexture("inBuffer", layers[i]);
                converter.drawBlocks(denoisedStabilized[i]);
            }
            
            Log.d(TAG, "Using bilateral filtering");
        // }  // End of commented wavelet block

        // TODO: Inverse Anscombe transform - commented out with Anscombe precondition
        /*
        // Apply inverse Anscombe transform to restore original signal space
        converter.useProgram(R.raw.stage2_4_anscombe_inverse_fs);
        converter.setf("noiseA", noiseA);
        converter.setf("noiseB", noiseB);
        converter.setf("shadowsParam", 0.0f);  // Must match precondition
        converter.setf("bias", 0.5f);  // Bias correction: 0.25-1.0 (higher = more aggressive denoising)

        for (int i = 0; i < layers.length; i++) {
            converter.setTexture("buf", denoisedStabilized[i]);
            converter.seti("bufSize", denoisedStabilized[i].getWidth(), denoisedStabilized[i].getHeight());
            denoised[i] = new Texture(denoisedStabilized[i]);
            converter.drawBlocks(denoised[i]);
            Log.d(TAG, String.format("Applied inverse Anscombe to layer %d", i));

            // Clean up intermediate textures
            preconditioned[i].close();
            denoisedStabilized[i].close();
        }
        */
        
        // Without Anscombe: denoised layers are already in denoisedStabilized
        // (which are actually just bilateral-filtered original layers, no stabilization)
        for (int i = 0; i < layers.length; i++) {
            denoised[i] = denoisedStabilized[i];
        }

        // Merge all layers.
        converter.useProgram(R.raw.stage3_1_noise_reduce_remove_noise_fs);

        converter.setTexture("bufDenoisedHighRes", denoised[0]);
        converter.setTexture("bufDenoisedMediumRes", denoised[1]);
        converter.setTexture("bufDenoisedLowRes", denoised[2]);
        converter.setTexture("bufNoisyMediumRes", layers[1]);
        converter.setTexture("bufNoisyLowRes", layers[2]);
        converter.setTexture("noiseTexMediumRes", noise[1]);
        converter.setTexture("noiseTexLowRes", noise[2]);

        // Reuse original high res noisy texture.
        converter.drawBlocks(mDenoised);

        // Cleanup.
        denoised[0].close();
        for (int i = 1; i < layers.length; i++) {
            layers[i].close();
            denoised[i].close();
        }
    }

    @Override
    public int getShader() {
        return R.raw.stage3_1_noise_reduce_fs;
    }

    @Override
    protected boolean isEnabled() {
        return getProcessParams().noiseReduce;
    }

    /**
     * Convert NoiseProfile from sensor space to XYZ space.
     * NoiseProfile format: [a0, b0, a1, b1, a2, b2] where noise_stddev = sqrt(a * signal + b)
     * Returns [aX, bX, aY, bY, aZ, bZ] in XYZ space, or null if conversion fails.
     * 
     * If NoiseProfile is missing/invalid but BaselineNoise is available, creates
     * a synthetic noise model scaled by BaselineNoise.
     */
    private float[] computeNoiseModelXYZ(float[] noiseProfile, float[] sensorToXYZ) {
        if (sensorToXYZ == null || sensorToXYZ.length != 9) {
            return null;
        }

        // Check if NoiseProfile is valid (exists and not all zeros)
        boolean hasValidNoiseProfile = false;
        if (noiseProfile != null && noiseProfile.length == 6) {
            for (float v : noiseProfile) {
                if (v > 0) {
                    hasValidNoiseProfile = true;
                    break;
                }
            }
        }
        
        if (!hasValidNoiseProfile) {
            // Try to create synthetic noise model from BaselineNoise
            if (mSensorParams.baselineNoise != 1.0f) {
                Log.d(TAG, String.format("NoiseProfile missing/invalid, using BaselineNoise=%.2f to create synthetic model",
                        mSensorParams.baselineNoise));
                return createSyntheticNoiseModel(mSensorParams.baselineNoise);
            }
            Log.d(TAG, "NoiseProfile is missing/invalid and no BaselineNoise available");
            return null;
        }

        // Compute noise variance at a representative signal level (mid-gray = 0.5)
        // In sensor space: noise_stddev = sqrt(a * signal + b)
        // NoiseProfile format: [a0, b0, a1, b1, a2, b2] for 3 channels
        // For Bayer CFA: channels are typically mapped as [R, G, B] or [R, G1, G2, B] -> [R, G, B]
        float signalLevel = 0.5f; // Mid-gray
        
        float[] sensorNoiseVar = new float[3];
        
        // noise_stddev = sqrt(a * signal + b), so variance = a * signal + b
        sensorNoiseVar[0] = noiseProfile[0] * signalLevel + noiseProfile[1]; // R: a0*s + b0
        sensorNoiseVar[1] = noiseProfile[2] * signalLevel + noiseProfile[3]; // G: a1*s + b1
        sensorNoiseVar[2] = noiseProfile[4] * signalLevel + noiseProfile[5]; // B: a2*s + b2

        // Transform noise variance through color matrix
        // For a linear transform M: Var(Y) = M * Var(X) * M^T
        // But since we're dealing with standard deviations, we approximate:
        // We'll compute the noise magnitude in XYZ space
        float[] xyzNoise = new float[3];
        xyzNoise[0] = Math.abs(sensorToXYZ[0] * sensorNoiseVar[0] + 
                              sensorToXYZ[1] * sensorNoiseVar[1] + 
                              sensorToXYZ[2] * sensorNoiseVar[2]);
        xyzNoise[1] = Math.abs(sensorToXYZ[3] * sensorNoiseVar[0] + 
                              sensorToXYZ[4] * sensorNoiseVar[1] + 
                              sensorToXYZ[5] * sensorNoiseVar[2]);
        xyzNoise[2] = Math.abs(sensorToXYZ[6] * sensorNoiseVar[0] + 
                              sensorToXYZ[7] * sensorNoiseVar[1] + 
                              sensorToXYZ[8] * sensorNoiseVar[2]);

        // Convert to noise model parameters in XYZ space
        // We'll use a simplified model: noise_stddev = sqrt(a * signal + b)
        // For XYZ, we'll use the transformed noise levels as base values
        float[] noiseModelXYZ = new float[6];
        float avgNoise = (xyzNoise[0] + xyzNoise[1] + xyzNoise[2]) / 3f;
        
        // Scale the noise model parameters proportionally
        // a component (shot noise) - scales with signal
        noiseModelXYZ[0] = avgNoise * 0.5f; // aX
        noiseModelXYZ[1] = avgNoise * 0.1f; // bX (read noise)
        noiseModelXYZ[2] = avgNoise * 0.5f; // aY
        noiseModelXYZ[3] = avgNoise * 0.1f; // bY
        noiseModelXYZ[4] = avgNoise * 0.5f; // aZ
        noiseModelXYZ[5] = avgNoise * 0.1f; // bZ

        Log.d(TAG, String.format("NoiseProfile converted: sensor=[%.4f,%.4f,%.4f] -> XYZ=[%.4f,%.4f,%.4f]",
                sensorNoiseVar[0], sensorNoiseVar[1], sensorNoiseVar[2],
                xyzNoise[0], xyzNoise[1], xyzNoise[2]));

        return noiseModelXYZ;
    }
    
    /**
     * Create a synthetic noise model when NoiseProfile is not available.
     * Uses BaselineNoise to scale a default noise model.
     * BaselineNoise = 1.0 means camera has same noise as reference at ISO 100.
     * Higher values = noisier camera.
     */
    private float[] createSyntheticNoiseModel(float baselineNoise) {
        // Default noise parameters for a "reference" camera at ISO 100
        // These are typical values for a good quality sensor
        float baseA = 0.0001f;  // Shot noise coefficient
        float baseB = 0.00001f; // Read noise (signal-independent)
        
        // Scale by BaselineNoise (squared because noise variance scales with intensity)
        float scale = baselineNoise * baselineNoise;
        
        float[] noiseModel = new float[6];
        noiseModel[0] = baseA * scale; // aX
        noiseModel[1] = baseB * scale; // bX
        noiseModel[2] = baseA * scale; // aY
        noiseModel[3] = baseB * scale; // bY
        noiseModel[4] = baseA * scale; // aZ
        noiseModel[5] = baseB * scale; // bZ
        
        Log.d(TAG, String.format("Created synthetic noise model from BaselineNoise=%.2f: a=%.6f, b=%.6f",
                baselineNoise, noiseModel[0], noiseModel[1]));
        
        return noiseModel;
    }

    /**
     * Compute noise scale factor for a given pyramid level based on NoiseProfile.
     * Higher noise levels require stronger filtering (larger sigma).
     */
    private float computeNoiseScale(float[] noiseModelXYZ, int pyramidLevel) {
        if (noiseModelXYZ == null) {
            return 1.0f;
        }

        // Compute expected noise at mid-gray for this pyramid level
        // At coarser levels, noise is reduced by downsampling
        float signalLevel = 0.5f;
        float downscaleFactor = 1.0f / (1 << (pyramidLevel * 2)); // 1.0, 0.25, 0.0625, ...
        
        // Average noise across XYZ channels
        float noiseX = (float) Math.sqrt(noiseModelXYZ[0] * signalLevel + noiseModelXYZ[1]) * downscaleFactor;
        float noiseY = (float) Math.sqrt(noiseModelXYZ[2] * signalLevel + noiseModelXYZ[3]) * downscaleFactor;
        float noiseZ = (float) Math.sqrt(noiseModelXYZ[4] * signalLevel + noiseModelXYZ[5]) * downscaleFactor;
        float avgNoise = (noiseX + noiseY + noiseZ) / 3f;

        // Normalize to a reasonable scale (0.5 to 2.0)
        // Typical noise levels are in range 0.01-0.1, so we scale accordingly
        float baseNoise = 0.05f; // Reference noise level
        float scale = Math.max(0.5f, Math.min(2.0f, avgNoise / baseNoise));
        
        return scale;
    }

    /**
     * Noise Reduction Parameters.
     */
    static class NRParams {
        final float[] sigma;
        final int denoiseFactor;
        final float sharpenFactor;
        final float adaptiveSaturation, adaptiveSaturationPow;

        private NRParams(ProcessParams params, float[] s) {
            sigma = s;

            float hypot = (float) Math.hypot(s[0], s[1]);
            Log.d(TAG, "Chroma noise hypot " + hypot);

            denoiseFactor = (int)((float) params.denoiseFactor * Math.sqrt(s[0] + s[1]));
            Log.d(TAG, "Denoise radius " + denoiseFactor);

            sharpenFactor = Math.max(params.sharpenFactor - 6f * hypot, -0.25f);
            Log.d(TAG, "Sharpen factor " + sharpenFactor);

            adaptiveSaturation = Math.max(0f, params.adaptiveSaturation[0] - 30f * hypot);
            adaptiveSaturationPow = params.adaptiveSaturation[1];
            Log.d(TAG, "Adaptive saturation " + adaptiveSaturation);
        }
    }
}
