package amirz.dngprocessor.pipeline.exposefuse;

import android.util.Log;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;

import static amirz.dngprocessor.pipeline.exposefuse.FuseUtils.*;

public class Merge extends Stage implements IntermediateProvider {
    private static final String TAG = "Merge";
    private static final int LEVELS = 10;  // Match Laplace.LEVELS
    
    private Texture mMerged;

    public Texture getMerged() {
        return mMerged;
    }
    
    @Override
    public Texture getIntermediate() {
        return mMerged;
    }

    @Override
    protected void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();
        ProcessParams process = getProcessParams();
        
        // Check user preference for fusion method
        String method = (process != null) ? process.exposeFusionMethod : "laplacian";
        boolean useLaplacian = "laplacian".equals(method);
        
        // Check if Laplacian pyramids are available
        Laplace laplace = previousStages.getStage(Laplace.class);
        
        if (useLaplacian && laplace != null) {
            // Use Laplacian pyramid blending for seamless multi-scale fusion
            Log.d(TAG, "Using Laplacian pyramid blending for exposure fusion (method=" + method + ")");
            executePyramidBlending(converter, previousStages, laplace);
        } else {
            // Use simple Mertens-style weighted blending
            if (useLaplacian && laplace == null) {
                Log.w(TAG, "Laplacian pyramids requested but Laplace stage not found, falling back to Mertens-style");
            } else {
                Log.d(TAG, "Using Mertens-style weighted blending for exposure fusion (method=" + method + ")");
            }
            executeSimpleBlending(converter, previousStages, method);
        }
    }
    
    /**
     * Laplacian pyramid-based exposure fusion (Mertens et al.).
     * 
     * IMPORTANT: Weights are computed at FULL resolution and then downsampled
     * to form a Gaussian pyramid of weights. This maintains spatial correlation
     * between fine details and their corresponding weights.
     * 
     * Algorithm:
     * 1. Compute weights at full resolution from finest Gaussian level
     * 2. Build Gaussian pyramid of weights by downsampling (NOT recomputing)
     * 3. Blend Laplacian coefficients at each level using corresponding weights
     * 4. Blend coarsest Gaussian level
     * 5. Reconstruct from coarse to fine
     */
    private void executePyramidBlending(GLPrograms converter, StagePipeline.StageMap previousStages, Laplace laplace) {
        // Get pyramids from Laplace stage
        Laplace.Pyramid underPyramid = laplace.getUnderPyramid();
        Laplace.Pyramid normalPyramid = laplace.getExtraHighlightPyramid();
        Laplace.Pyramid overPyramid = laplace.getOverPyramid();
        
        if (underPyramid == null || normalPyramid == null || overPyramid == null) {
            Log.w(TAG, "Pyramids not available, falling back to simple blending");
            ProcessParams process = getProcessParams();
            String method = (process != null) ? process.exposeFusionMethod : "laplacian";
            executeSimpleBlending(converter, previousStages, method);
            return;
        }
        
        // Get original input for weight calculation
        Texture originalInput = previousStages.getStageByInterface(IntermediateProvider.class).getIntermediate();
        
        // Extract luma from original input at full resolution for weight calculation
        Texture originalLuma = extractLuma(converter, originalInput);
        
        try {
            // Step 1: Compute weights at FULL resolution from the finest Gaussian level
            // This is the key fix: weights are computed once at full res, not at each level
            Log.d(TAG, "Computing weights at full resolution: " + 
                    underPyramid.gauss[0].getWidth() + "x" + underPyramid.gauss[0].getHeight());
            Texture fullResWeights = computeWeightsAtLevel(converter,
                    underPyramid.gauss[0], normalPyramid.gauss[0], overPyramid.gauss[0],
                    originalLuma);
            
            // Step 2: Build Gaussian pyramid of weights by DOWNSAMPLING (not recomputing)
            // This maintains spatial correlation between weights and fine details
            Texture[] weightPyramid = new Texture[LEVELS];
            weightPyramid[0] = fullResWeights;
            for (int i = 1; i < LEVELS; i++) {
                weightPyramid[i] = downsample2x(converter, weightPyramid[i - 1]);
            }
            
            // Step 3: Blend Laplacian pyramids at each level using downsampled weights
            Texture[] blendedLaplacians = new Texture[LEVELS - 1];
            for (int i = 0; i < LEVELS - 1; i++) {
                blendedLaplacians[i] = blendLaplaciansAtLevel(converter,
                        underPyramid.laplace[i], normalPyramid.laplace[i], overPyramid.laplace[i],
                        weightPyramid[i]);
            }
            
            // Step 4: Blend the coarsest Gaussian level (base of pyramid)
            // Use the coarsest weight pyramid level
            Texture blendedCoarse = blendGaussiansWithWeights(converter,
                    underPyramid.gauss[LEVELS - 1], normalPyramid.gauss[LEVELS - 1], 
                    overPyramid.gauss[LEVELS - 1], weightPyramid[LEVELS - 1]);
            
            // Step 5: Reconstruct from coarse to fine
            Texture mergedLuma = reconstructPyramid(converter, blendedCoarse, blendedLaplacians);
            
            // Step 6: Combine with chroma
            Texture chroma = previousStages.getStageByInterface(IntermediateProvider.class).getIntermediate();
            converter.useProgram(R.raw.stage4_9_combine_z);
            converter.setTexture("bufChroma", chroma);
            converter.setTexture("bufLuma", mergedLuma);
            
            // Use Float16 format to avoid banding from 8-bit quantization
            mMerged = TexturePool.get(chroma.getWidth(), chroma.getHeight(), 
                    chroma.getChannels(), Texture.Format.Float16);
            converter.drawBlocks(mMerged);
            
            // Cleanup
            mergedLuma.close();
            blendedCoarse.close();
            for (Texture tex : weightPyramid) {
                if (tex != null) tex.close();
            }
            for (Texture tex : blendedLaplacians) {
                if (tex != null) tex.close();
            }
            originalLuma.close();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in pyramid blending, falling back to simple blending", e);
            // Cleanup on error
            originalLuma.close();
            ProcessParams process = getProcessParams();
            String method = (process != null) ? process.exposeFusionMethod : "laplacian";
            executeSimpleBlending(converter, previousStages, method);
        }
    }
    
    /**
     * Extract luma channel from the original input texture.
     */
    private Texture extractLuma(GLPrograms converter, Texture original) {
        Texture luma = TexturePool.get(original.getWidth(), original.getHeight(), 
                1, Texture.Format.Float16);
        converter.useProgram(R.raw.stage4_13_extract_luma_downsample);
        converter.setTexture("originalInput", original);
        converter.seti("maxxy", original.getWidth() - 1, original.getHeight() - 1);
        converter.drawBlocks(luma);
        return luma;
    }
    
    // Mertens weight exponents (can be tuned for different effects)
    // Default values from Mertens et al. paper
    private static final float EXPONENT_CONTRAST = 1.0f;     // alpha: contrast importance
    private static final float EXPONENT_SATURATION = 1.0f;   // beta: saturation importance
    private static final float EXPONENT_EXPOSURE = 1.0f;     // gamma: well-exposedness importance
    
    /**
     * Compute Mertens weights at a specific resolution.
     * Returns RGB texture where R=under weight, G=normal weight, B=over weight.
     */
    private Texture computeWeightsAtLevel(GLPrograms converter, Texture frameUnder, 
            Texture frameNormal, Texture frameOver, Texture originalInput) {
        Texture weights = TexturePool.get(frameUnder.getWidth(), frameUnder.getHeight(), 
                3, Texture.Format.Float16);  // RGB = weights for 3 frames
        
        converter.useProgram(R.raw.stage4_10_compute_weights);
        converter.setTexture("frameUnder", frameUnder);
        converter.setTexture("frameNormal", frameNormal);
        converter.setTexture("frameOver", frameOver);
        converter.setTexture("originalInput", originalInput);
        
        // Pass texture size for edge clamping in contrast computation
        converter.seti("texSize", frameUnder.getWidth() - 1, frameUnder.getHeight() - 1);
        
        // Pass Mertens exponents
        converter.setf("wExponentContrast", EXPONENT_CONTRAST);
        converter.setf("wExponentSaturation", EXPONENT_SATURATION);
        converter.setf("wExponentExposure", EXPONENT_EXPOSURE);
        
        converter.drawBlocks(weights);
        
        return weights;
    }
    
    /**
     * Blend Laplacian coefficients at a specific pyramid level.
     */
    private Texture blendLaplaciansAtLevel(GLPrograms converter, Texture laplaceUnder,
            Texture laplaceNormal, Texture laplaceOver, Texture weights) {
        Texture blended = TexturePool.get(laplaceUnder.getWidth(), laplaceUnder.getHeight(),
                1, Texture.Format.Float16);
        
        converter.useProgram(R.raw.stage4_11_blend_laplacian);
        converter.setTexture("laplaceUnder", laplaceUnder);
        converter.setTexture("laplaceNormal", laplaceNormal);
        converter.setTexture("laplaceOver", laplaceOver);
        converter.setTexture("weights", weights);
        converter.drawBlocks(blended);
        
        return blended;
    }
    
    /**
     * Blend Gaussian levels using pre-computed weights.
     * Used for the coarsest level where we blend Gaussian values directly.
     */
    private Texture blendGaussiansWithWeights(GLPrograms converter, Texture gaussUnder,
            Texture gaussNormal, Texture gaussOver, Texture weights) {
        Texture blended = TexturePool.get(gaussUnder.getWidth(), gaussUnder.getHeight(),
                1, Texture.Format.Float16);
        
        // Reuse same blending logic as Laplacian blending
        converter.useProgram(R.raw.stage4_11_blend_laplacian);
        converter.setTexture("laplaceUnder", gaussUnder);
        converter.setTexture("laplaceNormal", gaussNormal);
        converter.setTexture("laplaceOver", gaussOver);
        converter.setTexture("weights", weights);
        converter.drawBlocks(blended);
        
        return blended;
    }
    
    /**
     * Reconstruct the final image from the blended pyramid (coarse to fine).
     */
    private Texture reconstructPyramid(GLPrograms converter, Texture coarse, Texture[] laplacians) {
        Texture current = coarse;
        
        // Reconstruct from coarsest to finest
        for (int i = laplacians.length - 1; i >= 0; i--) {
            Texture upsampled = upsample2x(converter, current, laplacians[i]);
            
            // Reconstruct: upsampled + Laplacian detail
            Texture reconstructed = TexturePool.get(laplacians[i].getWidth(), laplacians[i].getHeight(),
                    1, Texture.Format.Float16);
            
            converter.useProgram(R.raw.stage4_12_reconstruct_pyramid);
            converter.setTexture("coarse", upsampled);
            converter.setTexture("laplacian", laplacians[i]);
            converter.drawBlocks(reconstructed);
            
            if (current != coarse) {
                current.close();  // Close intermediate upsampled texture
            }
            upsampled.close();
            current = reconstructed;
        }
        
        return current;
    }
    
    /**
     * Simple weighted blending (fallback when pyramids are not available).
     * Uses Mertens-style weights but without multi-scale pyramid blending.
     */
    private void executeSimpleBlending(GLPrograms converter, StagePipeline.StageMap previousStages, String method) {
        // 3-frame exposure fusion with Mertens-style well-exposedness weighting
        // Uses region-specific weight boosts for proper highlight/shadow handling
        DoubleExpose doubleExpose = previousStages.getStage(DoubleExpose.class);
        Texture underExposed = doubleExpose.getUnderexposed();      // gamma > 1, preserves highlights
        Texture normalExposed = doubleExpose.getExtraHighlight();   // gamma = 1, reference
        Texture overExposed = doubleExpose.getOverexposed();        // gamma < 1, reveals shadows
        
        // Get original input texture (before any gamma curves) for weight calculation
        // The original luminance determines highlight/shadow regions for weight boosting
        Texture originalInput = previousStages.getStageByInterface(IntermediateProvider.class).getIntermediate();
        
        converter.useProgram(getShader());
        converter.setTexture("frameUnder", underExposed);
        converter.setTexture("frameNormal", normalExposed);
        converter.setTexture("frameOver", overExposed);
        converter.setTexture("originalInput", originalInput);
        
        // Pass texture size for edge clamping in contrast computation
        converter.seti("texSize", underExposed.getWidth() - 1, underExposed.getHeight() - 1);
        
        // Pass Mertens exponents
        converter.setf("wExponentContrast", EXPONENT_CONTRAST);
        converter.setf("wExponentSaturation", EXPONENT_SATURATION);
        converter.setf("wExponentExposure", EXPONENT_EXPOSURE);
        
        // Respect user preference for fusion method
        // "fullmertens" -> full Mertens (contrast + saturation + well-exposedness)
        // "mertens" -> simple Mertens (well-exposedness only)
        // "laplacian" -> fullmertens (fallback when Laplacian not available)
        int useFullMertens;
        if ("mertens".equals(method)) {
            useFullMertens = 0;  // Simple Mertens (well-exposedness only)
        } else {
            useFullMertens = 1;  // Full Mertens (or Laplacian fallback)
        }
        converter.seti("useFullMertens", useFullMertens);
        Log.d(TAG, "Using fusion method: " + method + " (useFullMertens=" + useFullMertens + ")");
        
        Log.d(TAG, "3-frame fusion: under=" + underExposed + ", normal=" + normalExposed + ", over=" + overExposed);
        
        Texture chroma = previousStages.getStageByInterface(IntermediateProvider.class).getIntermediate();
        
        // Create output texture for merged luma
        Texture mergedLuma = TexturePool.get(chroma.getWidth(), chroma.getHeight(), 1,
                Texture.Format.Float16);
        converter.drawBlocks(mergedLuma);
        
        // Combine with chroma
        converter.useProgram(R.raw.stage4_9_combine_z);
        converter.setTexture("bufChroma", chroma);
        converter.setTexture("bufLuma", mergedLuma);
        
        // Use Float16 format to avoid banding from 8-bit quantization
        mMerged = TexturePool.get(chroma.getWidth(), chroma.getHeight(), 
                chroma.getChannels(), Texture.Format.Float16);
        converter.drawBlocks(mMerged);
        
        mergedLuma.close();
    }

    private void noiseReduce(Texture in, Texture out, int level) {
        GLPrograms converter = getConverter();
        converter.useProgram(level > 0
                ? R.raw.stage4_7_nr_intermediate
                : R.raw.stage4_8_nr_zero);
        converter.setTexture("buf", in);
        converter.seti("bufEdge", in.getWidth() - 1, in.getHeight() - 1);
        converter.setf("blendY", 0.9f);
        if (level > 0) {
            converter.setf("sigma", 0.4f, 0.03f);
        }
        converter.drawBlocks(out, level == 0);
    }

    @Override
    public int getShader() {
        return R.raw.stage4_5_merge_3frame;
    }

    @Override
    protected boolean isEnabled() {
        ProcessParams process = getProcessParams();
        // Run if exposeFuse toggle is enabled (independent of other stages)
        // Note: This stage is not added to pipeline if EarlyExposureFusion is added
        return process != null && process.exposeFuse;
    }

    @Override
    public void close() {
        if (mMerged != null) {
            mMerged.close();
            mMerged = null;
        }
    }
}
