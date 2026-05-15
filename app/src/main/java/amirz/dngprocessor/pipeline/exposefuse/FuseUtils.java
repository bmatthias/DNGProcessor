package amirz.dngprocessor.pipeline.exposefuse;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;

public class FuseUtils {
    
    // Gaussian blur parameters for pyramid operations
    // sigma ≈ 1.0 is standard for 2x downsampling (Nyquist anti-aliasing)
    // radius should cover ~2-3 sigma for good Gaussian approximation
    private static final float BLUR_SIGMA = 1.0f;
    private static final int BLUR_RADIUS = 2;  // 5-tap kernel covers ±2σ
    
    /**
     * Downsample a texture by 2x with proper Gaussian anti-aliasing.
     * Uses standard dimension calculation: ceil(width/2) = (width + 1) / 2
     */
    public static Texture downsample2x(GLPrograms converter, Texture in) {
        // Standard dimension calculation for 2x downsampling
        // (width + 1) / 2 rounds up, ensuring we don't lose edge pixels
        int newWidth = (in.getWidth() + 1) / 2;
        int newHeight = (in.getHeight() + 1) / 2;
        
        Texture downsampled = TexturePool.get(newWidth, newHeight,
                in.getChannels(), in.getFormat());

        // Gaussian blur before downsampling is REQUIRED for proper Laplacian pyramids
        // Without blur, aliasing artifacts cause banding in the final image
        // The Nyquist theorem requires low-pass filtering before downsampling
        Texture blurred = TexturePool.get(in);
        Texture tmp = TexturePool.get(in);
        blur2x(converter, in, tmp, blurred);
        tmp.close();

        converter.useProgram(R.raw.stage4_2_downsample);
        converter.setTexture("buf", blurred);
        converter.seti("maxxy", blurred.getWidth() - 1, blurred.getHeight() - 1);
        converter.drawBlocks(downsampled, false);

        blurred.close();

        return downsampled;
    }

    /**
     * Upsample a texture by 2x with bilinear interpolation and Gaussian smoothing.
     * @param in The texture to upsample
     * @param dimens A texture whose dimensions define the output size
     */
    public static Texture upsample2x(GLPrograms converter, Texture in, Texture dimens) {
        Texture upsampled = TexturePool.get(dimens);

        converter.useProgram(R.raw.stage4_3_upsample);
        converter.setTexture("buf", in);
        // Pass max coordinates for edge clamping
        converter.seti("maxxy", in.getWidth() - 1, in.getHeight() - 1);
        converter.drawBlocks(upsampled);

        // Gaussian blur after upsampling for proper reconstruction
        // This smooths the interpolation and prevents aliasing artifacts
        Texture blurred = TexturePool.get(upsampled);
        Texture tmp = TexturePool.get(upsampled);
        blur2x(converter, upsampled, tmp, blurred);
        tmp.close();
        upsampled.close();

        return blurred;
    }

    /**
     * Apply separable Gaussian blur (horizontal then vertical).
     */
    public static void blur2x(GLPrograms converter, Texture in, Texture tmp, Texture out) {
        converter.useProgram(R.raw.stage4_0_blur_1ch_fs);
        converter.seti("bufSize", in.getWidth(), in.getHeight());
        converter.setf("sigma", BLUR_SIGMA);
        converter.seti("radius", BLUR_RADIUS);

        converter.setTexture("buf", in);
        converter.seti("dir", 1, 0); // Horizontal
        converter.drawBlocks(tmp, false);

        converter.setTexture("buf", tmp);
        converter.seti("dir", 0, 1); // Vertical
        converter.drawBlocks(out, false);
    }
}
