package amirz.dngprocessor.parser;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import amirz.dngprocessor.Preferences;
import amirz.dngprocessor.device.DeviceMap;
import amirz.dngprocessor.gl.GLContext;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.params.SensorParams;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.burst.BurstLinearStagePipeline;
import amirz.dngprocessor.pipeline.burst.BurstSrUpsample;
import amirz.dngprocessor.pipeline.burst.BurstStagePipeline;
import amirz.dngprocessor.ui.AdaptiveSaturationCurveActivity;
import amirz.dngprocessor.ui.ToneCurveActivity;
import amirz.dngprocessor.util.NotifHandler;
import amirz.dngprocessor.util.Path;
import amirz.dngprocessor.util.ShaderLoader;
import amirz.dngprocessor.util.Utilities;

import static amirz.dngprocessor.util.Constants.DIAGONAL;

/**
 * Parses N DNG/RAW files as a single burst, performs multi-frame HDR merge,
 * and saves the result as a JPEG using the same naming convention as {@link DngParser}.
 *
 * <p>The first file in the list is treated as the reference frame whose sensor parameters
 * and output crop define the output image.
 */
public class BurstParser {
    private static final String TAG = "BurstParser";

    private final Context mContext;
    private final List<Uri> mUris;
    private final List<String> mFiles;

    public BurstParser(Context context, List<Uri> uris, List<String> files) {
        mContext = context;
        mUris = uris;
        mFiles = files;
    }

