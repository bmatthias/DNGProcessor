package amirz.dngprocessor.pipeline.convert;

import amirz.dngprocessor.gl.Texture;

/**
 * Interface for stages that provide intermediate texture data.
 * Implemented by both EdgeMirror (for Bayer pipeline) and LinearRawEdgeMirror (for Linear Raw pipeline).
 */
public interface IntermediateProvider {
    Texture getIntermediate();
}

