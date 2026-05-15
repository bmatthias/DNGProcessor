package amirz.dngprocessor.pipeline.intermediate;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.math.Histogram;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;
import amirz.dngprocessor.pipeline.exposefuse.Merge;

import static android.opengl.GLES20.*;

public class Analysis extends Stage {
    private static final String TAG = "SampleHistogram";
    
    // Histogram bins for RAW CDF (used for histogram matching)
    private static final int RAW_HIST_BINS = 2048;

    private final int mOutWidth, mOutHeight, mOffsetX, mOffsetY;
    private final boolean mBuildRawCdf;  // Whether to build RawCdf (only needed for HistogramMatch)
    private final boolean mComputeMaxHdrValue;  // Whether to compute maxHdrValue (needed for HdrCompress or HistogramMatch)
    private final boolean mComputeMeanColor;  // Whether to compute mean color (needed for ToneMap color matching)
    private final boolean mComputeHistogramStats;  // Whether to compute histogram stats (needed for AutoTune)
    private float[] mSigma, mHist;
    private float mGamma;
    private float mLogAvgLuminance;
    private float mMinLuminance;
    private float mMaxLuminance;
    private float mP01Luminance;  // 1st percentile (robust to outliers)
    private float mP99Luminance;  // 99th percentile (robust to outliers)
    
    // Raw CDF data for histogram matching (unmodified CDF)
    private float[] mRawCdf;
    private float mMaxHdrValue = 1.0f;  // Default safe value (matches fallback in callers)
    private float[] mMeanColor;  // Mean RGB for color matching

    public Analysis(int outWidth, int outHeight, int offsetX, int offsetY) {
        // Default: compute everything for backward compatibility
        this(outWidth, outHeight, offsetX, offsetY, true, true, true, true);
    }
    
    public Analysis(int outWidth, int outHeight, int offsetX, int offsetY, boolean buildRawCdf) {
        // Legacy constructor: buildRawCdf implies computeMaxHdrValue
        this(outWidth, outHeight, offsetX, offsetY, buildRawCdf, buildRawCdf, true, true);
    }
    
    public Analysis(int outWidth, int outHeight, int offsetX, int offsetY, 
                    boolean buildRawCdf, boolean computeMaxHdrValue, 
                    boolean computeMeanColor, boolean computeHistogramStats) {
        mOutWidth = outWidth;
        mOutHeight = outHeight;
        mOffsetX = offsetX;
        mOffsetY = offsetY;
        mBuildRawCdf = buildRawCdf;
        mComputeMaxHdrValue = computeMaxHdrValue;
        mComputeMeanColor = computeMeanColor;
        mComputeHistogramStats = computeHistogramStats;
    }

    public float[] getSigma() {
        return mSigma;
    }

    public float[] getHist() {
        return mHist;
    }

    public float getGamma() {
        return mGamma;
    }
    
    /**
     * Get the log-average luminance of the image.
     * This is computed as exp(mean(log(luminance + epsilon))).
     * Useful for auto-exposure and HDR tone mapping.
     * 
     * @return log-average luminance value
     */
    public float getLogAvgLuminance() {
        return mLogAvgLuminance;
    }
    
    /**
     * Get the minimum luminance value found in the image.
     * Used for histogram normalization/stretching.
     * 
     * @return minimum luminance value
     */
    public float getMinLuminance() {
        return mMinLuminance;
    }
    
    /**
     * Get the maximum luminance value found in the image.
     * Used for histogram normalization/stretching.
     * 
     * @return maximum luminance value
     */
    public float getMaxLuminance() {
        return mMaxLuminance;
    }
    
    /**
     * Get the 1st percentile luminance value (robust to outliers).
     * Used for histogram normalization/stretching to prevent extreme contrast.
     * 
     * @return 1st percentile luminance value
     */
    public float getP01Luminance() {
        return mP01Luminance;
    }
    
    /**
     * Get the 99th percentile luminance value (robust to outliers).
     * Used for histogram normalization/stretching to prevent extreme contrast.
     * 
     * @return 99th percentile luminance value
     */
    public float getP99Luminance() {
        return mP99Luminance;
    }
    
    /**
     * Get the raw luminance CDF (cumulative distribution function) for histogram matching.
     * This is an unmodified CDF covering [0, maxHdrValue].
     * 
     * @return float array of size RAW_HIST_BINS+1 with CDF values normalized to [0, 1]
     */
    public float[] getRawCdf() {
        return mRawCdf;
    }
    
    /**
     * Get the maximum HDR luminance value found in the image.
     * The raw CDF covers the range [0, maxHdrValue].
     * 
     * @return maximum luminance value (can be > 1.0 for HDR content)
     */
    public float getMaxHdrValue() {
        return mMaxHdrValue;
    }
    
    /**
     * Get the mean color (in xyY color space: x, y chromaticity + Y luminance).
     * Used for global color matching with reference JPEG.
     * 
     * @return float[3] with mean {x, y, Y} values
     */
    public float[] getMeanColor() {
        return mMeanColor;
    }
    