    public void run() {
        Log.i(TAG, "Starting burst processing of " + mUris.size() + " frames");
        NotifHandler.progress(mContext, mUris.size() + 2, 0);

        Preferences pref = Preferences.global();

        // --- Parse each frame ---
        List<RawFrame> frames = new ArrayList<>(mUris.size());
        for (int i = 0; i < mUris.size(); i++) {
            Uri uri = mUris.get(i);
            String file = mFiles.get(i);
            Log.d(TAG, "Parsing frame " + i + ": " + file);
            NotifHandler.progress(mContext, mUris.size() + 2, i);
            RawFrame frame = new DngParser(mContext, uri, file).parseSingle();
            frames.add(frame);
        }

        // --- Validate compatibility ---
        RawFrame firstFrame = frames.get(0);
        for (int i = 1; i < frames.size(); i++) {
            RawFrame f = frames.get(i);
            if (f.sensor.isLinearRaw != firstFrame.sensor.isLinearRaw) {
                throw new IllegalArgumentException(
                        "Burst frames must all be the same type (Bayer/linear raw): frame " + i
                        + " differs from frame 0");
            }
            if (f.sensor.inputWidth != firstFrame.sensor.inputWidth
                    || f.sensor.inputHeight != firstFrame.sensor.inputHeight) {
                Log.w(TAG, "Frame " + i + " has different dimensions ("
                        + f.sensor.inputWidth + "x" + f.sensor.inputHeight + " vs "
                        + firstFrame.sensor.inputWidth + "x" + firstFrame.sensor.inputHeight
                        + ") – will be aligned by SAD, but best results require matching sensors");
            }
        }

        // --- Bracket detection: per-frame relative EVs, darkest-as-reference -----
        // Each frame's "absolute" EV is read from EXIF ExposureBiasValue if present,
        // otherwise estimated from ISO·shutter (hdr-plus-swift fallback).
        // Frames are then re-ordered so that the darkest exposure sits at index 0
        // (the rest of the pipeline always treats index 0 as the alignment / merge
        // reference). For uniform-exposure bursts this is a no-op.
        float[] absoluteEv = computeAbsoluteEv(frames);
        int darkIdx = argMinFinite(absoluteEv);
        if (darkIdx > 0) {
            Collections.swap(frames, 0, darkIdx);
            float tmp = absoluteEv[0]; absoluteEv[0] = absoluteEv[darkIdx]; absoluteEv[darkIdx] = tmp;
            Log.i(TAG, "Reordered burst: ref=" + frames.get(0).fileName
                    + " (darkest, EV=" + String.format("%+.2f", absoluteEv[0]) + ")");
        }
        float refEv = absoluteEv[0];
        float minEv = refEv, maxEv = refEv;
        for (int i = 0; i < frames.size(); i++) {
            float rel = absoluteEv[i] - refEv;
            // Guard: defensive clamp — alignment scaling > 16× would saturate fp16.
            rel = Math.max(0f, Math.min(rel, 4f));
            frames.get(i).relativeEv = rel;
            if (absoluteEv[i] < minEv) minEv = absoluteEv[i];
            if (absoluteEv[i] > maxEv) maxEv = absoluteEv[i];
        }
        float evSpan = maxEv - minEv;
        boolean bracketed = evSpan >= ProcessParams.BURST_BRACKET_EV_THRESHOLD;

        StringBuilder evDump = new StringBuilder();
        for (int i = 0; i < frames.size(); i++) {
            evDump.append(String.format("  [%d] %s  abs=%+.2fEV rel=%+.2fEV  expT=%.4fs iso=%d%n",
                    i, frames.get(i).fileName, absoluteEv[i], frames.get(i).relativeEv,
                    frames.get(i).exposureTime, frames.get(i).isoSpeed));
        }
        Log.i(TAG, "Burst exposure summary (span=" + String.format("%.2f", evSpan)
                + "EV, bracketed=" + bracketed + "):\n" + evDump);

        RawFrame ref = frames.get(0); // Darkest frame after reorder.

        // --- Build ProcessParams from the reference frame's prefs ---
        ProcessParams process = buildProcessParams(pref, ref.sensor);
        process.burstEnabled = true;
        // "auto" picks HDR_BRACKET vs SR_EQUAL from the detected EV span. The mode
        // is intentionally not yet exposed via a user preference — flip to
        // "sr_equal" or "hdr_bracket" here to force a specific path for testing.
        process.burstMode = "auto";
        process.burstBracketed = bracketed;
        float[] relativeEvs = new float[frames.size()];
        for (int i = 0; i < frames.size(); i++) relativeEvs[i] = frames.get(i).relativeEv;
        process.burstRelativeEv = relativeEvs;
        // Post-merge gain target: the median brighter-frame EV, clipped so we don't
        // blow up highlights that the merge already protected. 0EV → no boost.
        process.burstPostExposureBoostEv = bracketed
                ? Math.min(2.0f, computeMedianBoostEv(relativeEvs)) : 0f;

        // --- Create output bitmap (2× in SR mode, 1× in plain-merge mode) ---
        int outW = BurstSrUpsample.ENABLE_SR ? 2 * ref.cropWidth  : ref.cropWidth;
        int outH = BurstSrUpsample.ENABLE_SR ? 2 * ref.cropHeight : ref.cropHeight;
        Bitmap argbOutput = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);

        // --- Output path ---
        String outputBaseName = mFiles.get(0);
        int lastDot = outputBaseName.lastIndexOf('.');
        if (lastDot > 0) outputBaseName = outputBaseName.substring(0, lastDot);
        if (pref.suffix.get() && outputBaseName.startsWith("IMG"))
            outputBaseName = "MAT" + outputBaseName.substring(3);
        process.outputBaseName = outputBaseName;
        process.context = mContext;

        String savePath = Path.processedPath(pref.savePath.get(), mFiles.get(0));
        // Make the save path unique by inserting "_burst" before the extension
        savePath = savePath.replace(".jpg", "_burst.jpg");

        // The merged Bayer DNG is written next to the JPEG output by the pipeline
        // itself, just after the merge stage. We pre-compute the path here so the
        // pipeline doesn't have to know anything about file naming.
        // (Linear-raw bursts don't yet have a merged-DNG exporter; only Bayer bursts.)
        String mergedDngPath = ref.sensor.isLinearRaw
                ? null
                : savePath.replace("_burst.jpg", "_merged.dng");

