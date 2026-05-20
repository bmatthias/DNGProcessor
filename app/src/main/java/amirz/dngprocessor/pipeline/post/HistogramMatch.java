package amirz.dngprocessor.pipeline.post;

import android.graphics.Bitmap;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.params.SensorParams;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;
import amirz.dngprocessor.pipeline.convert.RgbProvider;
import amirz.dngprocessor.pipeline.intermediate.Analysis;

import static android.opengl.GLES20.*;

/**
 * Histogram Matching stage that applies histogram matching to xyY intermediate data.
 * 
 * This stage runs before ToneMap and applies histogram matching to the Y (luminance)
 * channel of xyY while preserving xy chromaticity. This ensures color saturation is
 * maintained, unlike applying histogram matching in RGB space.
 * 
 * APPROACH:
 * 1. Get xyY intermediate input
 * 2. Extract Y (luminance) from xyY
 * 3. Apply histogram matching curve to Y (in gamma space)
 * 4. Scale Y by ratio (targetY / originalY)
 * 5. Preserve xy chromaticity
 * 6. Convert xyY => RGB (if this is the final stage before ToneMap)
 * 7. Output RGB (if converting) or modified xyY (if not)
 * 
 * This is similar to LateExposureFusion but applies histogram matching instead of
 * exposure fusion.
 */
public class HistogramMatch extends Stage implements IntermediateProvider, RgbProvider {
    private static final String TAG = "HistogramMatch";
    
    private Texture mIntermediate;
    private Texture mRgbOutput;  // RGB output (when doing xyY => RGB conversion)
    private boolean mApplied = false;
    private float[] mXYZtoProPhoto;
    
    public HistogramMatch(float[] XYZtoProPhoto) {
        mXYZtoProPhoto = XYZtoProPhoto;
    }
    
    @Override
    public Texture getIntermediate() {
        // Return RGB if available, otherwise return xyY intermediate
        return mRgbOutput != null ? mRgbOutput : mIntermediate;
    }
    
    @Override
    public Texture getRgbTex() {
        return mRgbOutput;
    }
    
    /**
     * Check if histogram matching was actually applied
     */
    public boolean isApplied() {
        return mApplied;
    }
    
