package amirz.dngprocessor.util;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import amirz.dngprocessor.params.SensorParams;
import amirz.dngprocessor.parser.TIFF;

/**
 * Minimal little-endian DNG (TIFF/EP) writer for a single Bayer CFA image plane.
 *
 * <p>The writer produces a self-contained DNG that downstream tools (Lightroom,
 * dcraw, RawTherapee, this app's own {@link amirz.dngprocessor.parser.DngParser})
 * can re-open. Output is:
 * <ul>
 *   <li>Little-endian (Intel byte order, "II" + magic 42)</li>
 *   <li>Single IFD0 (no SubIFDs / thumbnails)</li>
 *   <li>Uncompressed, single strip, 16-bit unsigned samples, Bayer CFA</li>
 *   <li>{@code BlackLevel = 0}, {@code WhiteLevel = 65535} — the caller's pixel
 *       buffer is expected to be black-level-subtracted and normalised to
 *       {@code [0, 1]} → {@code [0, 65535]} in the channel ordering of the
 *       supplied CFA pattern.</li>
 *   <li>{@code AsShotNeutral} mirrors the reference sensor's
 *       {@code neutralColorPoint} so DNG consumers can apply the same white
 *       balance the source frame carried.</li>
 *   <li>Color matrices, calibration illuminants and forward matrices are copied
 *       verbatim from the supplied {@link SensorParams} so the file still
 *       describes the original sensor's color rendering.</li>
 * </ul>
 *
 * <p>Designed specifically for dumping the burst-merged CFA texture as an
 * intermediate artefact, not for general-purpose DNG production.
 */
public final class DngWriter {
    private static final String TAG = "DngWriter";

    /** Convenience entry point used by the burst pipeline. */
    public static void writeMergedBayer(String outputPath,
                                        short[] pixels16, int width, int height,
                                        SensorParams refSensor, int srFactor,
                                        String make, String model,
                                        String software) throws IOException {
        // Derive the 4-byte CFA pattern from the validated sensor.cfa enum
        // rather than the raw cfaVal bytes: the source DNG may store
        // CFAPattern as TIFF/EP-style TYPE_Undefined with a 2-byte X/Y
        // dimension header (e.g. [2,2,0,1,1,2] for RGGB), in which case
        // taking the first 4 bytes of cfaVal would write a nonsense pattern
        // ([2,2,0,1] = BBRG) and the DNG consumer cannot demosaic, producing
        // the classic "pink + fine-dotted CFA mosaic" rendering.
        byte[] cfa = cfaFromSensor(refSensor);

        // DefaultCropOrigin / Size deliberately stays at (0, 0, W, H):
        //  * The whole merged buffer is "image content" — there is no optical
        //    black margin to skip after our pipeline.
        //  * It guarantees the visible-image origin shares CFA parity with the
        //    sensor origin, so consumers that interpret CFAPattern relative
        //    to the crop (rather than the spec-mandated sensor origin) still
        //    line up correctly.
        int cropX = 0, cropY = 0;
        int cropW = width;
        int cropH = height;

        // The merged texture is NOT white-balanced yet (the original pipeline applies WB
        // later in ToIntermediate via neutralColorPoint). Mirror that into AsShotNeutral
        // so DNG consumers WB the file themselves and the merge looks neutrally rendered.
        float[] asShotNeutral = (refSensor.neutralColorPoint != null
                && refSensor.neutralColorPoint.length == 3)
                ? refSensor.neutralColorPoint
                : new float[] { 1f, 1f, 1f };

        Log.i(TAG, String.format("writeMergedBayer: %dx%d CFA=[%d,%d,%d,%d] "
                        + "(sensor.cfa=%d cfaValLen=%d) AsShotNeutral=(%.3f,%.3f,%.3f)",
                width, height, cfa[0], cfa[1], cfa[2], cfa[3],
                refSensor.cfa,
                refSensor.cfaVal == null ? -1 : refSensor.cfaVal.length,
                asShotNeutral[0], asShotNeutral[1], asShotNeutral[2]));

        new DngWriter()
                .setMake(make)
                .setModel(model)
                .setSoftware(software)
                .setUniqueCameraModel((make == null ? "" : make + " ")
                        + (model == null ? "Merged Bayer" : model + " (Burst Merged)"))
                .setImage(pixels16, width, height, cfa)
                .setCrop(cropX, cropY, cropW, cropH)
                .setColorMatrices(
                        refSensor.colorMatrix1, refSensor.colorMatrix2,
                        refSensor.forwardTransform1, refSensor.forwardTransform2,
                        refSensor.calibrationTransform1, refSensor.calibrationTransform2,
                        refSensor.referenceIlluminant1, refSensor.referenceIlluminant2)
                .setAsShotNeutral(asShotNeutral)
                .setBaselineExposure(refSensor.baselineExposure)
                .write(outputPath);
    }

