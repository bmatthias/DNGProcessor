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
import amirz.dngprocessor.pipeline.convert.IntermediateProvider;
import amirz.dngprocessor.pipeline.intermediate.Analysis;

/**
 * Unified HDR Compression stage that applies HDR compression to xyY intermediate data.
 * 
 * This stage runs before ToneMap and applies compression to the Y (luminance)
 * channel of xyY while preserving xy chromaticity. This ensures color saturation is
 * maintained, unlike applying compression in RGB space.
 * 
 * Supported compression methods:
 * - 0: Reinhard (simple, smooth)
 * - 1: ACES Filmic (film-like response)
 * - 2: Uncharted 2 (popular in game engines)
 * - 3: Improved Rational (enhanced Reinhard)
 * - 4: Gamma+ACES Fusion (blends multiple methods)
 * 
 * APPROACH:
 * 1. Get xyY intermediate input
 * 2. Extract Y (luminance) from xyY
 * 3. Apply compression method to Y
 * 4. Replace Y directly with compressedY (preserves color saturation)
 * 5. Preserve xy chromaticity
 * 6. Output modified xyY
 * 
 * This is similar to HistogramMatch and LateExposureFusion but applies HDR compression
 * instead of histogram matching or exposure fusion.
 */
public class HdrCompress extends Stage implements IntermediateProvider {
    private static final String TAG = "HdrCompress";

    private Texture mIntermediate;
    private boolean mApplied = false;

    @Override
    public Texture getIntermediate() {
        return mIntermediate;
    }

    /**
     * Check if compression was actually applied
     */
    public boolean isApplied() {
        return mApplied;
    }

    @Override
    protected void execute(StagePipeline.StageMap previousStages) {
        Log.d(TAG, "=== HdrCompress.execute() START ===");

        GLPrograms converter = getConverter();
        SensorParams sensor = getSensorParams();
        ProcessParams process = getProcessParams();

        // Get intermediate texture from previous stage
        IntermediateProvider intermediateProvider = previousStages.getStageByInterface(IntermediateProvider.class);
        if (intermediateProvider == null) {
            Log.e(TAG, "No intermediate provider available");
            mIntermediate = null;
            mApplied = false;
            return;
        }

        Texture intermediateInput = intermediateProvider.getIntermediate();
        if (intermediateInput == null) {
            Log.e(TAG, "No intermediate texture available");
            mIntermediate = null;
            mApplied = false;
            return;
        }

        Log.d(TAG, "Intermediate input: " + intermediateInput.getWidth() + "x" + intermediateInput.getHeight() +
                ", channels=" + intermediateInput.getChannels() + ", format=" + intermediateInput.getFormat());

        // Check if compression should be applied
        // Only apply if hdrCompressionMethod is set and not Late Exposure Fusion (method 5)
        // Method 5 is handled by LateExposureFusion stage
        int compressionMethod = process.hdrCompressionMethod;
        if (compressionMethod < 0 || compressionMethod > 4) {
            Log.d(TAG, "Compression not applied: method=" + compressionMethod + 
                      " (method 5=Late Exposure Fusion is handled by LateExposureFusion stage)");
            // Pass through intermediate unchanged
            mIntermediate = intermediateInput;
            mApplied = false;
            return;
        }

        // Get max HDR value from Analysis stage
        Analysis analysis = previousStages.getStage(Analysis.class);
        float maxHdrValue = analysis != null ? analysis.getMaxHdrValue() : 1.0f;

        // Apply compression shader
        int width = intermediateInput.getWidth();
        int height = intermediateInput.getHeight();
        mIntermediate = TexturePool.get(width, height, 4, Texture.Format.Float16);

        converter.useProgram(R.raw.hdr_compress_xyy);
        converter.setTexture("xyYInput", intermediateInput);

        float baselineEV = sensor.baselineExposure;
        float baselineMult = (float) Math.pow(2.0, baselineEV);
        converter.setf("baselineExposure", baselineMult);
        converter.setf("maxHdr", maxHdrValue);
        converter.seti("compressionMethod", compressionMethod);
        converter.setf("compressionStrength", 1.0f);  // Full compression strength

        converter.drawBlocks(mIntermediate);

        mApplied = true;
        Log.d(TAG, "HDR compression applied to xyY intermediate: method=" + compressionMethod +
              ", maxHDR=" + maxHdrValue);
    }

    @Override
    public int getShader() {
        return R.raw.hdr_compress_xyy;
    }

    @Override
    public void close() {
        // Only close mIntermediate if we actually created it (mApplied = true)
        // If compression wasn't applied, mIntermediate is just a reference to another stage's texture
        if (mIntermediate != null && mApplied) {
            mIntermediate.close();
        }
    }
}