    /**
     * Build RAW CDF from current intermediate texture.
     * This is needed because the pipeline's Analysis stage runs before stages that modify
     * values (DoubleExpose/Merge for non-HDR, LateExposureFusion/HdrCompress for HDR),
     * so we need to build a new CDF from the actual values that HistogramMatch receives.
     * 
     * Uses the same shader-based downsampling approach as Analysis.java for performance.
     * The analysis shader decodes HDR and outputs luminance in channel 3.
     * 
     * @param intermediate The intermediate texture in xyY format
     * @return Array containing [rawCdf, maxHdrValue] where rawCdf is the CDF array
     */
    private Object[] buildRawCdfFromIntermediate(Texture intermediate) {
        final int RAW_HIST_BINS = Analysis.getRawHistBins();
        final int SAMPLING_FACTOR = 16;  // Match Analysis.java for consistency

        GLPrograms converter = getConverter();
        SensorParams sensor = getSensorParams();

        int width = intermediate.getWidth();
        int height = intermediate.getHeight();
        int w = width;
        int h = height;

        // Use same shader-based downsampling as Analysis.java
        converter.useProgram(R.raw.stage2_2_analysis_fs);
        converter.setTexture("intermediate", intermediate);
        converter.seti("outOffset", sensor.outputOffsetX, sensor.outputOffsetY);

        // Downsample
        w /= SAMPLING_FACTOR;
        h /= SAMPLING_FACTOR;
        w = Math.max(1, w);
        h = Math.max(1, h);

        converter.seti("samplingFactor", SAMPLING_FACTOR);

        // The analysis shader outputs (sqrt(sigma_r), sqrt(sigma_g), sqrt(sigma_b), decoded_luminance)
        // We only need channel 3 (luminance) which is already decoded by the shader
        try (Texture analyzeTex = TexturePool.get(w, h, 4, Texture.Format.Float16)) {
            converter.drawBlocks(analyzeTex);

            int pixelCount = w * h;
            float[] f = new float[pixelCount * 4];
            FloatBuffer fb = ByteBuffer.allocateDirect(f.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            fb.mark();

            // OPTIMIZATION: Read entire downsampled texture in ONE call (much faster than pixel-by-pixel)
            glReadPixels(0, 0, w, h, GL_RGBA, GL_FLOAT, fb.reset());
            fb.get(f);

            // Build histogram in gamma space (matching Analysis.java buildRawCdf)
            // The shader already decoded HDR, so f[i+3] contains decoded luminance
            int[] hist = new int[RAW_HIST_BINS];
            float maxHdrValue = 1.0f;

            for (int i = 0; i < f.length; i += 4) {
                float luma = f[i + 3];  // Decoded luminance from shader output (channel 3)

                // Track max HDR value
                if (luma > maxHdrValue && luma < 100.0f) {  // Ignore outliers
                    maxHdrValue = luma;
                }

                // Apply gamma encoding to match JPEG space
                // For HDR values > 1, gamma naturally compresses them
                float gammaLuma = (float) Math.pow(Math.max(luma, 0.0), 1.0 / 2.2);
                // Histogram covers [0, 1] in gamma space (values > 1 are clamped to last bin)
                int bin = (int) (Math.min(gammaLuma, 1.0f) * (RAW_HIST_BINS - 1));
                bin = Math.max(0, Math.min(RAW_HIST_BINS - 1, bin));
                hist[bin]++;
            }

            // Add small headroom to maxHdrValue
            // maxHdrValue *= 1.05f;

            // Build cumulative distribution function
            float[] rawCdf = new float[RAW_HIST_BINS + 1];
            rawCdf[0] = 0;
            for (int i = 1; i <= RAW_HIST_BINS; i++) {
                rawCdf[i] = rawCdf[i - 1] + hist[i - 1];
            }

            // Normalize CDF to [0, 1]
            float total = rawCdf[RAW_HIST_BINS];
            if (total > 0) {
                for (int i = 0; i <= RAW_HIST_BINS; i++) {
                    rawCdf[i] /= total;
                }
            }

            Log.d(TAG, "Built RAW CDF from current intermediate: bins=" + RAW_HIST_BINS +
                  ", maxHDR=" + maxHdrValue + ", samples=" + pixelCount);

            return new Object[]{rawCdf, maxHdrValue};
        }
    }
    
    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        Log.d(TAG, "=== HistogramMatch.execute() START ===");
        
        GLPrograms converter = getConverter();
        SensorParams sensor = getSensorParams();
        ProcessParams process = getProcessParams();
        
        // Get intermediate texture from previous stage
        IntermediateProvider intermediateProvider = previousStages.getStageByInterface(IntermediateProvider.class);
        if (intermediateProvider == null) {
            Log.e(TAG, "No intermediate provider available");
            mIntermediate = null;
            return;
        }
        
        Texture intermediateInput = intermediateProvider.getIntermediate();
        if (intermediateInput == null) {
            Log.e(TAG, "No intermediate texture available");
            mIntermediate = null;
            return;
        }
        
        Log.d(TAG, "Intermediate input: " + intermediateInput.getWidth() + "x" + intermediateInput.getHeight() + 
                ", channels=" + intermediateInput.getChannels() + ", format=" + intermediateInput.getFormat());
        
        // Log which stage provided the input (for debugging)
        String providerName = intermediateProvider.getClass().getSimpleName();
        Log.d(TAG, "Input provider: " + providerName);
        
        // Check if histogram matching should be applied
        boolean hasReferencePreview = process.useReferencePreview && sensor.hasPreview();
        
        if (!hasReferencePreview) {
            Log.d(TAG, "Histogram matching not applied: no reference preview");
            mIntermediate = intermediateInput;
            mApplied = false;
            return;
        }
        
        // CRITICAL FIX: Build CDF from current intermediate texture instead of using
        // pipeline's Analysis CDF. This is needed because:
        // - For non-HDR: DoubleExpose/Merge modifies values before HistogramMatch
        // - For HDR: LateExposureFusion/HdrCompress compresses values before HistogramMatch
        // The pipeline's Analysis runs before these stages, so its CDF doesn't match
        // the values that HistogramMatch actually receives.
        Object[] cdfResult = buildRawCdfFromIntermediate(intermediateInput);
        float[] rawCdf = (float[]) cdfResult[0];
        float maxHdrValue = (Float) cdfResult[1];
        int rawCdfBins = Analysis.getRawHistBins();
        
        // Compute histogram matching control points from JPEG preview
        // PROPER HISTOGRAM MATCHING (CDF-to-CDF):
        // 1. Build RAW CDF from processed image (done in Analysis stage)
        // 2. Build JPEG CDF from embedded preview
        // 3. For each RAW luminance L:
        //    - Find percentile p = CDF_RAW(L)
        //    - Find JPEG luminance T where CDF_JPEG(T) = p
        //    - Map L → T
        // 4. Handle HDR values (RAW can exceed 1.0, JPEG is always in [0,1])
        
        Bitmap preview = sensor.previewImage;
        int previewWidth = preview.getWidth();
        int previewHeight = preview.getHeight();
        
        // Extract pixels from reference preview
        int[] pixels = new int[previewWidth * previewHeight];
        preview.getPixels(pixels, 0, previewWidth, 0, 0, previewWidth, previewHeight);
        
        // Build luminance histogram from reference (gamma-encoded sRGB)
        final int JPEG_HIST_BINS = 2048;  // Match RAW CDF resolution
        int[] jpegHist = new int[JPEG_HIST_BINS];
        float jpegMeanLuma = 0f;
        float[] jpegMeanRgb = new float[3];  // For color matching
        
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            float r = ((pixel >> 16) & 0xFF) / 255.0f;
            float g = ((pixel >> 8) & 0xFF) / 255.0f;
            float b = (pixel & 0xFF) / 255.0f;
            // Luminance from gamma-encoded sRGB (Rec. 709)
            float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            
            int bin = (int) (luma * (JPEG_HIST_BINS - 1));
            bin = Math.max(0, Math.min(JPEG_HIST_BINS - 1, bin));
            jpegHist[bin]++;
            
            jpegMeanLuma += luma;
            jpegMeanRgb[0] += r;
            jpegMeanRgb[1] += g;
            jpegMeanRgb[2] += b;
        }
        
