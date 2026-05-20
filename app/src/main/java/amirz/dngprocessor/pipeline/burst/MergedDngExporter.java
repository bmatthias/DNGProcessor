package amirz.dngprocessor.pipeline.burst;

import android.opengl.GLES20;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.math.BlockDivider;
import amirz.dngprocessor.params.SensorParams;
import amirz.dngprocessor.parser.RawFrame;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.BayerProvider;
import amirz.dngprocessor.util.Constants;
import amirz.dngprocessor.util.DngWriter;

import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_FRAMEBUFFER_COMPLETE;
import static android.opengl.GLES20.GL_NO_ERROR;
import static android.opengl.GLES20.GL_RGBA;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glCheckFramebufferStatus;
import static android.opengl.GLES20.glGetError;
import static android.opengl.GLES20.glGetIntegerv;
import static android.opengl.GLES30.GL_FLOAT;
import static android.opengl.GLES30.GL_IMPLEMENTATION_COLOR_READ_FORMAT;
import static android.opengl.GLES30.GL_IMPLEMENTATION_COLOR_READ_TYPE;
import static android.opengl.GLES30.GL_PACK_ALIGNMENT;
import static android.opengl.GLES30.GL_PACK_ROW_LENGTH;
import static android.opengl.GLES30.GL_PACK_SKIP_PIXELS;
import static android.opengl.GLES30.GL_PACK_SKIP_ROWS;
import static android.opengl.GLES30.GL_RED;

/**
 * Reads the merged Bayer texture produced by {@link BurstSrUpsample} and writes it
 * as a self-contained DNG side-car file. Inserted between {@link BurstSrUpsample}
 * and the downstream demosaic/JPEG stages so the merged sensor data can also be
 * inspected / re-processed independently of the JPEG output.
 *
 * <p>This stage is a pure side effect on the CPU: it does not modify the GPU
 * texture or any pipeline state, and its draws are limited to the single
 * read-back. Downstream stages continue to consume the same merged texture.
 */
public class MergedDngExporter extends Stage {
    private static final String TAG = "MergedDngExporter";

    /**
     * DEBUG: write the raw 16-bit pixel buffer (little-endian, row-major,
     * no header) to a side-car ".u16" file next to the DNG. Lets us load
     * the buffer in numpy/ImageJ outside the DNG container to determine
     * whether corruption is introduced by the readback or by DngWriter.
     */
    public static final boolean DUMP_RAW_U16_SIDECAR = true;

    private final BayerProvider mMergeStage;
    private final RawFrame mRefFrame;
    private final int mSrFactor;
    private final String mOutputPath;

    public MergedDngExporter(BayerProvider mergeStage, RawFrame refFrame,
                              int srFactor, String outputPath) {
        mMergeStage = mergeStage;
        mRefFrame = refFrame;
        mSrFactor = Math.max(1, srFactor);
        mOutputPath = outputPath;
    }

    @Override
    public boolean isEnabled() {
        return mOutputPath != null && !mOutputPath.isEmpty();
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        Texture merged = mMergeStage.getSensorTex();
        if (merged == null) {
            Log.w(TAG, "Merged texture is null; skipping DNG export to " + mOutputPath);
            return;
        }
        SensorParams refSensor = mRefFrame != null ? mRefFrame.sensor : getSensorParams();
        exportBayer(merged, refSensor, mRefFrame, mSrFactor, mOutputPath);
    }

    /**
     * Read back a Bayer texture and write a merged DNG. Called from
     * {@link MergedDngExporter} (post-SR) and from {@link BurstFrequencyMerge}
     * (1× pre-SR debug dump, while the merge texture is still valid).
     */
    public static void exportBayer(Texture merged, SensorParams refSensor, RawFrame refFrame,
                                     int srFactor, String outputPath) {
        if (merged == null || outputPath == null || outputPath.isEmpty()) {
            Log.w(TAG, "exportBayer skipped: merged=" + merged + " path=" + outputPath);
            return;
        }

        int width = merged.getWidth();
        int height = merged.getHeight();
        Log.i(TAG, "Exporting merged Bayer DNG  " + width + "x" + height
                + "  srFactor=" + srFactor + "  ->  " + outputPath);

        short[] pixels = downloadAsUint16(merged, width, height);

        logCfaSanity(pixels, width, height, refSensor);
        logPixelStats(pixels, width, height);
        if (DUMP_RAW_U16_SIDECAR) {
            dumpRawU16(pixels, width, height, outputPath);
        }
        String make = "DNGProcessor";
        String model = (refSensor != null && refFrame != null)
                ? "Burst Merged (" + refFrame.fileName + ")" : "Burst Merged";

        try {
            DngWriter.writeMergedBayer(outputPath, pixels, width, height,
                    refSensor, srFactor, make, model, "DNGProcessor Burst");
            Log.i(TAG, "Wrote merged DNG: " + outputPath);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write merged DNG to " + outputPath, e);
        }
    }

