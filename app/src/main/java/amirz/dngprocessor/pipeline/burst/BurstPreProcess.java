package amirz.dngprocessor.pipeline.burst;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.parser.RawFrame;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.PreProcess;

import static android.opengl.GLES20.GL_LINEAR;

/**
 * Burst pre-processing stage: runs {@link PreProcess} for each raw frame individually,
 * then performs image alignment and stores the results for {@link BurstFrequencyMerge}.
 */
public class BurstPreProcess extends Stage {
    private static final String TAG = "BurstPreProcess";

    private final List<RawFrame> mFrames;

    /** Normalised Float16 sensor textures, one per frame, in input order. */
    private final List<Texture> mSensorTextures = new ArrayList<>();

    /** Per-tile shift grid + confidences (see {@link BayerAlignment.TileShiftResult}). */
    private BayerAlignment.TileShiftResult mTileShifts;

    public BurstPreProcess(List<RawFrame> frames) {
        mFrames = frames;
    }

    public List<Texture> getSensorTextures() { return mSensorTextures; }

    /**
     * Per-tile shift grid + confidences in full-resolution pixels.
     * @see BayerAlignment#computeTileShifts
     */
    public BayerAlignment.TileShiftResult getTileShifts() { return mTileShifts; }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();
        ProcessParams process = getProcessParams();
        RawFrame refFrame = mFrames.get(0);

        // Pre-process each frame
        for (int k = 0; k < mFrames.size(); k++) {
            RawFrame frame = mFrames.get(k);
            PreProcess pp = new PreProcess(frame.rawBytes);
            pp.init(converter, frame.sensor, process);
            pp.execute(new StagePipeline.StageMap(new ArrayList<>()));
            Texture tex = pp.getSensorTex();
            if (tex == null) {
                throw new IllegalStateException("BurstPreProcess: PreProcess produced null texture for frame " + k);
            }
            mSensorTextures.add(tex);
            Log.d(TAG, "Preprocessed frame " + k + ": " + tex.getWidth() + "x" + tex.getHeight());

            // Immediately release per-frame auxiliary textures that are no longer needed
            // after normalisation. Leaving them open leaks GPU memory and can starve
            // the TexturePool that later allocates the accumulation ping-pong buffers.
            Texture gainMap = pp.getGainMapTex();
            if (gainMap != null) gainMap.close();
            pp.close(); // closes mHistMatchLutTex
        }

        // Compute per-tile alignment: green channel extraction + CPU-side SAD grid.
        // For bracketed bursts we pass per-frame exposure-equalisation scales so the
        // SAD compares pixels at the same EV (hdr-plus-swift `prepare_texture`).
        int fullW = refFrame.sensor.inputWidth;
        int fullH = refFrame.sensor.inputHeight;
        float[] alignScales = buildFrameScales(mFrames);
        mTileShifts = BayerAlignment.computeTileShifts(
                converter, mSensorTextures, fullW, fullH, false /* isBayer */, alignScales);

        int tiles = mTileShifts.tilesPerFrame();
        int nx = mTileShifts.nTilesX;
        int ny = mTileShifts.nTilesY;
        int centerTile = (ny / 2) * nx + (nx / 2);
        Log.d(TAG, "Burst tile alignment done (" + nx + "×" + ny
                + " grid). Per-frame center-tile shifts (full-res px):");
        for (int k = 0; k < mFrames.size(); k++) {
            int base = k * tiles * 2 + centerTile * 2;
            int confBase = k * tiles + centerTile;
            Log.d(TAG, String.format("  frame %d: center-tile dx=%.3f dy=%.3f conf=%.3f",
                    k, mTileShifts.shifts[base], mTileShifts.shifts[base + 1],
                    mTileShifts.confidences[confBase]));
        }
    }

    /**
     * Per-frame exposure-equalisation scales for alignment / merge.
     * Returns {@code 1 / 2^relativeEv} per frame (= 1.0 for the reference).
     * For uniform-exposure bursts this is all-ones (no behavioural change).
     */
    static float[] buildFrameScales(List<RawFrame> frames) {
        float[] scales = new float[frames.size()];
        for (int k = 0; k < frames.size(); k++) {
            float ev = frames.get(k).relativeEv;
            scales[k] = (float) Math.pow(2.0, -ev);
        }
        return scales;
    }

    @Override
    public int getShader() {
        // The PreProcess shader – used as the initial program for this stage.
        // The actual multi-frame loop inside execute() re-issues useProgram() per frame.
        return R.raw.stage1_1_fs;
    }

    @Override
    public void close() {
        for (Texture tex : mSensorTextures) {
            if (tex != null) tex.close();
        }
        mSensorTextures.clear();
    }
}
