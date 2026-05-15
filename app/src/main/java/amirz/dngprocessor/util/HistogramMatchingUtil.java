package amirz.dngprocessor.util;

import android.graphics.Bitmap;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.params.SensorParams;

import static android.opengl.GLES20.*;

/**
 * Utility class for building histogram matching LUTs from embedded JPEG previews.
 * This is used to guide baseline exposure compression to match the camera's intended rendering.
 */
public class HistogramMatchingUtil {
    private static final String TAG = "HistogramMatchingUtil";
    
    private static final int HIST_BINS = 1024;
    private static final int LUT_SIZE = 2048;
    private static final int SMOOTH_RADIUS = 3;
    
    /**
     * Build a histogram matching LUT texture from an embedded JPEG preview.
     * 
     * @param preview The embedded JPEG preview bitmap
     * @param process Process parameters (to check useReferencePreview flag)
     * @param sensor Sensor parameters (to check hasPreview)
     * @return The histogram matching LUT texture, or null if preview is not available
     */
    public static Texture buildHistogramMatchingLUT(Bitmap preview, ProcessParams process, SensorParams sensor) {
        if (!process.useReferencePreview || !sensor.hasPreview() || preview == null) {
            return null;
        }
        
        int previewWidth = preview.getWidth();
        int previewHeight = preview.getHeight();
        
        // Extract pixels from reference preview
        int[] pixels = new int[previewWidth * previewHeight];
        preview.getPixels(pixels, 0, previewWidth, 0, 0, previewWidth, previewHeight);
        
        // Build luminance histogram from reference (gamma-encoded sRGB)
        // The embedded JPEG is in final output space (gamma-encoded sRGB)
        int[] hist = new int[HIST_BINS];
        
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            float r = ((pixel >> 16) & 0xFF) / 255.0f;
            float g = ((pixel >> 8) & 0xFF) / 255.0f;
            float b = (pixel & 0xFF) / 255.0f;
            // Luminance from gamma-encoded sRGB (Rec. 709)
            float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            
            int bin = (int) (luma * (HIST_BINS - 1));
            if (bin < 0) bin = 0;
            if (bin >= HIST_BINS) bin = HIST_BINS - 1;
            hist[bin]++;
        }
        
        // Build cumulative distribution function (CDF)
        float[] cdf = new float[HIST_BINS];
        cdf[0] = hist[0];
        for (int i = 1; i < HIST_BINS; i++) {
            cdf[i] = cdf[i - 1] + hist[i];
        }
        // Normalize CDF to [0, 1]
        float total = cdf[HIST_BINS - 1];
        if (total > 0) {
            for (int i = 0; i < HIST_BINS; i++) {
                cdf[i] /= total;
            }
        }
        
        // Build inverse CDF lookup table for histogram matching
        // This creates a compression curve that remaps linear luminance (after baseline exposure)
        // to match the reference JPEG's distribution
        // For preprocessing: we match linear luminance to compressed linear luminance
        float[] histMatchLut = new float[LUT_SIZE];
        
        for (int i = 0; i < LUT_SIZE; i++) {
            float inputLuma = i / (float) (LUT_SIZE - 1);
            
            // Find the luminance value in reference where CDF equals inputLuma
            // This is the inverse CDF: CDF_ref^-1(inputLuma)
            float outputLuma = 0.0f;
            
            // Handle edge cases
            if (inputLuma <= cdf[0]) {
                outputLuma = 0.0f;
            } else if (inputLuma >= cdf[HIST_BINS - 1]) {
                outputLuma = 1.0f;
            } else {
                // Find the bin where CDF crosses inputLuma
                for (int j = 0; j < HIST_BINS - 1; j++) {
                    if (cdf[j] <= inputLuma && cdf[j + 1] >= inputLuma) {
                        // Linear interpolation between bins
                        float t = 0.0f;
                        float diff = cdf[j + 1] - cdf[j];
                        if (diff > 0.0001f) {
                            t = (inputLuma - cdf[j]) / diff;
                        }
                        outputLuma = (j + t) / (float) (HIST_BINS - 1);
                        break;
                    }
                }
            }
            
            histMatchLut[i] = outputLuma;
        }
        
        // Apply smoothing to reduce banding artifacts
        float[] smoothedLut = new float[LUT_SIZE];
        for (int i = 0; i < LUT_SIZE; i++) {
            float sum = 0.0f;
            float weight = 0.0f;
            
            // Gaussian-weighted smoothing to preserve overall curve shape
            for (int j = -SMOOTH_RADIUS; j <= SMOOTH_RADIUS; j++) {
                int idx = i + j;
                if (idx >= 0 && idx < LUT_SIZE) {
                    float gaussianWeight = (float) Math.exp(-(j * j) / (2.0f * SMOOTH_RADIUS * SMOOTH_RADIUS));
                    sum += histMatchLut[idx] * gaussianWeight;
                    weight += gaussianWeight;
                }
            }
            
            smoothedLut[i] = weight > 0.0001f ? sum / weight : histMatchLut[i];
        }
        
        // Blend original and smoothed LUT to preserve accuracy while reducing banding
        for (int i = 0; i < LUT_SIZE; i++) {
            histMatchLut[i] = smoothedLut[i] * 0.7f + histMatchLut[i] * 0.3f;
        }
        
        // Upload as 1D lookup texture
        FloatBuffer lutBuffer = ByteBuffer.allocateDirect(histMatchLut.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        lutBuffer.put(histMatchLut);
        lutBuffer.rewind();
        
        Texture lutTex = new Texture(LUT_SIZE, 1, 1, Texture.Format.Float16,
                lutBuffer, GL_LINEAR, GL_CLAMP_TO_EDGE);
        
        Log.d(TAG, "Built histogram matching LUT (size: " + LUT_SIZE + 
              ", from " + previewWidth + "x" + previewHeight + " preview)");
        
        return lutTex;
    }
    
    /**
     * Create a dummy identity LUT texture (input = output).
     * This is used when histogram matching is not available but a texture is required.
     * 
     * @return A dummy identity LUT texture
     */
    public static Texture createDummyIdentityLUT() {
        float[] dummyLut = new float[LUT_SIZE];
        for (int i = 0; i < LUT_SIZE; i++) {
            dummyLut[i] = i / (float) (LUT_SIZE - 1);  // Identity mapping
        }
        
        FloatBuffer dummyBuffer = ByteBuffer.allocateDirect(dummyLut.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        dummyBuffer.put(dummyLut);
        dummyBuffer.rewind();
        
        return new Texture(LUT_SIZE, 1, 1, Texture.Format.Float16,
                dummyBuffer, GL_LINEAR, GL_CLAMP_TO_EDGE);
    }
}