    /**
     * Read back the R16F merged texture from the GPU and convert to 16-bit
     * unsigned samples.
     *
     * <p>Important: the raw pixels were uploaded into the GPU in top-down image
     * order (DNG row 0 → texel y=0), and the entire merge pipeline operates in
     * this convention. {@code glReadPixels(0, y, ...)} returns rows starting at
     * texel y=y, which corresponds to image row y semantically — so the buffer
     * layout we want is row-major top-down with no vertical flip. Flipping the
     * rows here would also misalign the Bayer CFA pattern by exactly one row
     * and produce a pink / fine-checkerboard artefact when re-decoded.
     *
     * <p>Format choice: we read as {@code GL_RED + GL_FLOAT} — the canonical
     * read combination for an {@code R16F} color-renderable buffer per
     * GLES 3.0 §3.7.2 Table 3.13. {@code GL_RGBA + GL_FLOAT} is <em>not</em>
     * guaranteed for R16F and only works when the implementation happens to
     * expose it as {@code IMPLEMENTATION_COLOR_READ_FORMAT/TYPE}; on devices
     * where it doesn't, glReadPixels raises {@code INVALID_OPERATION} (leaving
     * the buffer with previous-block content → rectangular dark patches) or
     * silently returns mis-strided samples that collapse every pixel pair
     * onto the same value, which the CFA decoder reads as "all-red" → pink +
     * fine-dotted checkerboard.
     */
    private static short[] downloadAsUint16(Texture tex, int width, int height) {
        short[] out = new short[width * height];
        final int blockH = Constants.BLOCK_HEIGHT;
        // 1 float per pixel for GL_RED + GL_FLOAT.
        ByteBuffer buf = ByteBuffer.allocateDirect(width * blockH * 4)
                .order(ByteOrder.nativeOrder());
        FloatBuffer fb = buf.asFloatBuffer();

        // Drain any pre-existing GL error so our post-read check only sees
        // errors caused by the readback itself.
        while (glGetError() != GL_NO_ERROR) { /* drain */ }

        tex.setFrameBuffer();

        // Diagnostic: GL state at start of readback. If the implementation's
        // preferred read format isn't (GL_RED, GL_FLOAT) at 2× we'll be told.
        int fbStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        int[] implFormat = new int[1];
        int[] implType   = new int[1];
        int[] packAlign  = new int[1];
        int[] packRowLen = new int[1];
        int[] packSkipPx = new int[1];
        int[] packSkipRw = new int[1];
        glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT, implFormat, 0);
        glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE,   implType,   0);
        glGetIntegerv(GL_PACK_ALIGNMENT,    packAlign,  0);
        glGetIntegerv(GL_PACK_ROW_LENGTH,   packRowLen, 0);
        glGetIntegerv(GL_PACK_SKIP_PIXELS,  packSkipPx, 0);
        glGetIntegerv(GL_PACK_SKIP_ROWS,    packSkipRw, 0);
        Log.i(TAG, String.format(
                "Readback start %dx%d  fbStatus=0x%x (complete=0x%x)  "
                        + "implReadFmt=0x%x implReadType=0x%x  "
                        + "pack[align=%d rowLen=%d skipPx=%d skipRw=%d]",
                width, height, fbStatus, GL_FRAMEBUFFER_COMPLETE,
                implFormat[0], implType[0],
                packAlign[0], packRowLen[0], packSkipPx[0], packSkipRw[0]));

        BlockDivider divider = new BlockDivider(height, blockH);
        int[] row = new int[2];
        boolean fellBackToRgba = false;
        int blockIdx = 0;
        while (divider.nextBlock(row)) {
            int y = row[0], bh = row[1];
            fb.position(0);
            GLES20.glReadPixels(0, y, width, bh, GL_RED, GL_FLOAT, fb);
            int err = glGetError();
            if (err != GL_NO_ERROR) {
                // Some GLES 3.0 drivers reject GL_RED + GL_FLOAT against R16F
                // and only expose GL_RGBA + GL_FLOAT — fall back transparently.
                if (!fellBackToRgba) {
                    Log.w(TAG, "glReadPixels(GL_RED, GL_FLOAT) failed (0x"
                            + Integer.toHexString(err) + "); retrying with GL_RGBA.");
                    fellBackToRgba = true;
                    // Re-allocate buffer to RGBA size for the rest of the read.
                    buf = ByteBuffer.allocateDirect(width * blockH * 4 * 4)
                            .order(ByteOrder.nativeOrder());
                    fb = buf.asFloatBuffer();
                }
                fb.position(0);
                GLES20.glReadPixels(0, y, width, bh, GL_RGBA, GL_FLOAT, fb);
                int err2 = glGetError();
                if (err2 != GL_NO_ERROR) {
                    Log.e(TAG, "glReadPixels(GL_RGBA, GL_FLOAT) also failed (0x"
                            + Integer.toHexString(err2)
                            + "); merged DNG block " + y + " will be zero.");
                    continue;
                }
            }
            fb.rewind();
            int stride = fellBackToRgba ? 4 : 1;

            // Diagnostic: log first 4 samples of first row + middle row + last
            // row of the FIRST block and a sparsely sampled subset thereafter.
            // If glReadPixels is misbehaving with this width, the samples
            // here will not look like a smooth Bayer signal.
            if (blockIdx == 0 || (blockIdx & 0x3F) == 0) {
                int sampleRow0 = 0;
                int sampleRowM = Math.min(bh / 2, bh - 1);
                int sampleRowL = bh - 1;
                Log.i(TAG, String.format(
                        "block#%d y=%d bh=%d  "
                                + "row0[%.4f %.4f %.4f %.4f]  "
                                + "rowMid[%.4f %.4f %.4f %.4f]  "
                                + "rowLast[%.4f %.4f %.4f %.4f]",
                        blockIdx, y, bh,
                        fb.get(sampleRow0 * width * stride + 0 * stride),
                        fb.get(sampleRow0 * width * stride + 1 * stride),
                        fb.get(sampleRow0 * width * stride + 2 * stride),
                        fb.get(sampleRow0 * width * stride + 3 * stride),
                        fb.get(sampleRowM * width * stride + 0 * stride),
                        fb.get(sampleRowM * width * stride + 1 * stride),
                        fb.get(sampleRowM * width * stride + 2 * stride),
                        fb.get(sampleRowM * width * stride + 3 * stride),
                        fb.get(sampleRowL * width * stride + 0 * stride),
                        fb.get(sampleRowL * width * stride + 1 * stride),
                        fb.get(sampleRowL * width * stride + 2 * stride),
                        fb.get(sampleRowL * width * stride + 3 * stride)));
            }
            blockIdx++;

            for (int yy = 0; yy < bh; yy++) {
                int dstRow = y + yy;
                if (dstRow >= height) break;
                int srcBase = yy * width * stride;
                int dstBase = dstRow * width;
                for (int x = 0; x < width; x++) {
                    float v = fb.get(srcBase + x * stride);
                    int s;
                    if (Float.isNaN(v) || v <= 0f) {
                        s = 0;
                    } else if (v >= 1f) {
                        s = 65535;
                    } else {
                        s = Math.round(v * 65535f);
                        if (s > 65535) s = 65535;
                    }
                    out[dstBase + x] = (short) (s & 0xFFFF);
                }
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return out;
    }

    /**
     * Log average intensity at the four CFA parities in a centred ROI. If the
     * downstream DNG renders pink + dotted, the four averages will be radically
     * different from what the CFA pattern claims (e.g. all four near-equal
     * after CFA demosaic-failure, or G/B near zero with R inflated). The check
     * is cheap (~256² samples) and makes future regressions obvious in logcat.
     */
    private static void logCfaSanity(short[] pixels, int width, int height,
                                     SensorParams sensor) {
        int roi = Math.min(256, Math.min(width, height) & ~1); // even side
        int x0 = (width  - roi) / 2 & ~1;
        int y0 = (height - roi) / 2 & ~1;
        long[] sum = new long[4];
        int[] cnt = new int[4];
        for (int y = 0; y < roi; y++) {
            int row = (y0 + y) * width;
            int py = y & 1;
            for (int x = 0; x < roi; x++) {
                int px = x & 1;
                int idx = py * 2 + px;          // 0,1,2,3 in CFA repeat block
                sum[idx] += pixels[row + x0 + x] & 0xFFFF;
                cnt[idx]++;
            }
        }
        float[] avg = new float[4];
        for (int i = 0; i < 4; i++) avg[i] = cnt[i] > 0 ? sum[i] / (float) cnt[i] : 0f;
        byte[] cfa = (sensor != null && sensor.cfaVal != null && sensor.cfaVal.length >= 4)
                ? sensor.cfaVal : new byte[] { 0, 1, 1, 2 };
        Log.i(TAG, String.format("CFA sanity (%dx%d ROI @ %d,%d) "
                        + "CFA=[%d,%d,%d,%d] avg=(%.0f, %.0f, %.0f, %.0f)",
                roi, roi, x0, y0, cfa[0], cfa[1], cfa[2], cfa[3],
                avg[0], avg[1], avg[2], avg[3]));
    }

    /**
     * Log per-row and global pixel statistics so we can see what the readback
     * actually produced — independent of any downstream DNG / viewer logic.
     * If the buffer is mangled by the readback path the symptoms tend to
     * show as:
     *   - identical adjacent rows (row stride > expected; some rows skipped),
     *   - per-row mean drifting in an unexpected periodic way,
     *   - large min/max swings between adjacent rows,
     *   - global mean far from {@code 0.5 * 65535} for a calibrated burst.
     */
    private static void logPixelStats(short[] pixels, int width, int height) {
        long sum = 0;
        int min = 0xFFFF, max = 0;
        for (int i = 0; i < pixels.length; i++) {
            int v = pixels[i] & 0xFFFF;
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double mean = sum / (double) pixels.length;

        // Per-row means (8 evenly spaced rows including first and last).
        StringBuilder rowMeans = new StringBuilder();
        int samples = Math.min(8, height);
        for (int i = 0; i < samples; i++) {
            int y = (int) ((long) i * (height - 1) / Math.max(1, samples - 1));
            long rs = 0;
            int rmin = 0xFFFF, rmax = 0;
            int base = y * width;
            for (int x = 0; x < width; x++) {
                int v = pixels[base + x] & 0xFFFF;
                rs += v;
                if (v < rmin) rmin = v;
                if (v > rmax) rmax = v;
            }
            double rmean = rs / (double) width;
            if (rowMeans.length() > 0) rowMeans.append(", ");
            rowMeans.append(String.format("y=%d:%.0f[%d..%d]", y, rmean, rmin, rmax));
        }

        // Compare adjacent rows in the middle of the image — a stride bug
        // tends to make row N and row N+1 (or row N and row N+K for some K)
        // exactly identical, which is impossible for real sensor data.
        int yMid = height / 2;
        int identical = 0;
        for (int x = 0; x < Math.min(width, 256); x++) {
            int a = pixels[yMid * width + x] & 0xFFFF;
            int b = pixels[(yMid + 1) * width + x] & 0xFFFF;
            if (a == b) identical++;
        }

        Log.i(TAG, String.format("Pixel stats %dx%d  min=%d max=%d mean=%.0f  "
                        + "rows[%s]  adjRowMatches@y=%d:%d/256",
                width, height, min, max, mean, rowMeans.toString(), yMid, identical));
    }

    /**
     * DEBUG: dump the raw little-endian uint16 buffer next to the DNG. Load
     * in numpy with:
     *   np.fromfile("foo.u16", np.uint16).reshape(H, W)
     * If this file shows the corruption but the DNG-decoded image doesn't,
     * the bug is in DngWriter. If both show it, the bug is in the readback.
     */
    private static void dumpRawU16(short[] pixels, int width, int height,
                                    String dngPath) {
        String rawPath = dngPath.endsWith(".dng")
                ? dngPath.substring(0, dngPath.length() - 4) + ".u16"
                : dngPath + ".u16";
        try (FileOutputStream fos = new FileOutputStream(rawPath);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 1 << 20)) {
            ByteBuffer row = ByteBuffer.allocate(width * 2).order(ByteOrder.LITTLE_ENDIAN);
            byte[] rowBytes = row.array();
            for (int y = 0; y < height; y++) {
                row.clear();
                int base = y * width;
                for (int x = 0; x < width; x++) {
                    row.putShort(pixels[base + x]);
                }
                bos.write(rowBytes, 0, width * 2);
            }
            Log.i(TAG, "Wrote raw uint16 side-car: " + rawPath
                    + "  (" + width + "x" + height + ", "
                    + ((long) width * height * 2) + " bytes)");
        } catch (IOException e) {
            Log.w(TAG, "Failed to write raw uint16 side-car to " + rawPath, e);
        }
    }

    @Override
    public int getShader() {
        // The pipeline runner calls useProgram(getShader()) before execute(); this
        // stage doesn't issue any draws, but we still need to return a valid shader
        // resource so program creation/linking succeeds. Re-use a shader that the
        // preceding SR-upsample stage has already compiled.
        return R.raw.burst_sr_upsample;
    }
}
