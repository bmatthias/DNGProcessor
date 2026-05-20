package amirz.dngprocessor.pipeline.convert;

import amirz.dngprocessor.gl.Texture;

/**
 * Interface for stages that supply a normalised Bayer sensor texture to downstream stages
 * (GreenDemosaic, ToIntermediate).  Both the single-frame {@link PreProcess} and the
 * burst {@code BurstSrUpsample} stage implement this interface so that downstream stages can
 * work with either source transparently.
 */
public interface BayerProvider {
    /** The normalised Float16 single-channel Bayer texture. */
    Texture getSensorTex();

    /** Sensor input width in pixels. */
    int getInWidth();

    /** Sensor input height in pixels. */
    int getInHeight();

    /** CFA pattern index (0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR). */
    int getCfaPattern();

    /** Lens-shading gain-map texture, or {@code null} if none. */
    Texture getGainMapTex();
}
