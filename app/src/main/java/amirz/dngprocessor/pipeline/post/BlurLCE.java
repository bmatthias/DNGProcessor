package amirz.dngprocessor.pipeline.post;

import android.util.Log;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;
import amirz.dngprocessor.pipeline.intermediate.MergeDetail;

/**
 * Multi-scale blur for Local Contrast Enhancement.
 * 
 * Standard mode (Natural): 3 scales - weak, medium, strong
 * Boosted and MAT modes: 6 scales for script-like multi-scale LCE
 *   - xfine, fine, weak (for highlights/fine detail)
 *   - medium, strong, xstrong (for shadows/large structure)
 * 
 * Radii are scaled relative to image dimensions (like convert_uraw script's
 * percentage-based CLAHE radii) to ensure consistent behavior across resolutions.
 */
public class BlurLCE extends Stage {
    private static final String TAG = "BlurLCE";
    private int mOffsetScale = 1;

    /** Use when the intermediate is at {@code scale}× the sensor's native resolution. */
    public void setOffsetScale(int scale) {
        mOffsetScale = scale;
    }
    
    // Standard 3-scale LCE
    private Texture mWeakBlur, mMediumBlur, mStrongBlur;
    
    // Extended 6-scale LCE for Boosted and MAT modes (like convert_uraw script)
    private Texture mXfineBlur, mFineBlur, mXstrongBlur;
    private boolean mBoostedMode = false;
    private boolean mBlursCreated = false;
    
    // Maximum kernel radius to keep GPU performance reasonable
    // Increased to support large-scale blurs (33%, 100%) for better shadow lifting
    private static final int MAX_KERNEL_RADIUS = 128;

    public Texture getWeakBlur() {
        return mWeakBlur;
    }

    public Texture getMediumBlur() {
        return mMediumBlur;
    }

    public Texture getStrongBlur() {
        return mStrongBlur;
    }
    
    // Extended blurs for Boosted and MAT modes
    public Texture getXfineBlur() {
        return mXfineBlur;
    }
    
    public Texture getFineBlur() {
        return mFineBlur;
    }
    
    public Texture getXstrongBlur() {
        return mXstrongBlur;
    }
    
    public boolean isBoostedMode() {
        return mBoostedMode;
    }
    
    public boolean hasBlurs() {
        return mBlursCreated;
    }
    
    /**
     * Check if blur textures are needed.
     * Required for LCE, or when Texture/Clarity sliders are used.
     */
    private boolean needsBlurs(ProcessParams process) {
        return process.lce || 
               Math.abs(process.toneTexture) > 0.01f || 
               Math.abs(process.toneClarity) > 0.01f;
    }
    
    /**
     * Helper to create a Gaussian blur at specified sigma/radius.
     */
    private Texture createBlur(GLPrograms converter, Texture intermediate, Texture tmp,
                               float sigma, int radius, int w, int h) {
        // Vertical pass
        converter.setTexture("buf", intermediate);
        converter.setf("sigma", sigma);
        converter.seti("radius", radius, 1);
        converter.seti("dir", 0, 1); // Vertical
        converter.setf("ch", 0, 1);  // xy[Y]
        converter.drawBlocks(tmp, false);
        
        // Horizontal pass
        converter.setTexture("buf", tmp);
        converter.seti("dir", 1, 0); // Horizontal
        converter.setf("ch", 1, 0);  // [Y]00
        
        Texture result = TexturePool.get(w, h, 1, Texture.Format.Float16);
        converter.drawBlocks(result);
        return result;
    }

