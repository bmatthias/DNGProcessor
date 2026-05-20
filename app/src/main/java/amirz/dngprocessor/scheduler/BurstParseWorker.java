package amirz.dngprocessor.scheduler;

import android.content.Context;
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

import java.util.ArrayList;
import java.util.List;

import amirz.dngprocessor.Preferences;
import amirz.dngprocessor.parser.BurstParser;
import amirz.dngprocessor.util.NotifHandler;
import amirz.dngprocessor.util.Path;
import amirz.dngprocessor.util.Utilities;

public class BurstParseWorker extends Worker {
    private static final String TAG = "BurstParseWorker";
    public static final String WORK_TAG = "burst_parse_work";
    private static final String KEY_URIS = "uris";

    /**
     * Enqueue a burst job for a list of raw-image URIs.
     * Requires at least 2 URIs.
     */
    public static void enqueueWork(Context context, List<Uri> uris) {
        if (uris == null || uris.size() < 2) {
            Log.w(TAG, "enqueueWork: need at least 2 URIs, got " + (uris == null ? 0 : uris.size()));
            return;
        }
        String[] uriStrings = new String[uris.size()];
        for (int i = 0; i < uris.size(); i++) uriStrings[i] = uris.get(i).toString();

        Data inputData = new Data.Builder()
                .putStringArray(KEY_URIS, uriStrings)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(BurstParseWorker.class)
                .setInputData(inputData)
                .addTag(WORK_TAG)
                .build();

        WorkManager.getInstance(context).enqueue(request);
        Log.d(TAG, "Enqueued burst work for " + uris.size() + " frames");
    }

    public BurstParseWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();

        String[] uriStrings = getInputData().getStringArray(KEY_URIS);
        if (uriStrings == null || uriStrings.length < 2) {
            Log.e(TAG, "doWork: missing or insufficient URIs");
            return Result.failure();
        }

        List<Uri> uris = new ArrayList<>(uriStrings.length);
        for (String s : uriStrings) uris.add(Uri.parse(s));

        NotifHandler.createChannel(context);
        NotifHandler.startProgress(context, uris.size() + " burst frames");

        try {
            Preferences pref = Preferences.global();
            pref.migratePreferences(context);
            pref.applyAll(Utilities.prefs(context), context.getResources());

            long startTime = System.currentTimeMillis();

            List<String> fileNames = new ArrayList<>(uris.size());
            for (Uri uri : uris) fileNames.add(Path.getFileFromUri(context, uri));

            new BurstParser(context, uris, fileNames).run();

            float seconds = (System.currentTimeMillis() - startTime) * 0.001f;
            Log.d(TAG, "Burst processed in " + seconds + "s");
            NotifHandler.stopProgress(context);
            showToast(context, String.format("Burst processed %d frames in %.1fs", uris.size(), seconds));
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Burst processing failed", e);
            NotifHandler.stopProgress(context);
            showToast(context, "Burst processing failed: " + e.getMessage());
            return Result.failure();
        }
    }

    private void showToast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}