        jpegMeanLuma /= pixels.length;
        jpegMeanRgb[0] /= pixels.length;
        jpegMeanRgb[1] /= pixels.length;
        jpegMeanRgb[2] /= pixels.length;
        
        // Build JPEG CDF
        float[] jpegCdf = new float[JPEG_HIST_BINS + 1];
        jpegCdf[0] = 0;
        for (int i = 1; i <= JPEG_HIST_BINS; i++) {
            jpegCdf[i] = jpegCdf[i - 1] + jpegHist[i - 1];
        }
        // Normalize CDF to [0, 1]
        float total = jpegCdf[JPEG_HIST_BINS];
        if (total > 0) {
            for (int i = 0; i <= JPEG_HIST_BINS; i++) {
                jpegCdf[i] /= total;
            }
        }
        
        // Build parametric tone curve control points for direct shader evaluation
        // Instead of a LUT (which adds quantization), we pass control points and
        // evaluate the smooth Hermite curve directly in the shader
        final float[] CONTROL_PERCENTILES = {0.0f, 0.05f, 0.15f, 0.3f, 0.5f, 0.7f, 0.85f, 0.95f, 1.0f};
        final int NUM_CONTROL_POINTS = CONTROL_PERCENTILES.length;
        float[] controlInputs = new float[NUM_CONTROL_POINTS];
        float[] controlOutputs = new float[NUM_CONTROL_POINTS];
        
