package amirz.dngprocessor.pipeline.intermediate;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;

import static android.opengl.GLES20.*;
import static amirz.dngprocessor.util.Constants.BLOCK_HEIGHT;

/**
 * Contrast Limited Adaptive Histogram Equalization (CLAHE).
 * 
 * Multi-scale CLAHE implementation inspired by convert_uraw_v5.1.sh script.
 * Applies CLAHE at multiple tile sizes (large, medium, small) similar to the script's
 * approach with percentage-based radii (33%, 20%, 10%, etc.).
 * 
 * Features:
 * - Multi-scale tile processing (large, medium, small based on image dimensions)
 * - Contrast limiting (clip limit parameter)
 * - Bilinear interpolation between tiles
 * - Separate processing for shadows and highlights
 * - Works on luminance channel (Y in xyY space)
 */
public class CLAHE extends Stage implements IntermediateProvider {
    private static final String TAG = "CLAHE";
    
    // Default number of histogram bins
    private static final int HIST_BINS = 256;
    
    // Tile size percentages (inspired by convert_uraw script)
    // Large: 33% of dimension (like contrast_radius_large_shadows/highlights)
    // Medium: 20% of dimension (like contrast_radius_mid_shadows)
    // Small: 10% of dimension (like contrast_radius_small_shadows/highlights)
    // Mini: 0.5% of dimension (like contrast_radius_mini_highlights)
    // XMini: 0.22% of dimension (like contrast_radius_xmini_highlights)
    private static final float TILE_SIZE_LARGE = 0.33f;
    private static final float TILE_SIZE_MEDIUM = 0.20f;
    private static final float TILE_SIZE_SMALL = 0.10f;
    private static final float TILE_SIZE_MINI = 0.005f;
    private static final float TILE_SIZE_XMINI = 0.0022f;
    
    // Default clip limit (3.0 = 3x average, similar to script's default)
    private static final float DEFAULT_CLIP_LIMIT = 3.0f;
    
    private final ProcessParams mProcess;
    private Texture mIntermediate;
    private boolean mOwnsTexture = false;
    
    // CLAHE parameters
    private final float mClipLimit;
    private final float mStrength;
    
    public CLAHE(ProcessParams processParams) {
        mProcess = processParams;
        
        // Get CLAHE parameters from ProcessParams
        mClipLimit = processParams.claheClipLimit > 0 ? processParams.claheClipLimit : DEFAULT_CLIP_LIMIT;
        mStrength = processParams.claheStrength > 0 ? processParams.claheStrength : 1.0f;
    }
    
    public Texture getIntermediate() {
        return mIntermediate;
    }
    
    /**
     * Calculate tile size in pixels from percentage of dimension.
     */
    private int calculateTileSize(int dimension, float percentage) {
        return Math.max(8, (int) (dimension * percentage));
    }
    
