package amirz.dngprocessor.scheduler;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

import amirz.dngprocessor.Preferences;
import amirz.dngprocessor.R;
import amirz.dngprocessor.parser.DngParser;
import amirz.dngprocessor.parser.TIFFTag;
import amirz.dngprocessor.util.NotifHandler;
import amirz.dngprocessor.util.Path;
import amirz.dngprocessor.util.Utilities;

import java.io.File;

public class DngParseWorker extends Worker {
    private static final String TAG = "DngParseWorker";
    public static final String WORK_TAG = "dng_parse_work";
    private static final String KEY_URI = "uri";
    private static final String KEY_FROM_UI = "from_ui";
    private static final String PREF_PAUSED = "work_paused";
    // Delay to ensure DNG file is fully written before background processing
    private static final long INITIAL_DELAY_SECONDS = 5;

    /**
     * Enqueue work for UI-triggered processing (immediate, with toasts)
     */
    public static void enqueueWork(Context context, Uri uri) {
        enqueueWork(context, uri, true);
    }

    /**
     * Enqueue work for background processing (delayed, with notifications)
     */
    public static void enqueueWorkBackground(Context context, Uri uri) {
        enqueueWork(context, uri, false);
    }

    private static void enqueueWork(Context context, Uri uri, boolean fromUI) {
        Data inputData = new Data.Builder()
                .putString(KEY_URI, uri.toString())
                .putBoolean(KEY_FROM_UI, fromUI)
                .build();

        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(DngParseWorker.class)
                .setInputData(inputData)
                .addTag(WORK_TAG);

        // Only add delay for background processing (file might still be writing)
        if (!fromUI) {
            builder.setInitialDelay(INITIAL_DELAY_SECONDS, TimeUnit.SECONDS);
            Log.d(TAG, "Enqueued background work for URI: " + uri + " with " + INITIAL_DELAY_SECONDS + "s delay");
        } else {
            Log.d(TAG, "Enqueued UI work for URI: " + uri);
        }

        WorkManager.getInstance(context).enqueue(builder.build());
    }

    public DngParseWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        
        // Check if work is paused
        if (Utilities.prefs(context).getBoolean(PREF_PAUSED, false)) {
            Log.d(TAG, "Work is paused, retrying later");
            return Result.retry();
        }
        
        String uriString = getInputData().getString(KEY_URI);
        if (uriString == null) {
            Log.e(TAG, "No URI provided");
            return Result.failure();
        }

        boolean fromUI = getInputData().getBoolean(KEY_FROM_UI, false);
        Uri uri = Uri.parse(uriString);
        
        // Ensure notification channel exists for background processing
        NotifHandler.createChannel(context);
        
        String file = Path.getFileFromUri(context, uri);
        Log.d(TAG, "Processing " + file + " (fromUI=" + fromUI + ")");

        // Show progress notification for background processing
        if (!fromUI) {
            NotifHandler.startProgress(context, file);
        }

        try {
            Preferences pref = Preferences.global();
            // Only migrate once - migration is idempotent and checks version
            // This avoids repeated SharedPreferences I/O when processing multiple files
            pref.migratePreferences(context);
            // Load preferences once - applyAll reads all preferences from SharedPreferences
            // This is relatively fast but still involves I/O, so we do it once per job
            pref.applyAll(Utilities.prefs(context), context.getResources());
            // Store URI for reprocessing - use apply() for async write to avoid blocking
            Utilities.prefs(context)
                    .edit()
                    .putString(context.getString(R.string.pref_reprocess), uri.toString())
                    .apply();

            // Check if skip existing is enabled and output JPEG already exists
            if (pref.skipExisting.get()) {
                String outputPath = Path.processedPath(pref.savePath.get(), file);
                File outputFile = new File(outputPath);
                if (outputFile.exists()) {
                    Log.d(TAG, "Skipping " + file + " - output JPEG already exists: " + outputPath);
                    if (!fromUI) {
                        NotifHandler.stopProgress(context);
                    }
                    showMessage(context, fromUI, "Skipped " + file + " (already exists)");
                    return Result.success();
                }
            }

            long startTime = System.currentTimeMillis();
            new DngParser(context, uri, file).run();
            long endTime = System.currentTimeMillis();
            float seconds = (endTime - startTime) * 0.001f;
            Log.d(TAG, "Took " + seconds + "s to process");

            // Delete original JPEG if enabled
            if (pref.deleteOriginalJpeg.get()) {
                String jpegPath = Path.getOriginalJpegPath(pref.jpegLocation.get(), file);
                if (jpegPath != null) {
                    Log.d(TAG, "Deleting original JPEG: " + jpegPath);
                    File jpegFile = new File(jpegPath);
                    if (jpegFile.delete()) {
                        context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                                Uri.fromFile(jpegFile)));
                        Log.d(TAG, "Successfully deleted original JPEG: " + jpegPath);
                    } else {
                        Log.w(TAG, "Could not delete original JPEG: " + jpegPath);
                        showMessage(context, fromUI, "Could not delete original JPEG: " + Path.getFileFromUri(context, Uri.fromFile(jpegFile)));
                    }
                } else {
                    Log.d(TAG, "Original JPEG not found for: " + file);
                }
            }

            // Delete original DNG if enabled
            boolean deleteEnabled = pref.deleteOriginal.get();
            Log.d(TAG, "Delete original: " + deleteEnabled);
            
            if (deleteEnabled) {
                String path = Path.getPathFromUri(context, uri);
                Log.d(TAG, "Deleting " + path);
                File resolvedFile = new File(path);
                if (resolvedFile.delete()) {
                    context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                            Uri.fromFile(resolvedFile)));
                } else {
                    showMessage(context, fromUI, "Could not delete " + file);
                }
            }

            if (!fromUI) {
                NotifHandler.stopProgress(context);
            }
            showMessage(context, fromUI, String.format("Processed %s in %.1fs", file, seconds));
            return Result.success();
        } catch (TIFFTag.TIFFTagException e) {
            Log.e(TAG, "Missing metadata in " + file, e);
            if (!fromUI) {
                NotifHandler.stopProgress(context);
            }
            showMessage(context, fromUI, "Missing metadata in " + file + ": " + e.getMessage());
            return Result.failure();
        } catch (Exception e) {
            Log.e(TAG, "Could not load " + file, e);
            if (!fromUI) {
                NotifHandler.stopProgress(context);
            }
            showMessage(context, fromUI, "Could not load " + file);
            return Result.failure();
        }
    }

    private void showMessage(Context context, boolean useToast, String message) {
        if (useToast) {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
        } else {
            NotifHandler.showMessage(context, "DNG Processor", message);
        }
    }

    /**
     * Pause all work in the queue
     */
    public static void pauseWork(Context context) {
        Utilities.prefs(context).edit().putBoolean(PREF_PAUSED, true).apply();
        Log.d(TAG, "Paused all work");
    }

    /**
     * Resume all paused work
     */
    public static void resumeWork(Context context) {
        Utilities.prefs(context).edit().putBoolean(PREF_PAUSED, false).apply();
        Log.d(TAG, "Resumed all work");
    }

    /**
     * Cancel all work in the queue
     */
    public static void cancelAllWork(Context context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG);
        Log.d(TAG, "Cancelled all work");
    }
}

