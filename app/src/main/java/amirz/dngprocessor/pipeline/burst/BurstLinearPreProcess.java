package amirz.dngprocessor.pipeline.burst;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.parser.RawFrame;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.convert.LinearRawPreProcess;

/**
 * Burst pre-processing stage for linear-raw (already demosaiced) frames.
 * Runs {@link LinearRawPreProcess} for each frame and performs image alignment.
 */
public class BurstLinearPreProcess extends Stage {
    private static final String TAG = "BurstLinearPreProcess";

    private final List<RawFrame> mFrames;

    /** Normalised Float16 RGBA textures, one per frame. */
    private final List<Texture> mRgbTextures = new ArrayList<>();

    /** Gain-map textures, one per frame (may be a 1x1 identity if none). */
    private final List<Texture> mGainMapTextures = new ArrayList<>();

    /** Per-tile shift grid + confidences (see {@link BayerAlignment.TileShiftResult}). */
    private BayerAlignment.TileShiftResult mTileShifts;

    public BurstLinearPreProcess(List<RawFrame> frames) {
        mFrames = frames;
    }

    public List<Texture> getRgbTextures() { return mRgbTextures; }
    public List<Texture> getGainMapTextures() { return mGainMapTextures; }
    public BayerAlignment.TileShiftResult getTileShifts() { return mTileShifts; }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();
        ProcessParams process = getProcessParams();
        RawFrame refFrame = mFrames.get(0);

        for (int k = 0; k < mFrames.size(); k++) {
            RawFrame frame = mFrames.get(k);
            LinearRawPreProcess lpp = new LinearRawPreProcess(frame.rawBytes);
            lpp.init(converter, frame.sensor, process);
            lpp.execute(new StagePipeline.StageMap(new ArrayList<>()));
            Texture rgbTex = lpp.getRgbTex();
            Texture gainTex = lpp.getGainMapTex();
            if (rgbTex == null) {
                throw new IllegalStateException("BurstLinearPreProcess: null RGB texture for frame " + k);
            }
            mRgbTextures.add(rgbTex);
            mGainMapTextures.add(gainTex);
            Log.d(TAG, "Preprocessed linear frame " + k + ": " + rgbTex.getWidth() + "x" + rgbTex.getHeight());
        }

        // Per-tile alignment on green channel of linear-raw frames.
        // Bracket frames are equalised to ref EV before block matching.
        int fullW = refFrame.sensor.inputWidth;
        int fullH = refFrame.sensor.inputHeight;
        float[] alignScales = BurstPreProcess.buildFrameScales(mFrames);
        mTileShifts = BayerAlignment.computeTileShifts(
                converter, mRgbTextures, fullW, fullH, true /* isLinearRaw */, alignScales);

        int tiles = mTileShifts.tilesPerFrame();
        int nx = mTileShifts.nTilesX;
        int ny = mTileShifts.nTilesY;
        int centerTile = (ny / 2) * nx + (nx / 2);
        Log.d(TAG, "Linear burst tile alignment done (" + nx + "×" + ny
                + " grid). Centre-tile shifts (full-res px):");
        for (int k = 0; k < mFrames.size(); k++) {
            int base = k * tiles * 2 + centerTile * 2;
            int confBase = k * tiles + centerTile;
            Log.d(TAG, String.format("  frame %d: dx=%.3f dy=%.3f conf=%.3f",
                    k, mTileShifts.shifts[base], mTileShifts.shifts[base + 1],
                    mTileShifts.confidences[confBase]));
        }
    }

    @Override
    public int getShader() {
        return R.raw.stage1_linear_preprocess_fs;
    }

    @Override
    public void close() {
        for (Texture t : mRgbTextures) if (t != null) t.close();
        for (Texture t : mGainMapTextures) if (t != null) t.close();
        mRgbTextures.clear();
        mGainMapTextures.clear();
    }
}
