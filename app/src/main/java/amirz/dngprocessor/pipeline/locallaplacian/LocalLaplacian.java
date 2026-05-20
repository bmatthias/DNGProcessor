package amirz.dngprocessor.pipeline.locallaplacian;

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
 * Local Laplacian Filter for edge-aware local contrast enhancement.
 * 
 * Ported from darktable's locallaplacian.c/locallaplacian.cl
 * 
 * This filter provides:
 * - Shadow lifting without halos
 * - Highlight compression without losing detail
 * - Local contrast (clarity) enhancement
 * 
 * The algorithm works by:
 * 1. Building a Gaussian pyramid of the input luminance
 * 2. Creating multiple tone-mapped versions at different "gamma" reference levels
 * 3. For each pixel, interpolating between the Laplacian coefficients of the
 *    two bracketing gamma images based on local luminance
 * 4. Reconstructing from coarse to fine using the interpolated Laplacians
 * 
 * Key insight: By using Laplacians from tone-mapped images rather than the original,
 * we achieve local tone mapping without the halos that plague naive approaches.
 */
public class LocalLaplacian extends Stage implements IntermediateProvider {
    private static final String TAG = "LocalLaplacian";
    
    // Maximum pyramid levels (darktable uses 30, but we limit for mobile)
    private static final int MAX_LEVELS = 10;
    
    // Number of gamma reference levels for interpolation
    private static final int NUM_GAMMA = 6;
    
    // Output texture
    private Texture mOutput;
    
    // Processing parameters (from darktable's bilat.c local_laplacian mode)
    // Defaults: midtone=0.5, sigma_s=0.5, sigma_r=0.5, detail=0.25 in darktable
    // However, clarity=0.25 was too contrasty, so reduced to 0.1 for more subtle effect
    private float mSigma = 0.2f;      // Transition width shadows/midtones/highlights
    private float mShadows = 1.0f;    // Shadow boost (1.0 = no change, >1 = lift)
    private float mHighlights = 1.0f; // Highlight compression (1.0 = no change, <1 = compress)
    private float mClarity = 0.1f;   // Local contrast/midtone detail (reduced from 0.25)
    
    public LocalLaplacian() {
        // Default parameters matching darktable's "clarity" preset
    }
    
    public LocalLaplacian(float shadows, float highlights, float clarity) {
        this.mShadows = shadows;
        this.mHighlights = highlights;
        this.mClarity = clarity;
    }
    
    @Override
    public Texture getIntermediate() {
        return mOutput;
    }
    
    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();
        ProcessParams process = getProcessParams();
        
        // Get input texture (xyY format)
        Texture input = previousStages.getStageByInterface(IntermediateProvider.class).getIntermediate();
        int width = input.getWidth();
        int height = input.getHeight();
        
        // Auto-tune if enabled
        if (process != null && process.localLaplacianAutoTune) {
            Analysis analysis = previousStages.getStage(Analysis.class);
            if (analysis != null) {
                Log.d(TAG, "Auto-tuning Local Laplacian parameters from image analysis");
                AutoTune.autoTuneFromAnalysis(process, analysis, true, false);
            } else {
                Log.w(TAG, "Analysis stage not found, using manual parameters");
            }
        }
        
        // Load parameters from ProcessParams if available
        if (process != null) {
            mShadows = process.localLaplacianShadows;
            mHighlights = process.localLaplacianHighlights;
            mClarity = process.localLaplacianClarity;
            mSigma = process.localLaplacianSigma;
        }
        
        Log.d(TAG, String.format("LocalLaplacian: %dx%d, shadows=%.2f, highlights=%.2f, clarity=%.2f, sigma=%.2f",
                width, height, mShadows, mHighlights, mClarity, mSigma));
        
        // Calculate pyramid parameters
        int numLevels = Math.min(MAX_LEVELS, 31 - Integer.numberOfLeadingZeros(Math.min(width, height)));
        int maxSupp = 1 << (numLevels - 1);
        int paddedWidth = width + 2 * maxSupp;
        int paddedHeight = height + 2 * maxSupp;
        
        Log.d(TAG, String.format("Pyramid: %d levels, maxSupp=%d, padded=%dx%d", 
                numLevels, maxSupp, paddedWidth, paddedHeight));
        
        // Allocate pyramid textures
        Texture[] padded = new Texture[numLevels];
        Texture[] output = new Texture[numLevels];
        Texture[][] processed = new Texture[NUM_GAMMA][numLevels];
        