    /**
     * Calculate sigma and radius for a blur at a given percentage of image dimension.
     * Returns [sigma, radius] where radius is clamped to MAX_KERNEL_RADIUS.
     */
    private float[] calcBlurParams(int refDimension, float percentage) {
        // Target sigma based on percentage of image dimension
        // Using 3-sigma rule: radius ≈ 3*sigma covers 99.7% of Gaussian
        float targetSigma = refDimension * percentage / 100f;
        int targetRadius = Math.max(1, (int) Math.ceil(targetSigma * 3));
        
        // Clamp radius for GPU performance
        int radius = Math.min(targetRadius, MAX_KERNEL_RADIUS);
        // Adjust sigma if radius was clamped (slightly less accurate but faster)
        float sigma = Math.min(targetSigma, radius / 3f);
        
        return new float[] { sigma, radius };
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        ProcessParams process = getProcessParams();
        if (!needsBlurs(process)) {
            return;
        }
        
        mBlursCreated = true;
        mBoostedMode = process.lce && process.lceMultiScale;

        Texture intermediate = null;
        MergeDetail mergeDetail = previousStages.getStage(MergeDetail.class);
        if (mergeDetail != null) {
            intermediate = mergeDetail.getIntermediate();
        }
        if (intermediate == null) {
            // Fall back to IntermediateProvider when MergeDetail is skipped (new stages enabled)
            intermediate = previousStages.getStageByInterface(IntermediateProvider.class).getIntermediate();
        }
        GLPrograms converter = getConverter();

        int w = intermediate.getWidth();
        int h = intermediate.getHeight();
        
        // Use smaller dimension as reference for percentage-based scaling
        // This matches convert_uraw script's approach
        int refDim = Math.min(w, h);

        try (Texture tmp = TexturePool.get(w, h, 1, Texture.Format.Float16)) {
            int offsetX = mOffsetScale * getSensorParams().outputOffsetX;
            int offsetY = mOffsetScale * getSensorParams().outputOffsetY;
            converter.seti("minxy", offsetX, offsetY);
            converter.seti("maxxy", w - offsetX - 1, h - offsetY - 1);

            // Standard 3 scales (always computed)
            // Use radii from preferences
            
            // Strong: large structure (from preferences)
            float[] strongParams = calcBlurParams(refDim, process.lceRadiusStrong);
            mStrongBlur = createBlur(converter, intermediate, tmp, 
                    strongParams[0], (int) strongParams[1], w, h);
            
            // Medium: mid-level detail (from preferences)
            float[] mediumParams = calcBlurParams(refDim, process.lceRadiusMedium);
            mMediumBlur = createBlur(converter, intermediate, tmp, 
                    mediumParams[0], (int) mediumParams[1], w, h);
            
            // Weak: fine detail (from preferences)
            float[] weakParams = calcBlurParams(refDim, process.lceRadiusWeak);
            mWeakBlur = createBlur(converter, intermediate, tmp, 
                    weakParams[0], (int) weakParams[1], w, h);
            
            Log.d(TAG, String.format("LCE blur scales for %dx%d (ref=%d): " +
                    "weak=%.1f/r%d, medium=%.1f/r%d, strong=%.1f/r%d",
                    w, h, refDim,
                    weakParams[0], (int) weakParams[1],
                    mediumParams[0], (int) mediumParams[1],
                    strongParams[0], (int) strongParams[1]));
            
            if (mBoostedMode) {
                // Extended scales for Boosted and MAT modes
                // Use radii from preferences
                
                // XStrong: very large structure (from preferences)
                float[] xstrongParams = calcBlurParams(refDim, process.lceRadiusXstrong);
                mXstrongBlur = createBlur(converter, intermediate, tmp, 
                        xstrongParams[0], (int) xstrongParams[1], w, h);
                
                // Fine: finer detail (from preferences)
                float[] fineParams = calcBlurParams(refDim, process.lceRadiusFine);
                mFineBlur = createBlur(converter, intermediate, tmp, 
                        fineParams[0], (int) fineParams[1], w, h);
                
                // XFine: finest detail (from preferences)
                // This captures very fine texture in highlights
                float[] xfineParams = calcBlurParams(refDim, process.lceRadiusXfine);
                mXfineBlur = createBlur(converter, intermediate, tmp, 
                        xfineParams[0], (int) xfineParams[1], w, h);
                
                Log.d(TAG, String.format("Multi-scale LCE extra scales: " +
                        "xfine=%.2f/r%d, fine=%.2f/r%d, xstrong=%.1f/r%d",
                        xfineParams[0], (int) xfineParams[1],
                        fineParams[0], (int) fineParams[1],
                        xstrongParams[0], (int) xstrongParams[1]));
            }
        }
    }

    @Override
    public void close() {
        if (mBlursCreated) {
            mWeakBlur.close();
            mMediumBlur.close();
            mStrongBlur.close();
            
            if (mBoostedMode) {
                mXfineBlur.close();
                mFineBlur.close();
                mXstrongBlur.close();
            }
        }
    }

    @Override
    public boolean isEnabled() {
        ProcessParams process = getProcessParams();
        // Run if blurs are needed (for LCE, Texture, or Clarity) - independent of other stages
        return needsBlurs(process);
    }
    
    @Override
    public int getShader() {
        return R.raw.stage3_2_blur_fs;
    }
}
