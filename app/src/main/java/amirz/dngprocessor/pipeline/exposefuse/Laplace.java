package amirz.dngprocessor.pipeline.exposefuse;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;

import static amirz.dngprocessor.pipeline.exposefuse.FuseUtils.*;

/**
 * Builds Laplacian pyramids for exposure fusion.
 * 
 * A Laplacian pyramid decomposes an image into multiple frequency bands:
 * - Gaussian pyramid: G[i] = downsample(blur(G[i-1]))
 * - Laplacian pyramid: L[i] = G[i] - upsample(G[i+1])
 * 
 * The Laplacian levels contain high-frequency detail at each scale.
 * The coarsest Gaussian level contains the low-frequency base.
 * 
 * Reconstruction: G[i] = upsample(G[i+1]) + L[i]
 */
public class Laplace extends Stage {
    private static final int LEVELS = 10;

    public static class Pyramid {
        public Texture[] gauss;    // Gaussian pyramid (LEVELS textures)
        public Texture[] laplace;  // Laplacian pyramid (LEVELS-1 textures, high-freq detail)
        public boolean ownsLevel0; // Whether we own gauss[0] (copied vs referenced)
    }

    private Pyramid mUnderPyramid;
    private Pyramid mExtraHighlightPyramid;
    private Pyramid mOverPyramid;

    public Pyramid getUnderPyramid() {
        return mUnderPyramid;
    }

    public Pyramid getExtraHighlightPyramid() {
        return mExtraHighlightPyramid;
    }

    public Pyramid getOverPyramid() {
        return mOverPyramid;
    }

    /**
     * Release all pyramid textures. Call this when pyramids are no longer needed.
     */
    public void releasePyramid() {
        releasePyramid(mUnderPyramid);
        releasePyramid(mExtraHighlightPyramid);
        releasePyramid(mOverPyramid);
        mUnderPyramid = null;
        mExtraHighlightPyramid = null;
        mOverPyramid = null;
    }
    
    private void releasePyramid(Pyramid pyr) {
        if (pyr == null) return;
        
        // Release Gaussian pyramid
        if (pyr.gauss != null) {
            for (int i = 0; i < pyr.gauss.length; i++) {
                // Only close level 0 if we own it (copied it)
                if (i == 0 && !pyr.ownsLevel0) continue;
                if (pyr.gauss[i] != null) {
                    pyr.gauss[i].close();
                }
            }
        }
        
        // Release Laplacian pyramid
        if (pyr.laplace != null) {
            for (Texture tex : pyr.laplace) {
                if (tex != null) tex.close();
            }
        }
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        DoubleExpose de = previousStages.getStage(DoubleExpose.class);
        mUnderPyramid = createPyramid(de.getUnderexposed());
        mExtraHighlightPyramid = createPyramid(de.getExtraHighlight());
        mOverPyramid = createPyramid(de.getOverexposed());
    }

    /**
     * Create a Laplacian pyramid from an input texture.
     * 
     * @param input The input texture (not owned by pyramid, will not be closed)
     * @return Pyramid containing Gaussian and Laplacian levels
     */
    private Pyramid createPyramid(Texture input) {
        GLPrograms converter = getConverter();

        // Build Gaussian pyramid by repeated downsampling
        // G[0] = input, G[i] = downsample(G[i-1])
        Texture[] gauss = new Texture[LEVELS];
        gauss[0] = input;  // Reference to input, not owned
        for (int i = 1; i < gauss.length; i++) {
            gauss[i] = downsample2x(converter, gauss[i - 1]);
        }

        // Build Laplacian pyramid
        // L[i] = G[i] - upsample(G[i+1])
        // This captures the high-frequency detail lost during downsampling
        Texture[] upsampled = new Texture[LEVELS - 1];
        for (int i = 0; i < upsampled.length; i++) {
            upsampled[i] = upsample2x(converter, gauss[i + 1], gauss[i]);
        }

        // Compute difference (Laplacian = original - upsampled)
        Texture[] laplace = new Texture[LEVELS - 1];
        converter.useProgram(R.raw.stage4_4_difference);
        for (int i = 0; i < laplace.length; i++) {
            converter.setTexture("base", upsampled[i]);
            converter.setTexture("target", gauss[i]);

            laplace[i] = TexturePool.get(upsampled[i]);
            converter.drawBlocks(laplace[i]);
            upsampled[i].close();  // No longer needed after diff
        }

        Pyramid pyramid = new Pyramid();
        pyramid.gauss = gauss;
        pyramid.laplace = laplace;
        pyramid.ownsLevel0 = false;  // We don't own the input texture
        return pyramid;
    }

    @Override
    public int getShader() {
        return R.raw.stage4_2_downsample;
    }

    @Override
    public boolean isEnabled() {
        ProcessParams process = getProcessParams();
        // Run if exposeFuse toggle is enabled (independent of other stages)
        // Note: Requires DoubleExpose to have run, but that's handled by pipeline order
        // Note: This stage is not added to pipeline if EarlyExposureFusion is added
        return process != null && process.exposeFuse;
    }
    
    @Override
    public void close() {
        releasePyramid();
    }
}
