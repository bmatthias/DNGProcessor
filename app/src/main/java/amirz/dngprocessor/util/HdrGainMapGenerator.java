package amirz.dngprocessor.util;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Utility class for generating HDR gain maps from SDR and HDR bitmaps.
 * 
 * The gain map encodes the per-pixel ratio between HDR and SDR images,
 * allowing HDR displays to reconstruct the HDR image from the SDR base.
 * 
 * Gain map formula: gain = HDR_value / SDR_value (with proper handling for edge cases)
 * 
 * Uses multi-threaded processing for improved performance on large images.
 */
public class HdrGainMapGenerator {
    private static final String TAG = "HdrGainMapGenerator";
    
    // Minimum SDR value to avoid division by zero
    private static final float MIN_SDR_VALUE = 0.001f;
    
    // Maximum gain value to prevent extreme values
    private static final float MAX_GAIN = 16.0f;
    
    // Minimum gain value (should be >= 1.0 for valid HDR)
    private static final float MIN_GAIN = 1.0f;
    
    // Precomputed log2(17) for logarithmic decoding
    private static final float LOG_MAX = FastMath.getLog2_17();
    
    // Precomputed constants for fast pow approximations
    private static final float INV_1_055 = 1.0f / 1.055f;
    
    // Lookup table for fast 2^x approximation (for x in [0, LOG_MAX])
    private static final int POW2_LUT_SIZE = 256;
    private static final float[] POW2_LUT = new float[POW2_LUT_SIZE];
    
    static {
        // Precompute 2^(x * LOG_MAX / LUT_SIZE) for fast logarithmic decoding
        for (int i = 0; i < POW2_LUT_SIZE; i++) {
            float x = (float) i / POW2_LUT_SIZE * LOG_MAX;
            POW2_LUT[i] = (float) Math.pow(2, x);
        }
    }
    
    /**
     * Computes a gain map from SDR and HDR bitmaps.
     * 
     * The gain map is computed in linear RGB space for accuracy.
     * Each pixel's gain is calculated as: gain = HDR_linear / SDR_linear
     * 
     * Processes images in chunks to avoid OutOfMemoryError on large images.
     * 
     * @param sdrBitmap The SDR (standard dynamic range) bitmap
     * @param hdrBitmap The HDR (high dynamic range) bitmap (must be same size as SDR)
     * @return Gain map bitmap (grayscale, values represent gain ratios)
     */
    public static Bitmap computeGainMap(Bitmap sdrBitmap, Bitmap hdrBitmap) {
        if (sdrBitmap == null || hdrBitmap == null) {
            Log.e(TAG, "Input bitmaps cannot be null");
            return null;
        }
        
        if (sdrBitmap.getWidth() != hdrBitmap.getWidth() || 
            sdrBitmap.getHeight() != hdrBitmap.getHeight()) {
            Log.e(TAG, "SDR and HDR bitmaps must have the same dimensions");
            return null;
        }
        
        int width = sdrBitmap.getWidth();
        int height = sdrBitmap.getHeight();
        
        long startTime = System.currentTimeMillis();
        
        // Create gain map bitmap (grayscale)
        Bitmap gainMap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        
        // Process in chunks to avoid OOM on large images
        // Target chunk size: ~10MB per chunk (3 arrays: sdr, hdr, gain)
        // Each pixel is 4 bytes, so 10MB = 2.5M pixels = ~2500 rows for 8176 width
        // Use smaller chunks (512 rows) to be safe and allow parallel processing
        final int TARGET_CHUNK_ROWS = 512;
        int rowsPerChunk = Math.min(TARGET_CHUNK_ROWS, height);
        
        // Use multi-threading for parallel processing
        int numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1); // Leave one core free
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        // Process chunks sequentially to control memory usage, but process rows within chunks in parallel
        int chunks = (height + rowsPerChunk - 1) / rowsPerChunk;
        
