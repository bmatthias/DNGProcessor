package amirz.dngprocessor.pipeline.convert;

import amirz.dngprocessor.gl.Texture;

/**
 * Interface for stages that provide RGB texture output
 */
public interface RgbProvider {
    Texture getRgbTex();
}