        // Build control points by sampling CDF mapping at key percentiles
        // CORRECT HISTOGRAM MATCHING:
        // For each input gamma-space luminance value (e.g., 0.5):
        // 1. Find what percentile this value has in RAW CDF
        // 2. Find what gamma-space luminance in JPEG has that same percentile
        // 3. Map: inputGamma → outputGamma
        for (int p = 0; p < NUM_CONTROL_POINTS; p++) {
            float inputGammaLuma = CONTROL_PERCENTILES[p];
            controlInputs[p] = inputGammaLuma;
            
            // Step 1: Find percentile of this input gamma-space luminance in RAW CDF
            // The CDF structure: cdf[0]=0, cdf[i] = CDF value for histogram bin (i-1)
            // For a gamma value, find which bin it falls into, then interpolate CDF
            float percentile;
            if (inputGammaLuma <= 0.0f) {
                percentile = 0.0f;
            } else if (inputGammaLuma >= 1.0f) {
                percentile = 1.0f;
            } else {
                // Calculate histogram bin (same as Analysis.java)
                float binFloat = inputGammaLuma * (rawCdfBins - 1);
                int rawHistBin = (int) binFloat;
                rawHistBin = Math.max(0, Math.min(rawCdfBins - 1, rawHistBin));
                
                // Get CDF values at bin boundaries
                // cdf[bin+1] is CDF at end of bin 'bin', cdf[bin+2] is CDF at end of bin 'bin+1'
                float cdfAtBin = rawCdf[rawHistBin + 1];
                float cdfAtNextBin = (rawHistBin < rawCdfBins - 1) ? rawCdf[rawHistBin + 2] : 1.0f;
                
                // Interpolate percentile within the bin
                float frac = binFloat - rawHistBin;
                percentile = cdfAtBin + (cdfAtNextBin - cdfAtBin) * frac;
                percentile = Math.max(0.0f, Math.min(1.0f, percentile));
            }
            
            // Step 2: Find JPEG gamma-space luminance at same percentile (inverse CDF lookup)
            // CDF structure: jpegCdf[0]=0, jpegCdf[i] = CDF at end of histogram bin (i-1)
            float outputLuma = 0.0f;
            if (percentile <= jpegCdf[0]) {
                outputLuma = 0.0f;
            } else if (percentile >= jpegCdf[JPEG_HIST_BINS]) {
                outputLuma = 1.0f;
            } else {
                // Binary search for the CDF index containing this percentile
                int lo = 0, hi = JPEG_HIST_BINS;
                while (hi - lo > 1) {
                    int mid = (lo + hi) / 2;
                    if (jpegCdf[mid] <= percentile) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                // Interpolate to get exact gamma value
                // jpegCdf[lo] <= percentile < jpegCdf[hi]
                // Gamma value is between: (lo-1)/(BINS-1) and (hi-1)/(BINS-1)
                float cdfLo = jpegCdf[lo];
                float cdfHi = jpegCdf[hi];
                float t = (cdfHi > cdfLo) ? (percentile - cdfLo) / (cdfHi - cdfLo) : 0.0f;
                
                // Map CDF indices to gamma values
                // CDF index 0 -> gamma 0, CDF index i -> gamma (i-1)/(BINS-1) for i>=1
                float gammaLo = (lo == 0) ? 0.0f : (lo - 1) / (float) (JPEG_HIST_BINS - 1);
                float gammaHi = (hi - 1) / (float) (JPEG_HIST_BINS - 1);
                outputLuma = gammaLo + (gammaHi - gammaLo) * t;
                outputLuma = Math.max(0.0f, Math.min(1.0f, outputLuma));
            }
            
            controlOutputs[p] = outputLuma;
        }
        
        // Note: controlOutputs are already in gamma space (from JPEG preview)
        // We match them directly without additional gamma correction
        
        // Log control points for debugging
        Log.d(TAG, "Histogram matching control points:");
        for (int i = 0; i < NUM_CONTROL_POINTS; i++) {
            Log.d(TAG, String.format("  [%.3f] -> [%.3f]", controlInputs[i], controlOutputs[i]));
        }
        
        // Enforce monotonicity on control points
        for (int i = 1; i < NUM_CONTROL_POINTS; i++) {
            if (controlOutputs[i] < controlOutputs[i - 1]) {
                controlOutputs[i] = controlOutputs[i - 1];
            }
        }
        
        // Pre-compute tangents (slopes) at each control point for Hermite interpolation
        // These will be passed to the shader for direct curve evaluation
        float[] controlTangents = new float[NUM_CONTROL_POINTS];
        for (int i = 0; i < NUM_CONTROL_POINTS; i++) {
            if (i == 0) {
                // First point: use forward difference
                controlTangents[i] = (controlOutputs[1] - controlOutputs[0]) / (controlInputs[1] - controlInputs[0]);
            } else if (i == NUM_CONTROL_POINTS - 1) {
                // Last point: use backward difference
                controlTangents[i] = (controlOutputs[NUM_CONTROL_POINTS - 1] - controlOutputs[NUM_CONTROL_POINTS - 2]) / 
                                   (controlInputs[NUM_CONTROL_POINTS - 1] - controlInputs[NUM_CONTROL_POINTS - 2]);
            } else {
                // Interior points: use central difference (average of forward and backward)
                float forward = (controlOutputs[i + 1] - controlOutputs[i]) / (controlInputs[i + 1] - controlInputs[i]);
                float backward = (controlOutputs[i] - controlOutputs[i - 1]) / (controlInputs[i] - controlInputs[i - 1]);
                controlTangents[i] = 0.5f * (forward + backward);
            }
        }
        
        // Apply histogram matching and convert xyY => RGB
        // This stage does both: histogram matching in xyY space, then converts to RGB
        int width = intermediateInput.getWidth();
        int height = intermediateInput.getHeight();
        mRgbOutput = TexturePool.get(width, height, 4, Texture.Format.Float16);
        
        converter.useProgram(R.raw.histogram_match_xyy_to_rgb);
        converter.setTexture("xyYInput", intermediateInput);
        
        float baselineEV = sensor.baselineExposure;
        float baselineMult = (float) Math.pow(2.0, baselineEV);
        converter.setf("baselineExposure", baselineMult);
        converter.setf("histMatchMaxHdr", maxHdrValue);
        converter.setf("histMatchStrength", process.histMatchStrength);
        
        Log.d(TAG, "Histogram matching parameters: baselineMult=" + baselineMult + 
              ", maxHdrValue=" + maxHdrValue + ", baselineEV=" + baselineEV);
        converter.setf("XYZtoProPhoto", mXYZtoProPhoto);
        
        // Pass control points and tangents as uniform arrays to shader
        // Shader will evaluate Hermite interpolation directly (no quantization!)
        // Set array elements individually (GLSL ES requires element-by-element setting)
        for (int i = 0; i < NUM_CONTROL_POINTS; i++) {
            converter.setf("histMatchControlInputs[" + i + "]", controlInputs[i]);
            converter.setf("histMatchControlOutputs[" + i + "]", controlOutputs[i]);
            converter.setf("histMatchControlTangents[" + i + "]", controlTangents[i]);
        }
        converter.seti("histMatchNumControlPoints", NUM_CONTROL_POINTS);
        
        converter.drawBlocks(mRgbOutput);
        
        // Also set intermediate to RGB for compatibility (some code may check getIntermediate())
        mIntermediate = mRgbOutput;
        
        mApplied = true;
        Log.d(TAG, "Histogram matching applied to xyY and converted to RGB: RAW CDF bins=" + rawCdfBins + 
              ", maxHDR=" + maxHdrValue + ", JPEG CDF bins=" + JPEG_HIST_BINS + ", control points=" + NUM_CONTROL_POINTS);
    }
    
    @Override
    public int getShader() {
        return R.raw.histogram_match_xyy_to_rgb;
    }
    
    @Override
    public void close() {
        if (mIntermediate != null && mIntermediate != mRgbOutput) {
            mIntermediate.close();
        }
        if (mRgbOutput != null) {
            mRgbOutput.close();
        }
    }
}