        // --- Run pipeline ---
        ShaderLoader loader = ShaderLoader.getInstance(mContext);
        NotifHandler.progress(mContext, mUris.size() + 2, mUris.size());

        try (GLContext glContext = new GLContext()) {
            if (ref.sensor.isLinearRaw) {
                Log.d(TAG, "Using BurstLinearStagePipeline");
                try (BurstLinearStagePipeline pipeline = new BurstLinearStagePipeline(
                        glContext, ref.sensor, process, frames, argbOutput, loader)) {
                    pipeline.execute(buildProgressReporter());
                }
            } else {
                Log.d(TAG, "Using BurstStagePipeline (merged DNG -> " + mergedDngPath + ")");
                try (BurstStagePipeline pipeline = new BurstStagePipeline(
                        glContext, ref.sensor, process, frames, argbOutput, loader,
                        mergedDngPath)) {
                    pipeline.execute(buildProgressReporter());
                }
            }
        }

        if (mergedDngPath != null) {
            mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    Uri.fromFile(new File(mergedDngPath))));
            if (BurstStagePipeline.EXPORT_DEBUG_DNGS) {
                String mergedDng1xPath = mergedDngPath.replace("_merged.dng", "_merged_1x.dng");
                mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        Uri.fromFile(new File(mergedDng1xPath))));
                Log.i(TAG, "Merged DNG side-cars: " + mergedDngPath + " , " + mergedDng1xPath);
            }
        }

        // --- Save JPEG ---
        NotifHandler.progress(mContext, mUris.size() + 2, mUris.size() + 1);
        int jpegQuality = Math.max(0, Math.min(100, pref.jpegQuality.get()));
        try (FileOutputStream out = new FileOutputStream(savePath)) {
            argbOutput.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out);
            Log.i(TAG, "Saved burst output: " + savePath);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save burst JPEG: " + savePath, e);
        }
        argbOutput.recycle();

        mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                Uri.fromFile(new File(savePath))));

        NotifHandler.progress(mContext, mUris.size() + 2, mUris.size() + 2);
        Log.i(TAG, "Burst processing complete");
    }

    // ---------------------------------------------------------------------------

    private StagePipeline.OnProgressReporter buildProgressReporter() {
        return (completed, total, tag) ->
                Log.d(TAG, "Pipeline step " + tag + ": " + completed + "/" + total);
    }

    /**
     * Compute an "absolute" EV per frame so the burst's spread is comparable
     * across frames regardless of which EXIF field actually changed.
     *
     * <p>Priority of evidence:
     * <ol>
     *   <li>EXIF {@code ExposureBiasValue} when present — this is the most reliable
     *       hint that the camera intentionally bracketed.</li>
     *   <li>{@code log2(exposureTime · iso)} otherwise — same fallback as
     *       hdr-plus-swift {@code estimate_exposure_bias} when bias is missing.</li>
     *   <li>0 if everything's missing.</li>
     * </ol>
     *
     * The returned values are NOT yet zeroed at the darkest frame — the caller
     * subtracts {@code min} once it has picked the reference index.
     */
    private static float[] computeAbsoluteEv(List<RawFrame> frames) {
        float[] out = new float[frames.size()];
        boolean anyBias = false;
        for (RawFrame f : frames) {
            if (!Float.isNaN(f.exposureBiasEv)) { anyBias = true; break; }
        }
        for (int i = 0; i < frames.size(); i++) {
            RawFrame f = frames.get(i);
            if (anyBias && !Float.isNaN(f.exposureBiasEv)) {
                out[i] = f.exposureBiasEv;
            } else if (f.exposureTime > 0f && f.isoSpeed > 0) {
                out[i] = (float) (Math.log(f.exposureTime * f.isoSpeed) / Math.log(2.0));
            } else if (f.exposureTime > 0f) {
                out[i] = (float) (Math.log(f.exposureTime) / Math.log(2.0));
            } else {
                out[i] = 0f;
            }
        }
        return out;
    }

    private static int argMinFinite(float[] arr) {
        int idx = 0;
        float best = Float.POSITIVE_INFINITY;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] < best) { best = arr[i]; idx = i; }
        }
        return idx;
    }

    /**
     * Median of the per-frame relative EVs, used as the "post-merge boost" target.
     * Since the reference is at relativeEv=0 and other frames are positive, the
     * median sits roughly halfway between the darkest and brightest — close to a
     * standard "middle exposure" look.
     */
    private static float computeMedianBoostEv(float[] relativeEv) {
        if (relativeEv == null || relativeEv.length == 0) return 0f;
        float[] copy = Arrays.copyOf(relativeEv, relativeEv.length);
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    /**
     * Build ProcessParams from the current user preferences, mirroring the relevant
     * portion of {@link DngParser#run()}.  The sensor is needed for adaptive
     * saturation / histogram matching.
     */
    private ProcessParams buildProcessParams(Preferences pref, SensorParams sensor) {
        ProcessParams process = ProcessParams.getPreset(Preferences.postProcess());

        process.saturationMap = new float[]{
                pref.saturationRed.get(), pref.saturationYellow.get(),
                pref.saturationGreen.get(), pref.saturationCyan.get(),
                pref.saturationBlue.get(), pref.saturationIndigo.get(),
                pref.saturationViolet.get(), pref.saturationMagenta.get()
        };
        process.satLimit          = pref.saturationLimit.get();
        process.exposeFuse        = false;                          // burst replaces exposure fusion
        process.lce               = pref.lce.get();
        process.lceMethod         = pref.lceMethod.get();
        process.lceRadiusWeak     = pref.lceRadiusWeak.get();
        process.lceRadiusMedium   = pref.lceRadiusMedium.get();
        process.lceRadiusStrong   = pref.lceRadiusStrong.get();
        process.lceRadiusXfine    = pref.lceRadiusXfine.get();
        process.lceRadiusFine     = pref.lceRadiusFine.get();
        process.lceRadiusXstrong  = pref.lceRadiusXstrong.get();
        process.lceStrengthWeak   = pref.lceStrengthWeak.get();
        process.lceStrengthMedium = pref.lceStrengthMedium.get();
        process.lceStrengthStrong = pref.lceStrengthStrong.get();
        process.lceStrengthXfine  = pref.lceStrengthXfine.get();
        process.lceStrengthFine   = pref.lceStrengthFine.get();
        process.lceStrengthXstrong = pref.lceStrengthXstrong.get();
        process.lceLimitWeak      = pref.lceLimitWeak.get();
        process.lceLimitMedium    = pref.lceLimitMedium.get();
        process.lceLimitStrong    = pref.lceLimitStrong.get();
        process.lceLimitXfine     = pref.lceLimitXfine.get();
        process.lceLimitFine      = pref.lceLimitFine.get();
        process.lceLimitXstrong   = pref.lceLimitXstrong.get();
        process.ahe               = pref.ahe.get();
        process.edgeAwareHistEq   = pref.edgeAwareHistEq.get();
        // Multi-frame averaging already reduces noise by √N per merged frame;
        // on top of that, Decompose uses hardcoded sigma/radius values that are
        // calibrated for 1× images. At 2× resolution they are twice as aggressive
        // in physical terms, destroying the fine detail that SR is supposed to
        // reveal. Keep noiseReduce off for burst; let the user control it only via
        // forceNoiseReduction if they explicitly want it.
        process.noiseReduce       = false;
        process.useReferencePreview = pref.referencePreview.get();
        process.histMatchStrength  = pref.histMatchStrength.get();
        process.demosaicingMethod  = pref.demosaicingMethod.get();

        String compressionStr = pref.baselineExposureCompression.get();
        try {
            process.baselineExposureCompression = Integer.parseInt(compressionStr);
        } catch (NumberFormatException e) {
            process.baselineExposureCompression = 0;
        }
        String hdrStr = pref.hdrCompressionMethod.get();
        try {
            process.hdrCompressionMethod = Integer.parseInt(hdrStr);
        } catch (NumberFormatException e) {
            process.hdrCompressionMethod = 0;
        }

        process.sharpenFactor += pref.sharpening.get() / 200f;
        process.denoiseFactor += pref.noiseReduction.get().intValue();
        process.forceNoiseReduction = pref.forceNoiseReduction.get();
        process.waveletNoiseReduction = pref.waveletNoiseReduction.get();
        process.matGreenToYellowShift = pref.matGreenToYellowShift.get();
        process.matYellowToWarmShift  = pref.matYellowToWarmShift.get();
        process.localLaplacianEnabled = pref.localLaplacianEnabled.get();
        process.localLaplacianShadows = pref.localLaplacianShadows.get();
        process.localLaplacianHighlights = pref.localLaplacianHighlights.get();
        process.localLaplacianClarity = pref.localLaplacianClarity.get();
        process.localLaplacianSigma   = pref.localLaplacianSigma.get();
        process.toneEqualizerEnabled  = pref.toneEqualizerEnabled.get();
        process.toneEqBlacks          = pref.toneEqBlacks.get();
        process.toneEqDeepShadows     = pref.toneEqDeepShadows.get();
        process.toneEqShadows         = pref.toneEqShadows.get();
        process.toneEqLightShadows    = pref.toneEqLightShadows.get();
        process.toneEqMidtones        = pref.toneEqMidtones.get();
        process.toneEqDarkHighlights  = pref.toneEqDarkHighlights.get();
        process.toneEqHighlights      = pref.toneEqHighlights.get();
        process.toneEqWhites          = pref.toneEqWhites.get();
        process.toneEqSpeculars       = pref.toneEqSpeculars.get();
        process.toneEqSmoothing       = pref.toneEqSmoothing.get();
        process.toneEqFeathering      = pref.toneEqFeathering.get();

        process.colorTransform = new float[]{
                pref.colorTransformRR.get(), pref.colorTransformRG.get(), pref.colorTransformRB.get(),
                pref.colorTransformGR.get(), pref.colorTransformGG.get(), pref.colorTransformGB.get(),
                pref.colorTransformBR.get(), pref.colorTransformBG.get(), pref.colorTransformBB.get()
        };
        process.toneExposure  = pref.toneExposure.get();
        process.toneHighlights= pref.toneHighlights.get();
        process.toneShadows   = pref.toneShadows.get();
        process.toneWhites    = pref.toneWhites.get();
        process.toneContrast  = pref.toneContrast.get();
        process.toneBlacks    = pref.toneBlacks.get();
        process.toneTexture   = pref.toneTexture.get();
        process.toneClarity   = pref.toneClarity.get();
        process.toneDehaze    = pref.toneDehaze.get();
        process.toneVibrance  = pref.toneVibrance.get();
        process.toneSaturation= pref.toneSaturation.get();

        String lutPath = pref.externalLutPath.get();
        process.externalLutPath = (lutPath != null && !lutPath.isEmpty()) ? lutPath : null;

        process.userToneCurve    = ToneCurveActivity.getSavedCurvePoints(Utilities.prefs(mContext));
        process.toneCurveEnabled = ToneCurveActivity.isUserCurveEnabled(Utilities.prefs(mContext));
        process.histFactor       = pref.histFactor.get();

        if (pref.adaptiveSaturationCurveEnabled.get()) {
            float strength = AdaptiveSaturationCurveActivity.lookupStrength(
                    Utilities.prefs(mContext), sensor.lightValue);
            process.adaptiveSaturation = new float[]{strength, pref.adaptiveSaturationPower.get()};
        } else {
            process.adaptiveSaturation[1] = pref.adaptiveSaturationPower.get();
        }

        if (sensor.calibrationTransform1 == null || sensor.calibrationTransform2 == null) {
            sensor.calibrationTransform1 = DIAGONAL;
            sensor.calibrationTransform2 = DIAGONAL;
        }

        return process;
    }
}
