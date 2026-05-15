package amirz.dngprocessor;

import android.app.Application;
import android.util.Log;

import androidx.work.Configuration;
import androidx.work.WorkManager;

import java.util.concurrent.Executors;

/**
 * Application class to configure WorkManager for processing.
 * 
 * IMPORTANT: Set to 1 worker (sequential processing) to avoid OutOfMemoryError.
 * 
 * Each DNG file processing can allocate 100MB+ of native memory (DirectByteBuffers
 * for textures). When processing multiple files in parallel, memory accumulates
 * faster than GC can free it, causing OutOfMemoryError.
 * 
 * Sequential processing ensures:
 * 1. Memory from one file is freed before starting the next
 * 2. No memory contention between concurrent jobs
 * 3. Stable processing time per file (~4s) instead of exponential slowdown
 * 
 * The previous slowdown (4s -> 20s per file) was likely due to GC pressure
 * from memory leaks, not just sequential processing. With memory leaks fixed,
 * sequential processing should be fast and stable.
 */
public class DngProcessorApplication extends Application {
    private static final String TAG = "DngProcessorApp";
    
    // Maximum number of concurrent processing jobs
    // Reduced to 1 to avoid OutOfMemoryError when processing multiple large files
    // Each DNG file can use 100MB+ of native memory (DirectByteBuffers)
    // Processing sequentially ensures memory is freed between files
    private static final int MAX_CONCURRENT_WORKERS = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        
        // Run preference migration FIRST, before any Activity or Fragment tries to read preferences
        // This prevents ClassCastException when Android's PreferenceFragment tries to initialize
        // UI elements that read preferences (e.g., ListPreference reading a boolean value)
        Preferences.global().migratePreferences(this);
        
        try {
            // Configure WorkManager to allow parallel processing
            // Using a fixed thread pool allows multiple DNG processing jobs to run concurrently
            Configuration config = new Configuration.Builder()
                    .setExecutor(Executors.newFixedThreadPool(MAX_CONCURRENT_WORKERS))
                    .build();
            
            WorkManager.initialize(this, config);
            Log.d(TAG, "WorkManager configured for parallel processing (max " + MAX_CONCURRENT_WORKERS + " workers)");
        } catch (IllegalStateException e) {
            // WorkManager already initialized (shouldn't happen with auto-init disabled, but handle gracefully)
            Log.w(TAG, "WorkManager already initialized: " + e.getMessage());
        }
    }
}