    /**
     * Derive the 4-byte CFAPattern from the parsed sensor metadata in a way
     * that's robust to TIFF/EP-style CFAPattern tags carrying a 2-byte
     * dimension header. We prefer the validated {@code sensor.cfa} enum
     * because it has been matched against the canonical 4-byte patterns and
     * is guaranteed to be one of RGGB/GRBG/GBRG/BGGR or -1.
     */
    private static byte[] cfaFromSensor(SensorParams refSensor) {
        if (refSensor != null) {
            switch (refSensor.cfa) {
                case 0: return new byte[] { 0, 1, 1, 2 }; // RGGB
                case 1: return new byte[] { 1, 0, 2, 1 }; // GRBG
                case 2: return new byte[] { 1, 2, 0, 1 }; // GBRG
                case 3: return new byte[] { 2, 1, 1, 0 }; // BGGR
                default: /* -1: unknown, try cfaVal */ break;
            }
            // Sensor.cfa was -1: cfaVal may already be a clean 4-byte pattern,
            // OR may have a 2-byte X/Y header. Strip a header if it looks like
            // one (first two bytes both 2 with at least 6 total bytes).
            byte[] v = refSensor.cfaVal;
            if (v != null) {
                int off = (v.length >= 6 && v[0] == 2 && v[1] == 2) ? 2 : 0;
                if (v.length >= off + 4) {
                    return new byte[] { v[off], v[off + 1], v[off + 2], v[off + 3] };
                }
            }
        }
        // Burst pipeline is RGGB-biased (its preprocess shader hardcodes
        // RGGB ordering for blackLevel/gains), so RGGB is the safest fallback.
        return new byte[] { 0, 1, 1, 2 };
    }

    // -----------------------------------------------------------------------
    // Builder state
    // -----------------------------------------------------------------------

    private short[] mPixels16;
    private int mWidth, mHeight;
    private byte[] mCfa = new byte[] { 0, 1, 1, 2 };

    private int mCropX, mCropY, mCropW, mCropH;

    private float[] mColorMatrix1, mColorMatrix2;
    private float[] mForwardMatrix1, mForwardMatrix2;
    private float[] mCalibrationTransform1, mCalibrationTransform2;
    private int mCalibrationIlluminant1 = 21; // D65 default
    private int mCalibrationIlluminant2 = 17; // Standard light A default

    private float mBaselineExposure = 0f;
    /**
     * AsShotNeutral: per-channel reciprocal of the as-shot white. (1,1,1)
     * means WB has already been baked into the pixel data (legacy default);
     * non-trivial values let downstream DNG consumers apply their own WB.
     */
    private float[] mAsShotNeutral = new float[] { 1f, 1f, 1f };
    private String mMake = "DNGProcessor";
    private String mModel = "Burst Merged";
    private String mSoftware = "DNGProcessor Burst";
    private String mUniqueCameraModel = "DNGProcessor Burst Merged";

    public DngWriter setImage(short[] pixels16, int width, int height, byte[] cfa) {
        if (pixels16.length != width * height)
            throw new IllegalArgumentException("pixels length " + pixels16.length
                    + " != width*height " + width * height);
        if (cfa == null || cfa.length < 4)
            throw new IllegalArgumentException("CFA pattern must be 4 bytes");
        mPixels16 = pixels16;
        mWidth = width;
        mHeight = height;
        mCfa = new byte[] { cfa[0], cfa[1], cfa[2], cfa[3] };
        mCropW = width;
        mCropH = height;
        return this;
    }

