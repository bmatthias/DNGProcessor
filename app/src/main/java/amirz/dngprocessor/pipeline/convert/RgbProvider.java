package amirz.dngprocessor.pipeline.convert;

import amirz.dngprocessor.gl.Texture;

/**
 * Interface for stages that provide an RGB texture to downstream stages.
 * Both {@link LinearRawPreProcess} and the burst {@code BurstLinearSrMerge} stage implement
 * this interface so that {@code LinearRawToIntermediate} can work with either source.
 */
public interface RgbProvider {
    /** The Float16 RGBA texture containing normalised linear-RGB data. */
    Texture getRgbTex();

    /** Image width in pixels. */
    default int getInWidth() {
        Texture t = getRgbTex();
        return t != null ? t.getWidth() : 0;
    }

    /** Image height in pixels. */
    default int getInHeight() {
        Texture t = getRgbTex();
        return t != null ? t.getHeight() : 0;
    }

    /**
     * Lens-shading gain-map texture, or {@code null} if none.
     * The caller must NOT close the returned texture – it is owned by the provider.
     * Default implementation returns null (no gain map).
     */
    default Texture getGainMapTex() { return null; }
}
