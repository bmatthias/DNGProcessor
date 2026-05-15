package amirz.dngprocessor.pipeline.post;

import android.util.Log;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.params.SensorParams;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;

/**
 * Gradient Domain HDR Compression Stage
 * 
 * This stage implements gradient domain compression for HDR images:
 * 1. Computes spatial gradients (horizontal and vertical)
 * 2. Compresses large gradients more than small ones
 * 3. Reconstructs the image from compressed gradients using iterative Poisson solver
 * 
 * This preserves local contrast while compressing global dynamic range,
 * resulting in more natural-looking HDR compression with reduced halos.
 */
public class GradientDomainCompression extends Stage {
    private static final String TAG = "GradientDomainCompression";
    
    private static final int JACOBI_ITERATIONS = 10;  // Number of reconstruction iterations
    private static final float JACOBI_ALPHA = 0.25f;  // Jacobi iteration parameter
    private static final float DEFAULT_COMPRESSION_STRENGTH = 0.5f;  // Default compression strength (0.0-1.0)
    
    private Texture mInputTexture;
    private int mWidth;
    private int mHeight;
    
    @Override
    protected boolean isEnabled() {
        // Only enable if gradient domain compression is selected
        ProcessParams process = getProcessParams();
        // Check if gradient domain is selected (method 5 for baseline exposure compression)
        // Note: Gradient Domain is not available as an HDR compression method
        return process.baselineExposureCompression == 5;
    }
    
    @Override
    protected void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();
        SensorParams sensor = getSensorParams();
        ProcessParams process = getProcessParams();
        
        // Get input texture from previous stage
        // For baseline exposure, get from PreProcess or LinearRawPreProcess
        // For HDR compression, get from ToneMap stage
        ToneMap toneMap = previousStages.getStage(ToneMap.class);
        if (toneMap != null) {
            // We need to get the texture before tone mapping
            // This is a bit tricky - we might need to modify ToneMap to expose intermediate textures
            Log.w(TAG, "Gradient domain compression after tone mapping not yet supported");
            return;
        }
        
        // For now, we'll implement this as a post-processing stage that can be inserted
        // after the initial processing but before final tone mapping
        // This requires getting the appropriate input texture
        
        // For baseline exposure compression, we'd need to get the texture after PreProcess
        // but this stage runs later in the pipeline
        // Let me check what stages are available...
        
        Log.w(TAG, "Gradient domain compression requires input texture from appropriate stage");
        // TODO: Implement proper texture retrieval based on where this stage is inserted
    }
    
    /**
     * Execute gradient domain compression on a given input texture
     * This is a helper method that can be called from other stages
     */
    public Texture executeOnTexture(Texture inputTexture, float baselineExposure, float compressionStrength) {
        GLPrograms converter = getConverter();
        
        mWidth = inputTexture.getWidth();
        mHeight = inputTexture.getHeight();
        
        // Step 1: Compute gradients
        Texture gradientTexture = computeGradients(converter, inputTexture);
        
        // Step 2: Compress gradients
        Texture compressedGradientTexture = compressGradients(converter, gradientTexture, 
                baselineExposure, compressionStrength);
        gradientTexture.close();  // Free intermediate texture
        
        // Step 3: Reconstruct image iteratively
        Texture result = reconstructImage(converter, compressedGradientTexture, inputTexture);
        compressedGradientTexture.close();  // Free intermediate texture
        
        return result;
    }
    
    private Texture computeGradients(GLPrograms converter, Texture input) {
        converter.useProgram(R.raw.stage_gradient_compute_fs);
        
        Texture gradientTex = TexturePool.get(mWidth, mHeight, 4, Texture.Format.Float16);
        
        converter.setTexture("inputTexture", input);
        converter.seti("textureSize", mWidth, mHeight);
        
        converter.drawBlocks(gradientTex);
        
        return gradientTex;
    }
    
    private Texture compressGradients(GLPrograms converter, Texture gradientTexture, 
            float baselineExposure, float compressionStrength) {
        converter.useProgram(R.raw.stage_gradient_compress_fs);
        
        Texture compressedTex = TexturePool.get(mWidth, mHeight, 4, Texture.Format.Float16);
        
        converter.setTexture("gradientTexture", gradientTexture);
        converter.setf("compressionStrength", compressionStrength);
        converter.setf("baselineExposure", baselineExposure);
        
        converter.drawBlocks(compressedTex);
        
        return compressedTex;
    }
    
    private Texture reconstructImage(GLPrograms converter, Texture compressedGradientTexture, 
            Texture originalTexture) {
        converter.useProgram(R.raw.stage_gradient_reconstruct_fs);
        
        // Create two textures for ping-pong iteration
        Texture current = TexturePool.get(mWidth, mHeight, 4, Texture.Format.Float16);
        Texture next = TexturePool.get(mWidth, mHeight, 4, Texture.Format.Float16);
        
        // Initialize with original texture (convert RGB to grayscale for reconstruction)
        // For simplicity, we'll use the original as initial guess
        // In a full implementation, we'd convert RGB to luminance here
        
        converter.setTexture("compressedGradientTexture", compressedGradientTexture);
        converter.seti("textureSize", mWidth, mHeight);
        converter.setf("alpha", JACOBI_ALPHA);
        
        // Copy original to current as initial guess
        // For now, we'll use a simple approach: use original texture as initial
        Texture initial = originalTexture;  // We'll use this as the starting point
        
        for (int iter = 0; iter < JACOBI_ITERATIONS; iter++) {
            converter.setTexture("previousIteration", iter == 0 ? initial : current);
            converter.seti("iteration", iter);
            
            converter.drawBlocks(next);
            
            // Ping-pong
            Texture temp = current;
            current = next;
            next = temp;
        }
        
        next.close();  // Free unused texture
        
        return current;
    }
    
    @Override
    public int getShader() {
        // This stage uses multiple shaders, so we return the first one
        // The execute method handles shader switching
        return R.raw.stage_gradient_compute_fs;
    }
}