        try {
            for (int chunk = 0; chunk < chunks; chunk++) {
                final int startRow = chunk * rowsPerChunk;
                final int endRow = Math.min(startRow + rowsPerChunk, height);
                final int chunkHeight = endRow - startRow;
                
                // Allocate pixel arrays only for this chunk
                int chunkPixelCount = width * chunkHeight;
                final int[] sdrPixels = new int[chunkPixelCount];
                final int[] hdrPixels = new int[chunkPixelCount];
                final int[] gainPixels = new int[chunkPixelCount];
                
                // Extract pixels for this chunk only
                sdrBitmap.getPixels(sdrPixels, 0, width, 0, startRow, width, chunkHeight);
                hdrBitmap.getPixels(hdrPixels, 0, width, 0, startRow, width, chunkHeight);
                
                // Process this chunk in parallel sub-chunks
                int subChunkRows = Math.max(1, chunkHeight / numThreads);
                int subChunks = (chunkHeight + subChunkRows - 1) / subChunkRows;
                
                // Use CountDownLatch to wait for all sub-chunks in this chunk to complete
                CountDownLatch latch = new CountDownLatch(subChunks);
                
                for (int subChunk = 0; subChunk < subChunks; subChunk++) {
                    final int subStartRow = subChunk * subChunkRows;
                    final int subEndRow = Math.min(subStartRow + subChunkRows, chunkHeight);
                    final int subChunkStartIndex = subStartRow * width;
                    final int subChunkEndIndex = subEndRow * width;
                    
                    executor.submit(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                processPixelChunk(sdrPixels, hdrPixels, gainPixels, 
                                                 subChunkStartIndex, subChunkEndIndex, width);
                            } finally {
                                latch.countDown();
                            }
                        }
                    });
                }
                
                // Wait for this chunk to complete before moving to next chunk
                if (!latch.await(120, TimeUnit.SECONDS)) {
                    Log.w(TAG, "Gain map generation timed out after 2 minutes");
                    executor.shutdownNow();
                    return null;
                }
                
                // Write processed chunk directly to output bitmap
                gainMap.setPixels(gainPixels, 0, width, 0, startRow, width, chunkHeight);
                
                // Arrays will go out of scope here, allowing GC to reclaim memory before next chunk
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Gain map generation interrupted", e);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            return null;
        } finally {
            executor.shutdown();
        }
        
        long elapsedTime = System.currentTimeMillis() - startTime;
        Log.d(TAG, "Generated gain map: " + width + "x" + height + " in " + elapsedTime + "ms (using " + numThreads + " threads)");
        return gainMap;
    }
    
    /**
     * Processes a chunk of pixels for gain map generation.
     * This method is called from worker threads for parallel processing.
     * 
     * @param sdrPixels SDR pixel array
     * @param hdrPixels HDR pixel array
     * @param gainPixels Output gain map pixel array
     * @param startIndex Start index in pixel arrays
     * @param endIndex End index (exclusive) in pixel arrays
     * @param width Image width (for row calculation if needed)
     */
    private static void processPixelChunk(int[] sdrPixels, int[] hdrPixels, int[] gainPixels,
                                         int startIndex, int endIndex, int width) {
        for (int i = startIndex; i < endIndex; i++) {
            int sdrPixel = sdrPixels[i];
            int hdrPixel = hdrPixels[i];
            
            // Extract RGB components (0-255 range)
            float sdrR = Color.red(sdrPixel) / 255.0f;
            float sdrG = Color.green(sdrPixel) / 255.0f;
            float sdrB = Color.blue(sdrPixel) / 255.0f;
            
            float hdrR = Color.red(hdrPixel) / 255.0f;
            float hdrG = Color.green(hdrPixel) / 255.0f;
            float hdrB = Color.blue(hdrPixel) / 255.0f;
            
            // Both HDR and SDR are now gamma-encoded (no log encoding)
            // HDR uses 2.2 gamma on ProPhoto values
            // SDR uses sRGB gamma on sRGB values
            // For gain map, we compare their linear values
            
            // Convert SDR from sRGB gamma to linear
            float sdrRLinear = srgbToLinear(sdrR);
            float sdrGLinear = srgbToLinear(sdrG);
            float sdrBLinear = srgbToLinear(sdrB);
            
            // Convert HDR from 2.2 gamma to linear (simple power function)
            // Using 2.4 approximation for consistency with sRGB
            float hdrRLinear = (float) Math.pow(hdrR, 2.2);
            float hdrGLinear = (float) Math.pow(hdrG, 2.2);
            float hdrBLinear = (float) Math.pow(hdrB, 2.2);
            
            // Compute luminance in linear space
            float sdrLuma = 0.2126f * sdrRLinear + 0.7152f * sdrGLinear + 0.0722f * sdrBLinear;
            float hdrLuma = 0.2126f * hdrRLinear + 0.7152f * hdrGLinear + 0.0722f * hdrBLinear;
            
            // Compute gain map using log2 encoding for better range handling
            // gain = HDR / SDR, stored as log2(gain) mapped to [0, 1]
            // log2(1) = 0 → 0.5 (no gain)
            // log2(2) = 1 → higher (2x brighter in HDR)
            // log2(0.5) = -1 → lower (SDR brighter)
            float gain;
            if (sdrLuma < MIN_SDR_VALUE && hdrLuma < MIN_SDR_VALUE) {
                gain = 1.0f; // Both dark, no gain
            } else if (sdrLuma < MIN_SDR_VALUE) {
                gain = MAX_GAIN; // SDR is black, HDR has value
            } else if (hdrLuma < MIN_SDR_VALUE) {
                gain = 1.0f / MAX_GAIN; // HDR is black, SDR has value
            } else {
                gain = hdrLuma / sdrLuma;
                gain = Math.max(1.0f / MAX_GAIN, Math.min(MAX_GAIN, gain));
            }
            
            // Use log2 encoding: log2(gain) maps gain range to linear scale
            // log2(1/16) = -4, log2(1) = 0, log2(16) = 4
            // Map [-4, 4] to [0, 1] so gain=1 is 0.5 (middle gray)
            float log2Gain = (float) (Math.log(gain) / Math.log(2));
            float normalizedGain = (log2Gain + 4.0f) / 8.0f;
            normalizedGain = Math.max(0.0f, Math.min(1.0f, normalizedGain));
            
            // Store as grayscale
            int grayValue = (int) (normalizedGain * 255.0f);
            gainPixels[i] = Color.rgb(grayValue, grayValue, grayValue);
        }
    }
    
    /**
     * Fast approximation of 2^x using lookup table and linear interpolation.
     * Much faster than Math.pow(2, x) for repeated calls.
     */
    private static float fastPow2(float x) {
        if (x <= 0.0f) {
            return 1.0f;
        }
        if (x >= LOG_MAX) {
            return (float) Math.pow(2, LOG_MAX); // Fallback for edge case
        }
        
        // Use lookup table with linear interpolation
        float index = x / LOG_MAX * (POW2_LUT_SIZE - 1);
        int i = (int) index;
        if (i >= POW2_LUT_SIZE - 1) {
            return POW2_LUT[POW2_LUT_SIZE - 1];
        }
        float t = index - i;
        return POW2_LUT[i] * (1.0f - t) + POW2_LUT[i + 1] * t;
    }
    
    /**
     * Fast approximation of x^2.4 using lookup table.
     * Delegates to FastMath for consistency and performance.
     */
    private static float fastPow24(float x) {
        return FastMath.fastPow24(x);
    }
    
    /**
     * Converts sRGB value to linear RGB using fast approximations.
     * sRGB uses a gamma curve: linear = (srgb <= 0.04045) ? srgb/12.92 : ((srgb+0.055)/1.055)^2.4
     * This works for srgb in [0, 1] range.
     * 
     * For values > 1.0 (HDR), we use the inverse of the gamma encoding function.
     * The gamma encoding is: srgb = (linear <= 0.0031308) ? linear*12.92 : 1.055*linear^(1/2.4) - 0.055
     * So for linear > 1.0, we approximate: linear ≈ ((srgb + 0.055) / 1.055)^2.4
     * This approximation works reasonably well for srgb > 1.0.
     */
    private static float srgbToLinear(float srgb) {
        if (srgb <= 0.04045f) {
            return srgb / 12.92f;
        } else {
            // Fast approximation: ((srgb + 0.055) / 1.055)^2.4
            float normalized = (srgb + 0.055f) * INV_1_055;
            return fastPow24(normalized);
        }
    }
    
    /**
     * Gets the minimum gain value used in the gain map.
     * This is the gain ratio for pixels that don't need HDR enhancement.
     */
    public static float getMinGain() {
        return MIN_GAIN;
    }
    
    /**
     * Gets the maximum gain value used in the gain map.
     * This is the maximum gain ratio that can be encoded.
     */
    public static float getMaxGain() {
        return MAX_GAIN;
    }
}

