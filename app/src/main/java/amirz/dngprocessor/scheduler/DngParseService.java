package amirz.dngprocessor.scheduler;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import java.io.File;

import amirz.dngprocessor.parser.TIFFTag;
import amirz.dngprocessor.util.NotifHandler;
import amirz.dngprocessor.util.Path;
import amirz.dngprocessor.Preferences;
import amirz.dngprocessor.R;
import amirz.dngprocessor.util.Utilities;
import amirz.dngprocessor.parser.DngParser;

import static amirz.dngprocessor.util.Utilities.ATLEAST_OREO;

import androidx.annotation.Nullable;

public class DngParseService extends IntentService {
    private static final String TAG = "DngParseService";

    public static void runForUri(Context context, Uri uri) {
        context = context.getApplicationContext();

        Intent intent = new Intent(context, DngParseService.class);
        intent.setData(uri);

        if (ATLEAST_OREO) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public DngParseService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        Uri uri = intent.getData();
        String file = Path.getFileFromUri(this, uri);
        Log.e(TAG, "onHandleIntent " + file);

        NotifHandler.create(this, file);
        try {
            Preferences pref = Preferences.global();
            pref.migratePreferences(this);
            pref.applyAll(Utilities.prefs(this), getResources());
            Utilities.prefs(this)
                    .edit()
                    .putString(getString(R.string.pref_reprocess), uri.toString())
                    .apply();

            // Check if skip existing is enabled and output JPEG already exists
            if (pref.skipExisting.get()) {
                String outputPath = Path.processedPath(pref.savePath.get(), file);
                File outputFile = new File(outputPath);
                if (outputFile.exists()) {
                    Log.d(TAG, "Skipping " + file + " - output JPEG already exists: " + outputPath);
                    postMsg("Skipped " + file + " (already exists)");
                    NotifHandler.done(this);
                    return;
                }
            }

            long startTime = System.currentTimeMillis();
            new DngParser(this, uri, file).run();
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
                        sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                                Uri.fromFile(jpegFile)));
                        Log.d(TAG, "Successfully deleted original JPEG: " + jpegPath);
                    } else {
                        Log.w(TAG, "Could not delete original JPEG: " + jpegPath);
                        postMsg("Could not delete original JPEG: " + Path.getFileFromUri(this, Uri.fromFile(jpegFile)));
                    }
                } else {
                    Log.d(TAG, "Original JPEG not found for: " + file);
                }
            }

            // Delete original DNG if enabled
            boolean deleteEnabled = pref.deleteOriginal.get();
            Log.d(TAG, "Delete original: " + deleteEnabled);
            
            if (deleteEnabled) {
                String path = Path.getPathFromUri(this, uri);
                Log.e(TAG, "Deleting " + path);
                File resolvedFile = new File(path);
                if (resolvedFile.delete()) {
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                            Uri.fromFile(resolvedFile)));
                } else {
                    postMsg("Could not delete " + file);
                }
            }

            postMsg(String.format("Processed %s in %.1fs", file, seconds));
        } catch (TIFFTag.TIFFTagException e) {
            Log.e(TAG, "Missing metadata in " + file, e);
            postMsg("Missing metadata in " + file + ": " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Could not load " + file, e);
            postMsg("Could not load " + file);
        }
        NotifHandler.done(this);
    }

    private void postMsg(String msg) {
        new Handler(getMainLooper()).post(() ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}