    public DngWriter setCrop(int x, int y, int w, int h) {
        mCropX = x; mCropY = y;
        mCropW = Math.max(1, w);
        mCropH = Math.max(1, h);
        return this;
    }

    public DngWriter setColorMatrices(float[] cm1, float[] cm2,
                                       float[] fm1, float[] fm2,
                                       float[] cc1, float[] cc2,
                                       int illum1, int illum2) {
        mColorMatrix1 = cm1;
        mColorMatrix2 = cm2;
        mForwardMatrix1 = fm1;
        mForwardMatrix2 = fm2;
        mCalibrationTransform1 = cc1;
        mCalibrationTransform2 = cc2;
        if (illum1 != 0) mCalibrationIlluminant1 = illum1;
        if (illum2 != 0) mCalibrationIlluminant2 = illum2;
        return this;
    }

    public DngWriter setBaselineExposure(float baselineExposure) {
        mBaselineExposure = baselineExposure;
        return this;
    }

    public DngWriter setAsShotNeutral(float[] asShotNeutral) {
        if (asShotNeutral != null && asShotNeutral.length == 3) {
            mAsShotNeutral = new float[] {
                    asShotNeutral[0], asShotNeutral[1], asShotNeutral[2] };
        }
        return this;
    }

    public DngWriter setMake(String make)     { if (make != null) mMake = make; return this; }
    public DngWriter setModel(String model)   { if (model != null) mModel = model; return this; }
    public DngWriter setSoftware(String software) { if (software != null) mSoftware = software; return this; }
    public DngWriter setUniqueCameraModel(String s) { if (s != null) mUniqueCameraModel = s; return this; }

    // -----------------------------------------------------------------------
    // Encoding
    // -----------------------------------------------------------------------

    /**
     * Single in-memory tag entry, complete with its eventual data-blob bytes
     * (or null if the value fits inline).
     */
    private static final class Tag {
        final int id;
        final int type;
        final int count;
        final byte[] data;       // value bytes (may be > 4 — caller chooses inline vs offset)
        final int inlineBytes;   // bytes used inline when data.length <= 4

        Tag(int id, int type, int count, byte[] data, int inlineBytes) {
            this.id = id;
            this.type = type;
            this.count = count;
            this.data = data;
            this.inlineBytes = inlineBytes;
        }

        boolean isInline() { return inlineBytes <= 4 && data.length <= 4; }
    }

