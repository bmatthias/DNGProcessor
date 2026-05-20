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

public class MergeDetail extends Stage implements IntermediateProvider {
    private static final String TAG = "MergeDetail";

    private static final float MIN_GAMMA = 0.55f;
    private static final float MIN_GAMMA_AHE = 0.45f;  // More aggressive for AHE

    private final float mHistFactor;
    private final boolean mAhe;
    private final boolean mEdgeAwareHistEq;
    private Texture mIntermediate;
    private boolean mOwnsTexture = false;  // Whether we created our own output texture

    public MergeDetail(ProcessParams processParams) {
        mHistFactor = processParams.histFactor;
        mAhe = processParams.ahe;
        mEdgeAwareHistEq = processParams.edgeAwareHistEq;
    }

    public Texture getIntermediate() {
        return mIntermediate;
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();

        BilateralFilter bilateral = previousStages.getStage(BilateralFilter.class);
        Texture bilateralTex = mEdgeAwareHistEq && bilateral != null ? bilateral.getBilateral() : null;

        // Get input from most recent IntermediateProvider (could be Merge, LocalLaplacian, ToneEqualizer, etc.)
        Texture inputIntermediate = previousStages.getStageByInterface(IntermediateProvider.class).getIntermediate();

        Analysis sampleHistogram = previousStages.getStage(Analysis.class);
        float[] hist = sampleHistogram.getHist();
        // Use percentiles instead of min/max for robust normalization
        // This prevents extreme contrast when baseline exposure is high
        float minLum = sampleHistogram.getP01Luminance();
        float maxLum = sampleHistogram.getP99Luminance();

        // Must use direct buffer for OpenGL ES
        FloatBuffer histBuffer = ByteBuffer.allocateDirect(hist.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        histBuffer.put(hist);
        histBuffer.rewind();
        
        Texture histTex = new Texture(hist.length, 1, 1, Texture.Format.Float16,
                histBuffer, GL_LINEAR, GL_CLAMP_TO_EDGE);
        converter.setTexture("hist", histTex);
        converter.setf("histOffset", 0.5f / hist.length, 1.f - 1.f / hist.length);
        
        // Pass percentiles for histogram normalization/stretching
        // Using percentiles (p01, p99) instead of min/max is more robust to outliers
        // and prevents extreme contrast when baseline exposure shifts the image brightness
        float lumRange = maxLum - minLum;
        if (lumRange < 0.001f) {
            // Fallback if range is too small
            minLum = 0.0f;
            maxLum = 1.0f;
            lumRange = 1.0f;
        }
        converter.setf("histMinMax", minLum, maxLum);
        Log.d(TAG, "Histogram range (percentiles): [" + minLum + ", " + maxLum + "] (range=" + lumRange + ")");

        // If there are many dark patches, the color noise goes up.
        // To ensure that we do not boost that too much, reduce with color noise.
        float[] sigma = sampleHistogram.getSigma();
        float colorNoise = (float) Math.hypot(sigma[0], sigma[1]);

        // AHE mode uses more aggressive settings for better shadow/highlight recovery
        float baseMinGamma = mAhe ? MIN_GAMMA_AHE : MIN_GAMMA;
        float minGamma = Math.min(1f, baseMinGamma + 3f * colorNoise);
        float gamma = sampleHistogram.getGamma();
        gamma = Math.max(minGamma, gamma < 1.f ? 0.55f + 0.45f * gamma : gamma);

        // AHE boosts the histogram factor for more aggressive equalization
        float effectiveHistFactor = mAhe ? Math.min(1.2f, mHistFactor * 1.5f) : mHistFactor;
        
        // When histFactor is 0 (histogram equalization disabled), also disable gamma correction
        // to prevent any brightening from this stage
        if (mHistFactor == 0f) {
            gamma = 1.0f;  // No gamma correction - pass through unchanged
        } else {
            gamma = (float) Math.pow(gamma, effectiveHistFactor);
        }
        Log.d(TAG, "Setting gamma of " + gamma + " (original " + sampleHistogram.getGamma()
                + ", AHE=" + mAhe + ", histFactor=" + mHistFactor + ")");
        converter.setf("gamma", gamma);

        // Reduce the histogram equalization in scenes with good light distribution.
        // AHE mode keeps more histogram equalization active
        float baseHistEq = mAhe ? 0.5f : 0.4f;
        float gammaReduction = mAhe ? 0.4f : 0.6f;
        float noiseReduction = mAhe ? 2f : 4f;
        float bilatHistEq = Math.max(baseHistEq, 1f - sampleHistogram.getGamma() * gammaReduction
                - noiseReduction * colorNoise);
        Log.d(TAG, "Smoothed histogram equalization " + bilatHistEq + " (AHE=" + mAhe + ")");
        converter.setf("histFactor", bilatHistEq * effectiveHistFactor);

        converter.setTexture("intermediate", inputIntermediate);
        if (bilateralTex != null) {
            converter.setTexture("bilateral", bilateralTex);
            converter.seti("useEdgeAware", 1);
        } else {
            converter.seti("useEdgeAware", 0);
        }

        // Create a SEPARATE output texture to avoid reading/writing the same texture
        // (which is undefined behavior in OpenGL)
        mIntermediate = TexturePool.get(inputIntermediate.getWidth(), inputIntermediate.getHeight(), 
                inputIntermediate.getChannels(), inputIntermediate.getFormat());
        mOwnsTexture = true;

        converter.drawBlocks(mIntermediate);
        
        histTex.close();  // Clean up histogram texture

        //bilateralTex.close();
    }

    @Override
    public int getShader() {
        return R.raw.stage2_4_merge_detail;
    }

    @Override
    public void close() {
        // Close the texture if we created it ourselves
        if (mOwnsTexture && mIntermediate != null) {
            mIntermediate.close();
            mIntermediate = null;
            mOwnsTexture = false;
        }
    }
}