        try {
            // Allocate all textures
            for (int l = 0; l < numLevels; l++) {
                int lw = dl(paddedWidth, l);
                int lh = dl(paddedHeight, l);
                padded[l] = TexturePool.get(lw, lh, 1, Texture.Format.Float16);
                output[l] = TexturePool.get(lw, lh, 1, Texture.Format.Float16);
                for (int k = 0; k < NUM_GAMMA; k++) {
                    processed[k][l] = TexturePool.get(lw, lh, 1, Texture.Format.Float16);
                }
            }
            
            // Step 1: Pad input
            converter.useProgram(R.raw.ll_pad_input);
            converter.setTexture("input_tex", input);
            converter.seti("input_width", width);
            converter.seti("input_height", height);
            converter.seti("max_supp", maxSupp);
            converter.seti("padded_width", paddedWidth);
            converter.seti("padded_height", paddedHeight);
            converter.drawBlocks(padded[0]);
            
            // Step 2: Build Gaussian pyramid of padded input
            for (int l = 1; l < numLevels; l++) {
                int lw = dl(paddedWidth, l);
                int lh = dl(paddedHeight, l);
                int prevLw = dl(paddedWidth, l - 1);
                int prevLh = dl(paddedHeight, l - 1);
                
                converter.useProgram(R.raw.ll_gauss_reduce);
                converter.setTexture("input_tex", padded[l - 1]);
                converter.seti("input_width", prevLw);
                converter.seti("input_height", prevLh);
                
                // For the coarsest level, write directly to output
                if (l == numLevels - 1) {
                    converter.drawBlocks(output[l]);
                } else {
                    converter.drawBlocks(padded[l]);
                }
            }
            
            // Step 3: Process curve for each gamma level
            for (int k = 0; k < NUM_GAMMA; k++) {
                float gamma = (k + 0.5f) / (float) NUM_GAMMA;
                
                // Apply curve to finest level
                converter.useProgram(R.raw.ll_process_curve);
                converter.setTexture("input_tex", padded[0]);
                converter.setf("gamma", gamma);
                converter.setf("sigma", mSigma);
                converter.setf("shadows", mShadows);
                converter.setf("highlights", mHighlights);
                converter.setf("clarity", mClarity);
                converter.seti("width", paddedWidth);
                converter.seti("height", paddedHeight);
                converter.drawBlocks(processed[k][0]);
                
                // Build Gaussian pyramid for this gamma level
                for (int l = 1; l < numLevels; l++) {
                    int lw = dl(paddedWidth, l);
                    int lh = dl(paddedHeight, l);
                    int prevLw = dl(paddedWidth, l - 1);
                    int prevLh = dl(paddedHeight, l - 1);
                    
                    converter.useProgram(R.raw.ll_gauss_reduce);
                    converter.setTexture("input_tex", processed[k][l - 1]);
                    converter.seti("input_width", prevLw);
                    converter.seti("input_height", prevLh);
                    converter.drawBlocks(processed[k][l]);
                }
            }
            
            // Step 4: Assemble output pyramid from coarse to fine
            for (int l = numLevels - 2; l >= 0; l--) {
                int lw = dl(paddedWidth, l);
                int lh = dl(paddedHeight, l);
                
                converter.useProgram(R.raw.ll_laplacian_assemble);
                converter.setTexture("input_tex", padded[l]);
                converter.setTexture("output_coarse", output[l + 1]);
                
                // Set all gamma level textures
                converter.setTexture("gamma0_fine", processed[0][l]);
                converter.setTexture("gamma0_coarse", processed[0][l + 1]);
                converter.setTexture("gamma1_fine", processed[1][l]);
                converter.setTexture("gamma1_coarse", processed[1][l + 1]);
                converter.setTexture("gamma2_fine", processed[2][l]);
                converter.setTexture("gamma2_coarse", processed[2][l + 1]);
                converter.setTexture("gamma3_fine", processed[3][l]);
                converter.setTexture("gamma3_coarse", processed[3][l + 1]);
                converter.setTexture("gamma4_fine", processed[4][l]);
                converter.setTexture("gamma4_coarse", processed[4][l + 1]);
                converter.setTexture("gamma5_fine", processed[5][l]);
                converter.setTexture("gamma5_coarse", processed[5][l + 1]);
                
                converter.seti("fine_width", lw);
                converter.seti("fine_height", lh);
                converter.drawBlocks(output[l]);
            }
            
            // Step 5: Write back to output, combining with original chromaticity
            mOutput = TexturePool.get(width, height, input.getChannels(), input.getFormat());
            
            converter.useProgram(R.raw.ll_write_back);
            converter.setTexture("input_tex", input);
            converter.setTexture("processed_tex", output[0]);
            converter.seti("max_supp", maxSupp);
            converter.seti("output_width", width);
            converter.seti("output_height", height);
            converter.drawBlocks(mOutput);
            
            Log.d(TAG, "LocalLaplacian complete");
            
        } finally {
            // Clean up all temporary textures
            for (int l = 0; l < numLevels; l++) {
                if (padded[l] != null) padded[l].close();
                if (output[l] != null) output[l].close();
                for (int k = 0; k < NUM_GAMMA; k++) {
                    if (processed[k][l] != null) processed[k][l].close();
                }
            }
        }
    }
    
    /**
     * Compute downsampled dimension at given pyramid level.
     * Each level is approximately half the previous level's size.
     */
    private static int dl(int size, int level) {
        for (int l = 0; l < level; l++) {
            size = (size - 1) / 2 + 1;
        }
        return size;
    }
    
    @Override
    public int getShader() {
        // Return first shader for identification (actual shaders set in execute)
        return R.raw.ll_pad_input;
    }
    
    @Override
    public boolean isEnabled() {
        ProcessParams process = getProcessParams();
        boolean enabled = process != null && process.localLaplacianEnabled;
        Log.d(TAG, "LocalLaplacian.isEnabled(): process=" + (process != null) + 
                ", localLaplacianEnabled=" + (process != null ? process.localLaplacianEnabled : "null") + 
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