    /**
     * Get the number of bins in the raw CDF.
     * 
     * @return number of histogram bins
     */
    public static int getRawHistBins() {
        return RAW_HIST_BINS;
    }

    @Override
    protected void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();

        Texture intermediate = null;
        Merge mergeStage = previousStages.getStage(Merge.class);
        if (mergeStage != null) {
            intermediate = mergeStage.getMerged();
        }
        if (intermediate == null) {
            intermediate = previousStages.getStageByInterface(IntermediateProvider.class).getIntermediate();
        }
        converter.useProgram(R.raw.stage2_2_analysis_fs);

        converter.setTexture("intermediate", intermediate);
        converter.seti("outOffset", mOffsetX, mOffsetY);

        int w = mOutWidth;
        int h = mOutHeight;
        int samplingFactor = 16;

        // Analyze
        w /= samplingFactor;
        h /= samplingFactor;

        converter.seti("samplingFactor", samplingFactor);

        try (Texture analyzeTex = TexturePool.get(w, h, 4, Texture.Format.Float16)) {
            converter.drawBlocks(analyzeTex);

            int whPixels = w * h;
            float[] f = new float[whPixels * 4];
            FloatBuffer fb = ByteBuffer.allocateDirect(f.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            fb.mark();

            glReadPixels(0, 0, w, h, GL_RGBA, GL_FLOAT, fb.reset());
            fb.get(f);

            // Calculate histogram stats only if needed (for AutoTune)
            if (mComputeHistogramStats) {
                Histogram histParser = new Histogram(f, whPixels);
                mSigma = histParser.sigma;
                mHist = histParser.hist;
                mGamma = histParser.gamma;
                mLogAvgLuminance = histParser.logAvgLuminance;
                mMinLuminance = histParser.minLuminance;
                mMaxLuminance = histParser.maxLuminance;
                mP01Luminance = histParser.p01Luminance;
                mP99Luminance = histParser.p99Luminance;
                
                Log.d(TAG, "Sigma " + Arrays.toString(mSigma));
                Log.d(TAG, "LogAvg " + mLogAvgLuminance);
                Log.d(TAG, "Gamma " + histParser.gamma);
                Log.d(TAG, "Luminance range [" + mMinLuminance + ", " + mMaxLuminance + "]");
                Log.d(TAG, "Luminance percentiles (p01, p99): [" + mP01Luminance + ", " + mP99Luminance + "]");
            }
            
            // Build raw CDF for histogram matching (unmodified, HDR-aware)
            // Only build if HistogramMatch stage will use it (expensive operation)
            if (mBuildRawCdf) {
                buildRawCdf(f, whPixels);
            } else if (mComputeMaxHdrValue) {
                // Compute maxHdrValue for HdrCompress (if enabled)
                computeMaxHdrValue(f);
            }
            
            if (mComputeMaxHdrValue) {
                Log.d(TAG, "MaxHDR " + mMaxHdrValue);
            }
        }
        
        // Sample intermediate texture directly for mean chromaticity (for color matching)
        // The intermediate texture is in xyY format: (x, y, Y, alpha)
        // We need to read it directly because the analysis shader outputs sigma values
        // Only compute if needed for ToneMap color matching
        if (mComputeMeanColor) {
            sampleMeanChromaticity(intermediate, samplingFactor);
            Log.d(TAG, "MeanColor " + Arrays.toString(mMeanColor));
        }
    }
    
    /**
     * Sample the intermediate texture to compute mean chromaticity for color matching.
     * The intermediate texture is in xyY format where:
     * - Channel 0 = x chromaticity
     * - Channel 1 = y chromaticity
     * - Channel 2 = Y luminance (may be HDR encoded)
     * - Channel 3 = alpha (1/scale for HDR encoding)
     * 
     * @param intermediate The intermediate texture in xyY format
     * @param samplingFactor Subsampling factor (read every Nth pixel)
     */
    private void sampleMeanChromaticity(Texture intermediate, int samplingFactor) {
        // Read a downsampled version of the intermediate texture
        // Use a coarser sampling (32x32 = 1024 samples) for efficiency
        int sampleWidth = Math.min(32, intermediate.getWidth() / samplingFactor);
        int sampleHeight = Math.min(32, intermediate.getHeight() / samplingFactor);
        
        if (sampleWidth < 1) sampleWidth = 1;
        if (sampleHeight < 1) sampleHeight = 1;
        
        // Set up framebuffer to read from intermediate texture
        intermediate.setFrameBuffer();
        
        // Read pixels from the texture at regular intervals
        int stepX = intermediate.getWidth() / sampleWidth;
        int stepY = intermediate.getHeight() / sampleHeight;
        
        mMeanColor = new float[3];  // x, y, Y
        int sampleCount = 0;
        
        // Sample individual pixels across the texture
        FloatBuffer pixelBuffer = ByteBuffer.allocateDirect(4 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        
        for (int sy = 0; sy < sampleHeight; sy++) {
            for (int sx = 0; sx < sampleWidth; sx++) {
                int px = sx * stepX;
                int py = sy * stepY;
                
                pixelBuffer.rewind();
                glReadPixels(px, py, 1, 1, GL_RGBA, GL_FLOAT, pixelBuffer);
                pixelBuffer.rewind();
                
                float x = pixelBuffer.get(0);  // x chromaticity
                float y = pixelBuffer.get(1);  // y chromaticity
                float z = pixelBuffer.get(2);  // Y luminance (possibly encoded)
                float alpha = pixelBuffer.get(3);  // alpha (1/scale for HDR)
                
                // Decode HDR luminance if encoded
                float luma;
                if (alpha >= 0.9999f) {
                    luma = z;
                } else {
                    float invScale = Math.max(alpha, 0.0001f);
                    luma = z / invScale;
                }
                
                // Skip invalid samples
                if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(luma) ||
                    x < 0 || y < 0 || x + y > 1.0f) {
                    continue;
                }
                
                mMeanColor[0] += x;
                mMeanColor[1] += y;
                mMeanColor[2] += luma;
                sampleCount++;
            }
        }
        
        // Restore default framebuffer
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        
        // Compute mean
        if (sampleCount > 0) {
            mMeanColor[0] /= sampleCount;
            mMeanColor[1] /= sampleCount;
            mMeanColor[2] /= sampleCount;
        } else {
            // Fallback to D65 white point
            mMeanColor[0] = 0.3127f;
            mMeanColor[1] = 0.3290f;
            mMeanColor[2] = 0.5f;
            Log.w(TAG, "No valid chromaticity samples, using D65 white point as fallback");
        }
        
        Log.d(TAG, "Sampled mean chromaticity from " + sampleCount + " pixels: " +
              "x=" + mMeanColor[0] + ", y=" + mMeanColor[1] + ", Y=" + mMeanColor[2]);
    }
    
