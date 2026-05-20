package amirz.dngprocessor.parser;

import amirz.dngprocessor.params.SensorParams;

/**
 * Parsed data from a single DNG/RAW file, used by the burst processing pipeline.
 */
public class RawFrame {
    /** Raw pixel bytes, 16-bit per sample (same format as DngParser.rawImageInput). */
    public final byte[] rawBytes;

    /** Fully populated sensor parameters for this frame. */
    public final SensorParams sensor;

    /** Exposure time in seconds (from TIFF ExposureTime tag), 0 if unknown. */
    public final float exposureTime;

    /**
     * EXIF ExposureBiasValue in EV (positive = brighter than metered, negative = darker).
     * NaN if the tag was absent — the bracket detector falls back to
     * {@code log2(exposureTime / refExposureTime)} in that case.
     */
    public final float exposureBiasEv;

    /** ISO speed rating (EXIF), 0 if unknown — used as a fallback EV estimate. */
    public final int isoSpeed;

    /** Output crop width (DefaultCropSize[0]). */
    public final int cropWidth;

    /** Output crop height (DefaultCropSize[1]). */
    public final int cropHeight;

    /** Display filename (used for logging). */
    public final String fileName;

    /**
     * Per-burst relative EV vs the reference frame, populated by
     * {@link BurstParser} after bracket detection.
     *
     * <ul>
     *   <li>{@code 0} for the reference frame (darkest in a bracket; first in a uniform burst).</li>
     *   <li>{@code > 0} for brighter (longer-exposed / higher-ISO) comparison frames.</li>
     * </ul>
     *
     * Drives the per-frame exposure scaling applied before alignment and the
     * radiometric merge weights (hdr-plus-swift style).
     */
    public float relativeEv = 0f;

    public RawFrame(byte[] rawBytes, SensorParams sensor, float exposureTime,
                    float exposureBiasEv, int isoSpeed,
                    int cropWidth, int cropHeight, String fileName) {
        this.rawBytes = rawBytes;
        this.sensor = sensor;
        this.exposureTime = exposureTime;
        this.exposureBiasEv = exposureBiasEv;
        this.isoSpeed = isoSpeed;
        this.cropWidth = cropWidth;
        this.cropHeight = cropHeight;
        this.fileName = fileName;
    }
}