    /**
     * Compute histogram for a single tile region.
     */
    private int[] computeTileHistogram(float[] luminance, int width, int height,
                                       int tileX, int tileY, int tileSize) {
        int[] hist = new int[HIST_BINS];
        
        int startX = tileX * tileSize;
        int startY = tileY * tileSize;
        int endX = Math.min(startX + tileSize, width);
        int endY = Math.min(startY + tileSize, height);
        
        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int idx = y * width + x;
                if (idx < luminance.length) {
                    float val = luminance[idx];
                    int bin = (int) (val * HIST_BINS);
                    if (bin < 0) bin = 0;
                    if (bin >= HIST_BINS) bin = HIST_BINS - 1;
                    hist[bin]++;
                }
            }
        }
        
        return hist;
    }
    
    /**
     * Clip histogram and redistribute excess pixels.
     * This is the core of CLAHE - limits contrast amplification.
     */
    private int[] clipAndRedistribute(int[] hist, int pixelCount, float clipLimit) {
        int[] clipped = new int[HIST_BINS];
        System.arraycopy(hist, 0, clipped, 0, HIST_BINS);
        
        // Calculate clip limit (as multiple of average)
        int avgCount = pixelCount / HIST_BINS;
        int clipThreshold = (int) (avgCount * clipLimit);
        
        // Clip bins that exceed threshold and collect excess
        int excess = 0;
        for (int i = 0; i < HIST_BINS; i++) {
            if (clipped[i] > clipThreshold) {
                excess += clipped[i] - clipThreshold;
                clipped[i] = clipThreshold;
            }
        }
        
        // Redistribute excess evenly across all bins
        int excessPerBin = excess / HIST_BINS;
        int excessRemainder = excess % HIST_BINS;
        
        for (int i = 0; i < HIST_BINS; i++) {
            clipped[i] += excessPerBin;
            if (i < excessRemainder) {
                clipped[i]++;
            }
        }
        
        return clipped;
    }
    
    /**
     * Build cumulative distribution function (CDF) from histogram.
     */
    private float[] buildCDF(int[] hist, int pixelCount) {
        float[] cdf = new float[HIST_BINS];
        int sum = 0;
        
        for (int i = 0; i < HIST_BINS; i++) {
            sum += hist[i];
            cdf[i] = (float) sum / pixelCount;
        }
        
        return cdf;
    }
    
    /**
     * Compute CLAHE lookup tables for all tiles at a given scale.
     * Returns a 2D array: [tileY][tileX][bin] = equalized value
     */
    private float[][][] computeCLAHELUTs(float[] luminance, int width, int height, int tileSize) {
        int numTilesX = (width + tileSize - 1) / tileSize;
        int numTilesY = (height + tileSize - 1) / tileSize;
        
        float[][][] luts = new float[numTilesY][numTilesX][HIST_BINS];
        
        for (int ty = 0; ty < numTilesY; ty++) {
            for (int tx = 0; tx < numTilesX; tx++) {
                // Compute histogram for this tile
                int[] hist = computeTileHistogram(luminance, width, height, tx, ty, tileSize);
                
                // Calculate pixel count for this tile
                int startX = tx * tileSize;
                int startY = ty * tileSize;
                int endX = Math.min(startX + tileSize, width);
                int endY = Math.min(startY + tileSize, height);
                int pixelCount = (endX - startX) * (endY - startY);
                
                // Clip and redistribute
                int[] clipped = clipAndRedistribute(hist, pixelCount, mClipLimit);
                
                // Build CDF
                float[] cdf = buildCDF(clipped, pixelCount);
                
                // Store CDF as LUT
                System.arraycopy(cdf, 0, luts[ty][tx], 0, HIST_BINS);
            }
        }
        
        return luts;
    }
    
    /**
     * Apply CLAHE at a single scale and return enhanced luminance.
     */
    private float[] applyCLAHEAtScale(float[] luminance, int width, int height, int tileSize, float strength) {
        float[][][] luts = computeCLAHELUTs(luminance, width, height, tileSize);
        
        int numTilesX = (width + tileSize - 1) / tileSize;
        int numTilesY = (height + tileSize - 1) / tileSize;
        
        float[] enhanced = new float[luminance.length];
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                float luma = luminance[idx];
                
                // Calculate which tiles this pixel is between
                float tileX = (float) x / tileSize;
                float tileY = (float) y / tileSize;
                
                int tileX0 = (int) Math.floor(tileX);
                int tileY0 = (int) Math.floor(tileY);
                int tileX1 = tileX0 + 1;
                int tileY1 = tileY0 + 1;
                
                // Clamp tile indices
                tileX0 = Math.max(0, Math.min(tileX0, numTilesX - 1));
                tileY0 = Math.max(0, Math.min(tileY0, numTilesY - 1));
                tileX1 = Math.max(0, Math.min(tileX1, numTilesX - 1));
                tileY1 = Math.max(0, Math.min(tileY1, numTilesY - 1));
                
                // Calculate interpolation weights
                float fx = tileX - tileX0;
                float fy = tileY - tileY0;
                
                // Convert luminance to bin index
                int bin = (int) Math.max(0, Math.min(luma * HIST_BINS, HIST_BINS - 1));
                
                // Sample LUTs from 4 neighboring tiles
                float lut00 = luts[tileY0][tileX0][bin];
                float lut01 = luts[tileY0][tileX1][bin];
                float lut10 = luts[tileY1][tileX0][bin];
                float lut11 = luts[tileY1][tileX1][bin];
                
                // Bilinear interpolation
                float lut0 = lut00 * (1 - fx) + lut01 * fx;
                float lut1 = lut10 * (1 - fx) + lut11 * fx;
                float equalized = lut0 * (1 - fy) + lut1 * fy;
                
                // Blend with original based on strength
                enhanced[idx] = luma * (1 - strength) + equalized * strength;
            }
        }
        
        return enhanced;
    }
    
    @Override
    protected void execute(StagePipeline.StageMap previousStages) {
        // Get input from most recent IntermediateProvider (could be Merge, MergeDetail, LocalLaplacian, ToneEqualizer, etc.)
        Texture inputIntermediate = previousStages.getStageByInterface(IntermediateProvider.class).getIntermediate();
        
        if (mStrength <= 0.0f) {
            // CLAHE disabled, pass through input
            mIntermediate = inputIntermediate;
            mOwnsTexture = false;
            return;
        }
        
        GLPrograms converter = getConverter();
        
        int width = inputIntermediate.getWidth();
        int height = inputIntermediate.getHeight();
        
        // Use smaller dimension as reference (like script uses dimension = min(width, height))
        int refDim = Math.min(width, height);
        
        // Calculate tile sizes based on image dimensions (percentage-based, like script)
        int tileSizeLarge = calculateTileSize(refDim, TILE_SIZE_LARGE);
        int tileSizeMedium = calculateTileSize(refDim, TILE_SIZE_MEDIUM);
        int tileSizeSmall = calculateTileSize(refDim, TILE_SIZE_SMALL);
        
        Log.d(TAG, String.format("CLAHE: %dx%d (ref=%d), tiles: large=%d, medium=%d, small=%d, clipLimit=%.2f, strength=%.2f",
                width, height, refDim, tileSizeLarge, tileSizeMedium, tileSizeSmall, mClipLimit, mStrength));
        
        // Read luminance channel (Y from xyY) in blocks to avoid OOM
        float[] luminance = new float[width * height];
        try (Texture luminanceTex = TexturePool.get(width, height, 1, Texture.Format.Float16)) {
            // Extract luminance channel using a simple shader pass
            converter.useProgram(R.raw.stage_clahe_extract_luma);
            converter.setTexture("intermediate", inputIntermediate);
            converter.drawBlocks(luminanceTex);
            
            // Ensure framebuffer is set for reading
            luminanceTex.setFrameBuffer();
            
            // Read back luminance data in blocks to avoid large memory allocation
            // Process in horizontal strips (like the pipeline does)
            int blockHeight = Math.min(BLOCK_HEIGHT, height);
            int blockSize = width * blockHeight;
            
            // Allocate buffer for one block (much smaller than full image)
            float[] rgbaBlock = new float[blockSize * 4];
            FloatBuffer fb = ByteBuffer.allocateDirect(rgbaBlock.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            
            // Read image in blocks
            for (int y = 0; y < height; y += blockHeight) {
                int currentBlockHeight = Math.min(blockHeight, height - y);
                int currentBlockSize = width * currentBlockHeight;
                
                // Adjust buffer size if last block is smaller
                if (currentBlockSize < blockSize) {
                    rgbaBlock = new float[currentBlockSize * 4];
                    fb = ByteBuffer.allocateDirect(rgbaBlock.length * 4)
                            .order(ByteOrder.nativeOrder())
                            .asFloatBuffer();
                }
                
                // Read this block
                glReadPixels(0, y, width, currentBlockHeight, GL_RGBA, GL_FLOAT, fb);
                fb.rewind();
                fb.get(rgbaBlock);
                fb.rewind();
                
                // Extract red channel (luminance) from RGBA data and store in luminance array
                int baseIdx = y * width;
                for (int i = 0; i < currentBlockSize; i++) {
                    luminance[baseIdx + i] = rgbaBlock[i * 4];
                }
            }
        }
        
        // Apply CLAHE at multiple scales (like script applies multiple -clahe passes)
        // Each scale computes its contribution independently from the original,
        // then we combine them additively. This ensures each tile size has its own distinct effect.
        
        // Compute each scale's enhancement independently from the original
        float[] largeEnhanced = applyCLAHEAtScale(luminance, width, height, tileSizeLarge, 0.3f * mStrength);
        float[] mediumEnhanced = applyCLAHEAtScale(luminance, width, height, tileSizeMedium, 0.4f * mStrength);
        float[] smallEnhanced = applyCLAHEAtScale(luminance, width, height, tileSizeSmall, 0.3f * mStrength);
        
        // Combine enhancements additively: each scale adds its contribution to the original
        float[] enhanced = new float[luminance.length];
        for (int i = 0; i < luminance.length; i++) {
            float original = luminance[i];
            // Each scale's contribution is the difference from original
            float largeContribution = largeEnhanced[i] - original;
            float mediumContribution = mediumEnhanced[i] - original;
            float smallContribution = smallEnhanced[i] - original;
            // Combine all contributions additively
            enhanced[i] = original + largeContribution + mediumContribution + smallContribution;
        }
        
        // Upload enhanced luminance as texture for final pass
        FloatBuffer enhancedBuffer = ByteBuffer.allocateDirect(enhanced.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        enhancedBuffer.put(enhanced);
        enhancedBuffer.rewind();
        
        Texture enhancedLumaTex = new Texture(width, height, 1, Texture.Format.Float16,
                enhancedBuffer, GL_LINEAR, GL_CLAMP_TO_EDGE);
        
        // Apply CLAHE enhancement to xyY intermediate
        converter.useProgram(R.raw.stage_clahe_fs);
        converter.setTexture("intermediate", inputIntermediate);
        converter.setTexture("enhancedLuma", enhancedLumaTex);
        converter.setf("strength", mStrength);
        
        // Create output texture
        mIntermediate = TexturePool.get(width, height, 
                inputIntermediate.getChannels(), inputIntermediate.getFormat());
        mOwnsTexture = true;
        
        converter.drawBlocks(mIntermediate);
        
        enhancedLumaTex.close();
    }
    
    @Override
    public int getShader() {
        return R.raw.stage_clahe_fs;
    }
    
    @Override
    public void close() {
        if (mOwnsTexture && mIntermediate != null) {
            mIntermediate.close();
            mIntermediate = null;
            mOwnsTexture = false;
        }
    }
}