    /**
     * Compute max HDR value without building the full CDF.
     * This is a lightweight version that only computes mMaxHdrValue for HdrCompress.
     * 
     * @param f float array with RGBA values (luminance in channel 3)
     */
    private void computeMaxHdrValue(float[] f) {
        mMaxHdrValue = 1.0f;  // Minimum of 1.0 to avoid division issues
        
        for (int i = 0; i < f.length; i += 4) {
            float luma = f[i + 3];  // Y channel (luminance)
            if (luma > mMaxHdrValue && luma < 100.0f) {  // Ignore outliers
                mMaxHdrValue = luma;
            }
        }
        
        // Add small headroom to maxHdrValue
        mMaxHdrValue *= 1.05f;
        
        Log.d(TAG, "Computed maxHDR (CDF skipped): " + mMaxHdrValue);
    }
    
    /**
     * Build raw CDF for histogram matching.
     * This builds an unmodified CDF that covers the full HDR range [0, maxValue].
     * 
     * Note: The analysis shader outputs (sqrt(sigma_r), sqrt(sigma_g), sqrt(sigma_b), luminance).
     * We only use channel 3 (luminance) for CDF computation.
     * Mean chromaticity is computed separately in sampleMeanChromaticity().
     * 
     * @param f float array with RGBA values (luminance in channel 3)
     * @param pixelCount number of pixels
     */
    private void buildRawCdf(float[] f, int pixelCount) {
        // First pass: find max HDR value (in linear space for reference)
        computeMaxHdrValue(f);
        
        // Second pass: build histogram in GAMMA SPACE
        // JPEG histogram is in gamma space, so RAW CDF must also be in gamma space
        // for correct histogram matching. Gamma encoding: pow(x, 1/2.2)
        int[] hist = new int[RAW_HIST_BINS];
        for (int i = 0; i < f.length; i += 4) {
            float luma = f[i + 3];
            // Apply gamma encoding to match JPEG space
            // For HDR values > 1, gamma naturally compresses them
            float gammaLuma = (float) Math.pow(Math.max(luma, 0.0), 1.0 / 2.2);
            // Histogram covers [0, 1] in gamma space (values > 1 are clamped to last bin)
            int bin = (int) (Math.min(gammaLuma, 1.0f) * (RAW_HIST_BINS - 1));
            bin = Math.max(0, Math.min(RAW_HIST_BINS - 1, bin));
            hist[bin]++;
        }
        
        // Build cumulative distribution function
        mRawCdf = new float[RAW_HIST_BINS + 1];
        mRawCdf[0] = 0;
        for (int i = 1; i <= RAW_HIST_BINS; i++) {
            mRawCdf[i] = mRawCdf[i - 1] + hist[i - 1];
        }
        
        // Normalize CDF to [0, 1]
        float total = mRawCdf[RAW_HIST_BINS];
        if (total > 0) {
            for (int i = 0; i <= RAW_HIST_BINS; i++) {
                mRawCdf[i] /= total;
            }
        }
        
        Log.d(TAG, "Built raw CDF: " + RAW_HIST_BINS + " bins, maxHDR=" + mMaxHdrValue + 
              ", CDF range [" + mRawCdf[0] + ", " + mRawCdf[RAW_HIST_BINS] + "]");
    }

    @Override
    public int getShader() {
        return R.raw.stage2_1_noise_level_fs;
    }
}