    public void write(String outputPath) throws IOException {
        if (mPixels16 == null) throw new IllegalStateException("setImage(...) not called");
        if (mColorMatrix1 == null || mColorMatrix1.length != 9)
            throw new IllegalStateException("setColorMatrices(...) requires a 3x3 ColorMatrix1");

        // ---- Build the tag list ----
        List<Tag> tags = new ArrayList<>(40);

        addUInt32(tags, TIFF.TAG_NewSubfileType, 0);
        addUInt32(tags, TIFF.TAG_ImageWidth, mWidth);
        addUInt32(tags, TIFF.TAG_ImageLength, mHeight);
        addUInt16Array(tags, TIFF.TAG_BitsPerSample, new int[] { 16 });
        addUInt16(tags, TIFF.TAG_Compression, TIFF.COMPRESSION_NONE);
        addUInt16(tags, TIFF.TAG_PhotometricInterpretation, TIFF.PHOTOMETRIC_CFA);
        addString(tags, TIFF.TAG_Make, mMake);
        addString(tags, TIFF.TAG_Model, mModel);

        // StripOffsets placeholder — patched after layout pass.
        Tag stripOffsetsTag = new Tag(TIFF.TAG_StripOffsets, TIFF.TYPE_UInt_32, 1,
                u32(0), 4);
        tags.add(stripOffsetsTag);

        addUInt16(tags, TIFF.TAG_Orientation, 1);
        addUInt16(tags, TIFF.TAG_SamplesPerPixel, 1);
        addUInt32(tags, TIFF.TAG_RowsPerStrip, mHeight);
        addUInt32(tags, TIFF.TAG_StripByteCounts, mWidth * mHeight * 2);
        addUInt16(tags, TIFF.TAG_PlanarConfiguration, 1);
        // SampleFormat (339): 1 = unsigned integer. Strict DNG readers that
        // default to signed when this tag is missing would interpret values
        // above 32767 as negative and clip them — adding the explicit tag
        // removes that ambiguity.
        addUInt16Array(tags, TIFF.TAG_SampleFormat, new int[] { 1 });
        addString(tags, TIFF.TAG_Software, mSoftware);

        // CFA description tags.
        addUInt16Array(tags, TIFF.TAG_CFARepeatPatternDim, new int[] { 2, 2 });
        addBytes(tags, TIFF.TAG_CFAPattern, mCfa);

        // DNG core.
        addBytes(tags, TIFF.TAG_DNGVersion, new byte[] { 1, 4, 0, 0 });
        addBytes(tags, TIFF.TAG_DNGBackwardVersion, new byte[] { 1, 1, 0, 0 });
        addString(tags, TIFF.TAG_UniqueCameraModel, mUniqueCameraModel);

        // CFA plane color / layout.
        addBytes(tags, TIFF.TAG_CFAPlaneColor, new byte[] { 0, 1, 2 });
        addUInt16(tags, TIFF.TAG_CFALayout, 1); // Rectangular grid (square pixels)

        addUInt16Array(tags, TIFF.TAG_BlackLevelRepeatDim, new int[] { 2, 2 });
        // BlackLevel: 4 zeros (because we normalised black out already)
        addRationals(tags, TIFF.TAG_BlackLevel,
                new long[][] { { 0, 1 }, { 0, 1 }, { 0, 1 }, { 0, 1 } });
        addUInt32(tags, TIFF.TAG_WhiteLevel, 65535);

        addUInt32Array(tags, TIFF.TAG_DefaultCropOrigin, new long[] { mCropX, mCropY });
        addUInt32Array(tags, TIFF.TAG_DefaultCropSize,   new long[] { mCropW, mCropH });

        addSRationals(tags, TIFF.TAG_ColorMatrix1, matrixToRationals(mColorMatrix1));
        if (mColorMatrix2 != null && mColorMatrix2.length == 9) {
            addSRationals(tags, TIFF.TAG_ColorMatrix2, matrixToRationals(mColorMatrix2));
        }
        if (mCalibrationTransform1 != null && mCalibrationTransform1.length == 9) {
            addSRationals(tags, TIFF.TAG_CameraCalibration1, matrixToRationals(mCalibrationTransform1));
        }
        if (mCalibrationTransform2 != null && mCalibrationTransform2.length == 9) {
            addSRationals(tags, TIFF.TAG_CameraCalibration2, matrixToRationals(mCalibrationTransform2));
        }
        if (mForwardMatrix1 != null && mForwardMatrix1.length == 9) {
            addSRationals(tags, TIFF.TAG_ForwardMatrix1, matrixToRationals(mForwardMatrix1));
        }
        if (mForwardMatrix2 != null && mForwardMatrix2.length == 9) {
            addSRationals(tags, TIFF.TAG_ForwardMatrix2, matrixToRationals(mForwardMatrix2));
        }

        // AnalogBalance = (1,1,1) since we already applied gain.
        addRationals(tags, TIFF.TAG_AnalogBalance,
                new long[][] { { 1, 1 }, { 1, 1 }, { 1, 1 } });
        // AsShotNeutral: per-channel reciprocal of as-shot white point. Defaults
        // to (1,1,1) (WB baked into pixels); the caller may override via
        // setAsShotNeutral(refSensor.neutralColorPoint) so DNG readers WB the
        // file themselves and the merged data renders neutrally.
        addRationals(tags, TIFF.TAG_AsShotNeutral, new long[][] {
                toRational(mAsShotNeutral[0]),
                toRational(mAsShotNeutral[1]),
                toRational(mAsShotNeutral[2]) });

        addUInt16(tags, TIFF.TAG_CalibrationIlluminant1, mCalibrationIlluminant1);
        addUInt16(tags, TIFF.TAG_CalibrationIlluminant2, mCalibrationIlluminant2);

        // ActiveArea = full image (top, left, bottom, right).
        addUInt32Array(tags, TIFF.TAG_ActiveArea,
                new long[] { 0, 0, mHeight, mWidth });

        addSRationals(tags, TIFF.TAG_BaselineExposure,
                new long[][] { toSRational(mBaselineExposure) });

        // Tags must be sorted ascending by ID per TIFF spec.
        Collections.sort(tags, (a, b) -> Integer.compare(a.id & 0xFFFF, b.id & 0xFFFF));

        // ---- Layout pass: compute offsets ----
        // [0..8]   TIFF header
        // [8..]    IFD0: u16 count + N*12 entries + u32 next-IFD-offset
        // [...]    Out-of-line value blobs (sorted by tag order)
        // [...]    Strip pixel data
        final int headerSize = 8;
        final int ifdHeaderSize = 2;                 // u16 tagCount
        final int ifdEntrySize  = 12;                // tag, type, count, value
        final int ifdNextSize   = 4;                 // next IFD offset
        int ifdStart   = headerSize;
        int ifdEnd     = ifdStart + ifdHeaderSize + tags.size() * ifdEntrySize + ifdNextSize;

        int blobCursor = ifdEnd;
        int[] tagBlobOffsets = new int[tags.size()];
        for (int i = 0; i < tags.size(); i++) {
            Tag t = tags.get(i);
            if (t.isInline()) {
                tagBlobOffsets[i] = -1;
            } else {
                // 2-byte align all out-of-line blobs for safety.
                if ((blobCursor & 1) != 0) blobCursor++;
                tagBlobOffsets[i] = blobCursor;
                blobCursor += t.data.length;
            }
        }
        // Align strip start to a 2-byte boundary.
        if ((blobCursor & 1) != 0) blobCursor++;
        int stripOffset = blobCursor;
        long stripBytes = (long) mWidth * mHeight * 2;
        long totalSize = (long) stripOffset + stripBytes;

        // Patch StripOffsets with the actual strip offset.
        int sIdx = tags.indexOf(stripOffsetsTag);
        if (sIdx < 0) throw new IllegalStateException("StripOffsets tag missing after sort");
        tags.set(sIdx, new Tag(TIFF.TAG_StripOffsets, TIFF.TYPE_UInt_32, 1,
                u32(stripOffset), 4));

        // ---- Serialise header + IFD + tag blobs into a small in-memory buffer ----
        // The strip data is the big chunk and we stream it row-by-row directly to disk
        // to avoid allocating a single huge byte[] (e.g. 25 MB for a 4096×3072 16-bit image).
        ByteBuffer head = ByteBuffer.allocate(stripOffset).order(ByteOrder.LITTLE_ENDIAN);
        // TIFF header
        head.put((byte) 'I'); head.put((byte) 'I');
        head.putShort((short) 42);
        head.putInt(ifdStart);

        // IFD0
        head.position(ifdStart);
        head.putShort((short) tags.size());
        for (int i = 0; i < tags.size(); i++) {
            Tag t = tags.get(i);
            head.putShort((short) (t.id & 0xFFFF));
            head.putShort((short) (t.type & 0xFFFF));
            head.putInt(t.count);
            if (t.isInline()) {
                byte[] padded = new byte[4];
                System.arraycopy(t.data, 0, padded, 0, Math.min(4, t.data.length));
                head.put(padded);
            } else {
                head.putInt(tagBlobOffsets[i]);
            }
        }
        head.putInt(0); // No next IFD.

        // Out-of-line blobs.
        for (int i = 0; i < tags.size(); i++) {
            Tag t = tags.get(i);
            if (tagBlobOffsets[i] < 0) continue;
            head.position(tagBlobOffsets[i]);
            head.put(t.data);
        }

        // ---- Write header + streamed strip data ----
        try (FileOutputStream fos = new FileOutputStream(outputPath);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 1 << 20)) {
            bos.write(head.array(), 0, stripOffset);

            // Write pixel rows in little-endian. We allocate a single row buffer and reuse it.
            ByteBuffer rowBuf = ByteBuffer.allocate(mWidth * 2).order(ByteOrder.LITTLE_ENDIAN);
            byte[] rowBytes = rowBuf.array();
            for (int y = 0; y < mHeight; y++) {
                rowBuf.clear();
                int rowOff = y * mWidth;
                for (int x = 0; x < mWidth; x++) {
                    rowBuf.putShort(mPixels16[rowOff + x]);
                }
                bos.write(rowBytes, 0, mWidth * 2);
            }
        }
        Log.i(TAG, "Wrote merged DNG: " + outputPath + " (" + mWidth + "x" + mHeight
                + ", " + totalSize + " bytes)");
    }

    // -----------------------------------------------------------------------
    // Tag helpers
    // -----------------------------------------------------------------------

    private static void addUInt16(List<Tag> tags, int id, int value) {
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) (value & 0xFFFF));
        b.putShort((short) 0); // pad
        tags.add(new Tag(id, TIFF.TYPE_UInt_16, 1, b.array(), 4));
    }

    private static void addUInt32(List<Tag> tags, int id, long value) {
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt((int) value);
        tags.add(new Tag(id, TIFF.TYPE_UInt_32, 1, b.array(), 4));
    }

    private static void addUInt16Array(List<Tag> tags, int id, int[] values) {
        ByteBuffer b = ByteBuffer.allocate(Math.max(4, values.length * 2))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) b.putShort((short) (v & 0xFFFF));
        // Pad remaining bytes (only matters for inline storage; harmless otherwise).
        while (b.position() < b.capacity()) b.put((byte) 0);
        tags.add(new Tag(id, TIFF.TYPE_UInt_16, values.length, b.array(),
                values.length * 2));
    }

    private static void addUInt32Array(List<Tag> tags, int id, long[] values) {
        ByteBuffer b = ByteBuffer.allocate(Math.max(4, values.length * 4))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (long v : values) b.putInt((int) v);
        tags.add(new Tag(id, TIFF.TYPE_UInt_32, values.length, b.array(),
                values.length * 4));
    }

    private static void addString(List<Tag> tags, int id, String s) {
        if (s == null) s = "";
        // ASCII strings in TIFF are null-terminated; count includes the terminator.
        byte[] ascii = (s + "\0").getBytes();
        // Pad to at least 4 bytes for inline storage.
        byte[] data = ascii.length < 4 ? new byte[4] : ascii;
        if (data != ascii) System.arraycopy(ascii, 0, data, 0, ascii.length);
        tags.add(new Tag(id, TIFF.TYPE_String, ascii.length, data, ascii.length));
    }

    private static void addBytes(List<Tag> tags, int id, byte[] data) {
        byte[] payload = data.length < 4 ? new byte[4] : data;
        if (payload != data) System.arraycopy(data, 0, payload, 0, data.length);
        tags.add(new Tag(id, TIFF.TYPE_Byte, data.length, payload, data.length));
    }

    private static void addRationals(List<Tag> tags, int id, long[][] numDen) {
        ByteBuffer b = ByteBuffer.allocate(numDen.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (long[] r : numDen) {
            b.putInt((int) r[0]);
            b.putInt((int) r[1]);
        }
        tags.add(new Tag(id, TIFF.TYPE_UFrac, numDen.length, b.array(), b.capacity()));
    }

    private static void addSRationals(List<Tag> tags, int id, long[][] numDen) {
        ByteBuffer b = ByteBuffer.allocate(numDen.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (long[] r : numDen) {
            b.putInt((int) r[0]);
            b.putInt((int) r[1]);
        }
        tags.add(new Tag(id, TIFF.TYPE_Frac, numDen.length, b.array(), b.capacity()));
    }

    private static byte[] u32(int v) {
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(v);
        return b.array();
    }

    /** Convert a 3x3 float matrix to 9 signed rationals scaled by 10000. */
    private static long[][] matrixToRationals(float[] m) {
        long[][] out = new long[9][2];
        for (int i = 0; i < 9; i++) out[i] = toSRational(m[i]);
        return out;
    }

    /** Encode a finite float as a signed rational (numerator/denominator). */
    private static long[] toSRational(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return new long[] { 0, 1 };
        final int denom = 10000;
        long num = Math.round((double) v * denom);
        return new long[] { num, denom };
    }

    /**
     * Encode a non-negative finite float as an unsigned rational. NaN / Inf
     * and negatives collapse to 0/1. Used for tags whose TIFF type is
     * RATIONAL (e.g. {@code AsShotNeutral}).
     */
    private static long[] toRational(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v) || v < 0f) return new long[] { 0, 1 };
        final int denom = 10000;
        long num = Math.round((double) v * denom);
        return new long[] { num, denom };
    }
}
