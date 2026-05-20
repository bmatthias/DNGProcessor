package amirz.dngprocessor;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import amirz.dngprocessor.scheduler.DngParseWorker;
import amirz.dngprocessor.util.Path;
import amirz.dngprocessor.util.Utilities;
import amirz.library.settings.GlobalPreferences;
import amirz.library.settings.SliderPreference;

public class Preferences extends GlobalPreferences {
    private static final Preferences sInstance = new Preferences();
    
    // Migration version - increment when adding new migrations
    private static final String PREF_MIGRATION_VERSION = "pref_migration_version";
    private static final int CURRENT_MIGRATION_VERSION = 20;

    public static Preferences global() {
        return sInstance;
    }
    
    /**
     * Migrates preferences for existing users when installing a new version.
     * This runs once per migration version.
     * Call this before applyAll().
     * 
     * Optimized: Fast path check to avoid unnecessary SharedPreferences reads
     * when processing multiple files sequentially.
     */
    public void migratePreferences(android.content.Context context) {
        android.content.SharedPreferences prefs = Utilities.prefs(context);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        boolean needsCommit = false;
        
        // Fix any non-string baseline_exposure_compression values (e.g., from Android backup/restore)
        // This prevents ClassCastException when PreferenceFragment or StringRef tries to read it
        // Always convert to "0" (None) - the current default - regardless of the old boolean value
        String compressionKey = context.getString(R.string.pref_baseline_exposure_compression);
        if (prefs.contains(compressionKey)) {
            try {
                // Try to read as boolean - if this succeeds, it's the wrong type
                prefs.getBoolean(compressionKey, false);
                // If we get here, it's a boolean - convert to current default "0"
                android.util.Log.w("Preferences", "Found boolean baseline_exposure_compression, converting to String \"0\"");
                editor.remove(compressionKey);
                editor.putString(compressionKey, "0");
                needsCommit = true;
            } catch (ClassCastException e) {
                // Good - it's already a string (or other non-boolean type)
                // No conversion needed
            }
        }
        
        // Fast path: check version first without reading all preferences
        int lastMigrationVersion = prefs.getInt(PREF_MIGRATION_VERSION, 0);
        
        // If already migrated, commit any type fixes and return
        if (lastMigrationVersion >= CURRENT_MIGRATION_VERSION) {
            if (needsCommit) {
                editor.commit();
            }
            return;
        }
        
        // WARNING: Migration is running! This should only happen once per version upgrade.
        // If you see this log message repeatedly, there's a bug causing settings to be reset.
        android.util.Log.w("Preferences", "⚠️ RUNNING MIGRATIONS from v" + lastMigrationVersion + " to v" + CURRENT_MIGRATION_VERSION + 
            " - This should only happen ONCE per app version upgrade!");
        
        if (lastMigrationVersion < CURRENT_MIGRATION_VERSION) {
            
            // Migration 1: Set noise reduction to OFF and post processing to MAT
            // This migration runs once when installing this version, updating existing user preferences
            if (lastMigrationVersion < 1) {
                String processModeKey = context.getString(R.string.pref_post_process);
                String noiseReduceKey = context.getString(R.string.pref_noise_reduce);
                
                // Force these values for all existing users
                editor.putString(processModeKey, "MAT");
                editor.putBoolean(noiseReduceKey, false);
            }
            
            // Migration 2: Set sharpening and noise reduction sliders to 0
            // Both sliders are now additive with default 0
            // FloatRef stores values as Strings in SharedPreferences
            // Remove old Float values and set as String "0"
            if (lastMigrationVersion < 2) {
                String sharpeningKey = context.getString(R.string.pref_sharpening);
                String noiseReductionKey = context.getString(R.string.pref_noise_reduction);
                
                // Unconditionally remove both keys (regardless of their current type)
                // This avoids ClassCastException when FloatRef tries to read them as Strings
                editor.remove(sharpeningKey);
                editor.remove(noiseReductionKey);
                
                // Set as String values (FloatRef expects Strings)
                editor.putString(sharpeningKey, "0");
                editor.putString(noiseReductionKey, "0");
            }
            
            // Migration 3: Enable "Replace IMG prefix" setting
            // Changed from "Append DNGP" to "Replace IMG prefix" and enabled by default
            if (lastMigrationVersion < 3) {
                String suffixKey = context.getString(R.string.pref_suffix);
                editor.putBoolean(suffixKey, true);
            }
            
            // Migration 4: Enable "Match embedded JPEG" option by default
            // This feature uses the embedded preview for tone matching to match camera's intended look
            if (lastMigrationVersion < 4) {
                String referencePreviewKey = context.getString(R.string.pref_reference_preview);
                editor.putBoolean(referencePreviewKey, true);
            }
            
            // Migration 5: Disable "Match embedded JPEG" option by default
            // Reverting the default to false for better performance and user control
            if (lastMigrationVersion < 5) {
                String referencePreviewKey = context.getString(R.string.pref_reference_preview);
                editor.putBoolean(referencePreviewKey, false);
            }
            
            // Migration 6: Invert highlights slider direction to match Lightroom
            // The highlights slider direction was reversed: positive now brightens (was recover),
            // negative now recovers (was brighten). Invert all non-zero values to preserve user intent.
            if (lastMigrationVersion < 6) {
                String highlightsKey = context.getString(R.string.pref_tone_highlights);
                // FloatRef stores values as Strings in SharedPreferences
                // Use GlobalPreferences.getStringSafe() to safely read the value
                String highlightsValueStr = GlobalPreferences.getStringSafe(
                    prefs, highlightsKey, "0");
                
                try {
                    float highlightsValue = Float.parseFloat(highlightsValueStr);
                    // Only invert non-zero values (0 means default, no adjustment needed)
                    if (Math.abs(highlightsValue) > 0.001f) {
                        float invertedValue = -highlightsValue;
                        editor.putString(highlightsKey, String.valueOf(invertedValue));
                    }
                } catch (NumberFormatException e) {
                    // If parsing fails, leave it as-is (will use default on next load)
                    android.util.Log.w("Preferences", "Failed to parse highlights value for migration: " + highlightsValueStr);
                }
            }
            
            // Migration 7: Enable exposure fusion
            // Re-enable exposure fusion for all users
            if (lastMigrationVersion < 7) {
                String exposeFuseKey = context.getString(R.string.pref_expose_fuse);
                editor.putBoolean(exposeFuseKey, true);
            }
            
            // Migration 8: Mark migration version (baseline_exposure_compression conversion handled above)
            // The actual conversion is done unconditionally at the start of migratePreferences()
            // to prevent ClassCastException when Android's PreferenceFragment reads preferences
            // This migration block is just to mark the version as migrated
            if (lastMigrationVersion < 8) {
                // Conversion already handled at the start of this method
            }
            
            // Migration 9: Set baseline exposure compression to Gamma+ACES Fusion (11) as default
            // This migration sets the baseline exposure compression to the new default method
            // for users who haven't explicitly set it (i.e., using the old default of 1)
            if (lastMigrationVersion < 9) {
                compressionKey = context.getString(R.string.pref_baseline_exposure_compression);
                // Only migrate if the user hasn't explicitly set a different value
                // Check if the current value is the old default (1 = Reinhard)
                String currentValue = prefs.getString(compressionKey, null);
                if (currentValue == null || "1".equals(currentValue)) {
                    // Set to new default: 11 = Gamma+ACES Fusion
                    editor.putString(compressionKey, "11");
                }
            }
            
            // Migration 10: Add 3 to saturation if saturation != 0 and processing mode is MAT
            // This migration adjusts saturation for MAT mode users who have non-zero saturation
            if (lastMigrationVersion < 10) {
                String saturationKey = context.getString(R.string.pref_tone_saturation);
                String processModeKey = context.getString(R.string.pref_post_process);
                
                // FloatRef stores values as Strings in SharedPreferences
                // Use GlobalPreferences.getStringSafe() to safely read the value
                String saturationValueStr = GlobalPreferences.getStringSafe(
                    prefs, saturationKey, "0");
                String processModeValue = GlobalPreferences.getStringSafe(
                    prefs, processModeKey, "MAT");
                
                try {
                    float saturationValue = Float.parseFloat(saturationValueStr);
                    // Only migrate if saturation != 0 and processing mode is MAT
                    if (Math.abs(saturationValue) > 0.001f && "MAT".equals(processModeValue)) {
                        float newSaturationValue = saturationValue + 2.0f;
                        editor.putString(saturationKey, String.valueOf(newSaturationValue));
                    }
                } catch (NumberFormatException e) {
                    // If parsing fails, leave it as-is (will use default on next load)
                    android.util.Log.w("Preferences", "Failed to parse saturation value for migration: " + saturationValueStr);
                }
            }
            
            // Migration 11: Set demosaicing method to bilinear (new default)
            // This migration sets the demosaicing method to bilinear for users who haven't
            // explicitly set it (i.e., using the old default of "dht")
            if (lastMigrationVersion < 11) {
                String demosaicingMethodKey = context.getString(R.string.pref_demosaicing_method);
                // Only migrate if the user hasn't explicitly set a different value
                // Check if the current value is the old default ("dht") or not set
                String currentValue = prefs.getString(demosaicingMethodKey, null);
                if (currentValue == null || "dht".equals(currentValue)) {
                    // Set to new default: bilinear
                    editor.putString(demosaicingMethodKey, "bilinear");
                }
            }
            
            // Migration 12: Set baseline exposure compression to None (0) as default
            // This migration sets the baseline exposure compression to the new default method
            // for users who haven't explicitly set it (i.e., using the old default of 11)
            if (lastMigrationVersion < 12) {
                compressionKey = context.getString(R.string.pref_baseline_exposure_compression);
                // Only migrate if the user hasn't explicitly set a different value
                // Check if the current value is the old default (11 = Gamma+ACES Fusion) or not set
                String currentValue = prefs.getString(compressionKey, null);
                if (currentValue == null || "11".equals(currentValue)) {
                    // Set to new default: 0 = None
                    editor.putString(compressionKey, "0");
                }
            }
            
            // Migration 13: Reset all advanced options to their defaults
            // This migration resets all advanced options to their default values
            if (lastMigrationVersion < 13) {
                android.util.Log.w("Preferences", "⚠️ Migration 13 RUNNING - This will RESET all advanced options to defaults!");
                // Boolean preferences
                editor.putBoolean(context.getString(R.string.pref_expose_fuse), true);
                editor.putBoolean(context.getString(R.string.pref_reference_preview), false);
                editor.putBoolean(context.getString(R.string.pref_edge_aware_hist_eq), true);
                editor.putBoolean(context.getString(R.string.pref_forward_matrix), true);
                editor.putBoolean(context.getString(R.string.pref_gain_map), true);
                editor.putBoolean(context.getString(R.string.pref_ultra_hdr), true);
                editor.putBoolean(context.getString(R.string.pref_copy_gain_map_from_jpeg), false);
                editor.putBoolean(context.getString(R.string.pref_save_hdr_gain_map), false);
                editor.putBoolean(context.getString(R.string.pref_save_exposure_fusion_frames), false);
                
                // String preferences
                editor.putString(context.getString(R.string.pref_demosaicing_method), "bilinear");
                editor.putString(context.getString(R.string.pref_baseline_exposure_compression), "0");
                editor.putString(context.getString(R.string.pref_hdr_compression_method), "4");
                
                // Integer preferences (stored as strings)
                editor.putString(context.getString(R.string.pref_jpeg_quality), "97");
            }
            
            // Migration 15: Reduce LCE weak radius from 10 to 5, medium radius from 20 to 10
            // These smaller radii work better with the doubled fast CLAHE effect
            if (lastMigrationVersion < 15) {
                String weakRadiusKey = context.getString(R.string.pref_lce_radius_weak);
                String mediumRadiusKey = context.getString(R.string.pref_lce_radius_medium);
                
                // Migrate weak radius: 10 -> 5
                String weakValue = prefs.getString(weakRadiusKey, null);
                if (weakValue == null || "10.0".equals(weakValue) || "10".equals(weakValue)) {
                    editor.putString(weakRadiusKey, "5.0");
                }
                
                // Migrate medium radius: 20 -> 10
                String mediumValue = prefs.getString(mediumRadiusKey, null);
                if (mediumValue == null || "20.0".equals(mediumValue) || "20".equals(mediumValue)) {
                    editor.putString(mediumRadiusKey, "10.0");
                }
            }
            
            // Migration 16: Set default noise reduction to 25 and sharpening to 60 (equivalent to dcraw's 0.3)
            // Sharpening: 60 in 0-100 range converts to 0.3 sharpenFactor (matching dcraw's default)
            // Noise reduction: 25 is a reasonable moderate default
            if (lastMigrationVersion < 16) {
                String sharpeningKey = context.getString(R.string.pref_sharpening);
                String noiseReductionKey = context.getString(R.string.pref_noise_reduction);
                
                // Only migrate if the user hasn't explicitly set a different value
                // Check if the current value is the old default (0) or not set
                String sharpeningValue = GlobalPreferences.getStringSafe(
                    prefs, sharpeningKey, "0");
                String noiseReductionValue = GlobalPreferences.getStringSafe(
                    prefs, noiseReductionKey, "0");
                
                try {
                    float sharpeningFloat = Float.parseFloat(sharpeningValue);
                    float noiseReductionFloat = Float.parseFloat(noiseReductionValue);
                    
                    // Only migrate if values are at the old default (0)
                    if (Math.abs(sharpeningFloat) < 0.001f) {
                        editor.putString(sharpeningKey, "60");
                    }
                    if (Math.abs(noiseReductionFloat) < 0.001f) {
                        editor.putString(noiseReductionKey, "25");
                    }
                } catch (NumberFormatException e) {
                    // If parsing fails, set defaults anyway
                    editor.putString(sharpeningKey, "60");
                    editor.putString(noiseReductionKey, "25");
                }
            }
            
            // Migration 17: Update sharpening cap to 1.0 (100%) and adjust default from 60 to 30
            // Sharpening conversion changed from /200f (0-100 -> 0.0-0.5) to /100f (0-100 -> 0.0-1.0)
            // Default changed from 60 to 30 to maintain same effective value (0.3 sharpenFactor)
            // Convert users with old default 60 to new default 30 (equivalent sharpening)
            if (lastMigrationVersion < 17) {
                String sharpeningKey = context.getString(R.string.pref_sharpening);
                String sharpeningValue = GlobalPreferences.getStringSafe(
                    prefs, sharpeningKey, "0");
                
                try {
                    float sharpeningFloat = Float.parseFloat(sharpeningValue);
                    
                    // Convert old default 60 to new default 30 (both give 0.3 sharpenFactor)
                    if (Math.abs(sharpeningFloat - 60f) < 0.001f) {
                        editor.putString(sharpeningKey, "30");
                    }
                    // Set new default for users still at 0
                    else if (Math.abs(sharpeningFloat) < 0.001f) {
                        editor.putString(sharpeningKey, "30");
                    }
                    // Users with custom values keep their settings
                } catch (NumberFormatException e) {
                    // If parsing fails, set new default
                    editor.putString(sharpeningKey, "30");
                }
            }
            
            // Migration 18: Disable edge-aware histogram equalization and move to experimental features
            // This migration disables the feature for all users and moves it to experimental section
            if (lastMigrationVersion < 18) {
                String edgeAwareHistEqKey = context.getString(R.string.pref_edge_aware_hist_eq);
                // Disable edge-aware histogram equalization for all users
                editor.putBoolean(edgeAwareHistEqKey, false);
            }
            
            // Migration 19: Set default baseline exposure method to 17 (exposure fusion),
            // set default exposure fusion to simple mertens, and ensure edge-aware histogram equalization is off
            if (lastMigrationVersion < 19) {
                String baselineExposureCompressionKey = context.getString(R.string.pref_baseline_exposure_compression);
                String exposeFusionMethodKey = context.getString(R.string.pref_expose_fusion_method);
                String edgeAwareHistEqKey = context.getString(R.string.pref_edge_aware_hist_eq);
                
                // Set baseline exposure compression to 17 (Exposure Fusion)
                editor.putString(baselineExposureCompressionKey, "17");
                
                // Set exposure fusion method to mertens (Simple Mertens)
                editor.putString(exposeFusionMethodKey, "mertens");
                
                // Ensure edge-aware histogram equalization is off
                editor.putBoolean(edgeAwareHistEqKey, false);
            }
            
            // Migration 20: Set baseline exposure compression to None (0), HDR compression method to Late Exposure Fusion (5),
            // exposure fusion toggle to ON, and exposure fusion method to Simple Mertens (mertens)
            // This migration sets baseline exposure compression to None, HDR compression to Late Exposure Fusion,
            // enables exposure fusion, and sets exposure fusion method to Simple Mertens
            if (lastMigrationVersion < 20) {
                String baselineExposureCompressionKey = context.getString(R.string.pref_baseline_exposure_compression);
                String hdrCompressionMethodKey = context.getString(R.string.pref_hdr_compression_method);
                String exposeFuseKey = context.getString(R.string.pref_expose_fuse);
                String exposeFusionMethodKey = context.getString(R.string.pref_expose_fusion_method);
                
                // Set baseline exposure compression to 0 (None)
                editor.putString(baselineExposureCompressionKey, "0");
                
                // Set HDR compression method to 5 (Late Exposure Fusion)
                editor.putString(hdrCompressionMethodKey, "5");
                
                // Set exposure fusion toggle to ON
                editor.putBoolean(exposeFuseKey, true);
                
                // Set exposure fusion method to mertens (Simple Mertens)
                editor.putString(exposeFusionMethodKey, "mertens");
            }
            
            // Mark migration as complete
            editor.putInt(PREF_MIGRATION_VERSION, CURRENT_MIGRATION_VERSION);
            // Use commit() instead of apply() to ensure migration completes synchronously
            // before preferences are loaded by the Fragment
            boolean commitSuccess = editor.commit();
            if (commitSuccess) {
                android.util.Log.i("Preferences", "✓ Migration completed successfully: v" + lastMigrationVersion + " → v" + CURRENT_MIGRATION_VERSION);
            } else {
                android.util.Log.e("Preferences", "✗ MIGRATION COMMIT FAILED! Settings may be reset on next launch!");
            }
        }
    }

    /*
     * FILES
     */

    public final BooleanRef backgroundProcess =
            new BooleanRef(R.string.pref_background_process,
                    R.bool.pref_background_process_default);

    public final BooleanRef deleteOriginalJpeg =
            new BooleanRef(R.string.pref_delete_original_jpeg,
                    R.bool.pref_delete_original_jpeg_default);

    public final StringRef jpegLocation =
            new StringRef(R.string.pref_jpeg_location,
                    R.string.pref_jpeg_location_default);

    public final BooleanRef deleteOriginal =
            new BooleanRef(R.string.pref_delete_original,
                    R.bool.pref_delete_original_default);

    public final BooleanRef skipExisting =
            new BooleanRef(R.string.pref_skip_existing,
                    R.bool.pref_skip_existing_default);

    public final BooleanRef suffix =
            new BooleanRef(R.string.pref_suffix,
                    R.bool.pref_suffix_default);

    public final StringRef replacePrefixText =
            new StringRef(R.string.pref_replace_prefix_text,
                    R.string.pref_replace_prefix_text_default);

    public final BooleanRef addSuffix =
            new BooleanRef(R.string.pref_add_suffix,
                    R.bool.pref_add_suffix_default);

    public final StringRef addSuffixText =
            new StringRef(R.string.pref_add_suffix_text,
                    R.string.pref_add_suffix_text_default);

    public final StringRef savePath =
            new StringRef(R.string.pref_save_path,
                    R.string.pref_save_path_default);

    public final IntegerRef jpegQuality =
            new IntegerRef(R.string.pref_jpeg_quality,
                    R.integer.pref_jpeg_quality_default);

    /*
     * SATURATION
     */

    public final FloatRef saturationRed =
            new FloatRef(R.string.pref_saturation_r,
                    R.integer.pref_saturation_default);

    public final FloatRef saturationYellow =
            new FloatRef(R.string.pref_saturation_y,
                    R.integer.pref_saturation_default);

    public final FloatRef saturationGreen =
            new FloatRef(R.string.pref_saturation_g,
                    R.integer.pref_saturation_default);

    public final FloatRef saturationCyan =
            new FloatRef(R.string.pref_saturation_c,
                    R.integer.pref_saturation_default);

    public final FloatRef saturationBlue =
            new FloatRef(R.string.pref_saturation_b,
                    R.integer.pref_saturation_default);

    public final FloatRef saturationIndigo =
            new FloatRef(R.string.pref_saturation_i,
                    R.integer.pref_saturation_default);

    public final FloatRef saturationViolet =
            new FloatRef(R.string.pref_saturation_v,
                    R.integer.pref_saturation_default);

    public final FloatRef saturationMagenta =
            new FloatRef(R.string.pref_saturation_m,
                    R.integer.pref_saturation_default);

    public final FloatRef saturationLimit =
            new FloatRef(R.string.pref_saturation_limit,
                    R.integer.pref_saturation_limit_default);

    /*
     * PIPELINE
     */

    public final StringRef processMode =
            new StringRef(R.string.pref_post_process,
                    R.string.pref_post_process_default);

    public final BooleanRef noiseReduce =
            new BooleanRef(R.string.pref_noise_reduce,
                    R.bool.pref_noise_reduce_default);

    public final BooleanRef exposeFuse =
            new BooleanRef(R.string.pref_expose_fuse,
                    R.bool.pref_expose_fuse_default);

    public final StringRef exposeFusionMethod =
            new StringRef(R.string.pref_expose_fusion_method,
                    R.string.pref_expose_fusion_method_default);

    public final BooleanRef lce =
            new BooleanRef(R.string.pref_lce,
                    R.bool.pref_lce_default);

    public final StringRef lceMethod =
            new StringRef(R.string.pref_lce_method,
                    R.string.pref_lce_method_default);
    
    // LCE Radii (percentages)
    public final FloatRef lceRadiusWeak =
            new FloatRef(R.string.pref_lce_radius_weak,
                    R.integer.pref_lce_radius_weak_default);
    
    public final FloatRef lceRadiusMedium =
            new FloatRef(R.string.pref_lce_radius_medium,
                    R.integer.pref_lce_radius_medium_default);
    
    public final FloatRef lceRadiusStrong =
            new FloatRef(R.string.pref_lce_radius_strong,
                    R.integer.pref_lce_radius_strong_default);
    
    public final FloatRef lceRadiusXfine =
            new FloatRef(R.string.pref_lce_radius_xfine,
                    R.integer.pref_lce_radius_xfine_default);
    
    public final FloatRef lceRadiusFine =
            new FloatRef(R.string.pref_lce_radius_fine,
                    R.integer.pref_lce_radius_fine_default);
    
    public final FloatRef lceRadiusXstrong =
            new FloatRef(R.string.pref_lce_radius_xstrong,
                    R.integer.pref_lce_radius_xstrong_default);
    
    // LCE Strengths
    public final FloatRef lceStrengthWeak =
            new FloatRef(R.string.pref_lce_strength_weak,
                    R.integer.pref_lce_strength_weak_default);
    
    public final FloatRef lceStrengthMedium =
            new FloatRef(R.string.pref_lce_strength_medium,
                    R.integer.pref_lce_strength_medium_default);
    
    public final FloatRef lceStrengthStrong =
            new FloatRef(R.string.pref_lce_strength_strong,
                    R.integer.pref_lce_strength_strong_default);
    
    public final FloatRef lceStrengthXfine =
            new FloatRef(R.string.pref_lce_strength_xfine,
                    R.integer.pref_lce_strength_xfine_default);
    
    public final FloatRef lceStrengthFine =
            new FloatRef(R.string.pref_lce_strength_fine,
                    R.integer.pref_lce_strength_fine_default);
    
    public final FloatRef lceStrengthXstrong =
            new FloatRef(R.string.pref_lce_strength_xstrong,
                    R.integer.pref_lce_strength_xstrong_default);
    
    // LCE Limits
    public final FloatRef lceLimitWeak =
            new FloatRef(R.string.pref_lce_limit_weak,
                    R.integer.pref_lce_limit_weak_default);
    
    public final FloatRef lceLimitMedium =
            new FloatRef(R.string.pref_lce_limit_medium,
                    R.integer.pref_lce_limit_medium_default);
    
    public final FloatRef lceLimitStrong =
            new FloatRef(R.string.pref_lce_limit_strong,
                    R.integer.pref_lce_limit_strong_default);
    
    public final FloatRef lceLimitXfine =
            new FloatRef(R.string.pref_lce_limit_xfine,
                    R.integer.pref_lce_limit_xfine_default);
    
    public final FloatRef lceLimitFine =
            new FloatRef(R.string.pref_lce_limit_fine,
                    R.integer.pref_lce_limit_fine_default);
    
    public final FloatRef lceLimitXstrong =
            new FloatRef(R.string.pref_lce_limit_xstrong,
                    R.integer.pref_lce_limit_xstrong_default);

    public final BooleanRef ahe =
            new BooleanRef(R.string.pref_ahe,
                    R.bool.pref_ahe_default);

    public final BooleanRef edgeAwareHistEq =
            new BooleanRef(R.string.pref_edge_aware_hist_eq,
                    R.bool.pref_edge_aware_hist_eq_default);

    public final BooleanRef referencePreview =
            new BooleanRef(R.string.pref_reference_preview,
                    R.bool.pref_reference_preview_default);

    public final FloatRef histMatchStrength =
            new FloatRef(R.string.pref_hist_match_strength,
                    R.integer.pref_hist_match_strength_default);

    public final BooleanRef forwardMatrix =
            new BooleanRef(R.string.pref_forward_matrix,
                    R.bool.pref_forward_matrix_default);

    public final BooleanRef gainMap =
            new BooleanRef(R.string.pref_gain_map,
                    R.bool.pref_gain_map_default);

    public final StringRef demosaicingMethod =
            new StringRef(R.string.pref_demosaicing_method,
                    R.string.pref_demosaicing_method_default);

    public final StringRef baselineExposureCompression =
            new StringRef(R.string.pref_baseline_exposure_compression,
                    R.string.pref_baseline_exposure_compression_default);

    public final StringRef hdrCompressionMethod =
            new StringRef(R.string.pref_hdr_compression_method,
                    R.string.pref_hdr_compression_method_default);

    public final BooleanRef experimentalSettings =
            new BooleanRef(R.string.pref_experimental_settings,
                    R.bool.pref_experimental_settings_default);

    public final BooleanRef ultraHdr =
            new BooleanRef(R.string.pref_ultra_hdr,
                    R.bool.pref_ultra_hdr_default);

    public final BooleanRef copyGainMapFromJpeg =
            new BooleanRef(R.string.pref_copy_gain_map_from_jpeg,
                    R.bool.pref_copy_gain_map_from_jpeg_default);

    public final BooleanRef saveHdrGainMap =
            new BooleanRef(R.string.pref_save_hdr_gain_map,
                    R.bool.pref_save_hdr_gain_map_default);

    public final BooleanRef saveExposureFusionFrames =
            new BooleanRef(R.string.pref_save_exposure_fusion_frames,
                    R.bool.pref_save_exposure_fusion_frames_default);

    public final FloatRef sharpening =
            new FloatRef(R.string.pref_sharpening,
                    R.integer.pref_sharpening_default);

    public final FloatRef noiseReduction =
            new FloatRef(R.string.pref_noise_reduction,
                    R.integer.pref_noise_reduction_default);

    public final BooleanRef forceNoiseReduction =
            new BooleanRef(R.string.pref_force_noise_reduction,
                    R.bool.pref_force_noise_reduction_default);

    public final BooleanRef waveletNoiseReduction =
            new BooleanRef(R.string.pref_wavelet_noise_reduction,
                    R.bool.pref_wavelet_noise_reduction_default);

    // MAT mode options
    public final BooleanRef matGreenToYellowShift =
            new BooleanRef(R.string.pref_mat_green_to_yellow_shift,
                    R.bool.pref_mat_green_to_yellow_shift_default);

    public final BooleanRef matYellowToWarmShift =
            new BooleanRef(R.string.pref_mat_yellow_to_warm_shift,
                    R.bool.pref_mat_yellow_to_warm_shift_default);

    /*
     * COLOR TRANSFORM
     */

    public final FloatRef colorTransformRR =
            new FloatRef(R.string.pref_color_transform_rr,
                    R.integer.pref_color_transform_rr_default);

    public final FloatRef colorTransformRG =
            new FloatRef(R.string.pref_color_transform_rg,
                    R.integer.pref_color_transform_rg_default);

    public final FloatRef colorTransformRB =
            new FloatRef(R.string.pref_color_transform_rb,
                    R.integer.pref_color_transform_rb_default);

    public final FloatRef colorTransformGR =
            new FloatRef(R.string.pref_color_transform_gr,
                    R.integer.pref_color_transform_gr_default);

    public final FloatRef colorTransformGG =
            new FloatRef(R.string.pref_color_transform_gg,
                    R.integer.pref_color_transform_gg_default);

    public final FloatRef colorTransformGB =
            new FloatRef(R.string.pref_color_transform_gb,
                    R.integer.pref_color_transform_gb_default);

    public final FloatRef colorTransformBR =
            new FloatRef(R.string.pref_color_transform_br,
                    R.integer.pref_color_transform_br_default);

    public final FloatRef colorTransformBG =
            new FloatRef(R.string.pref_color_transform_bg,
                    R.integer.pref_color_transform_bg_default);

    public final FloatRef colorTransformBB =
            new FloatRef(R.string.pref_color_transform_bb,
                    R.integer.pref_color_transform_bb_default);

    /*
     * TONE ADJUSTMENTS (Lightroom-style)
     */

    public final FloatRef toneExposure =
            new FloatRef(R.string.pref_tone_exposure,
                    R.integer.pref_tone_exposure_default);

    public final FloatRef toneHighlights =
            new FloatRef(R.string.pref_tone_highlights,
                    R.integer.pref_tone_highlights_default);

    public final FloatRef toneShadows =
            new FloatRef(R.string.pref_tone_shadows,
                    R.integer.pref_tone_shadows_default);

    public final FloatRef toneWhites =
            new FloatRef(R.string.pref_tone_whites,
                    R.integer.pref_tone_whites_default);

    public final FloatRef toneContrast =
            new FloatRef(R.string.pref_tone_contrast,
                    R.integer.pref_tone_contrast_default);

    public final FloatRef toneBlacks =
            new FloatRef(R.string.pref_tone_blacks,
                    R.integer.pref_tone_blacks_default);

    public final FloatRef toneTexture =
            new FloatRef(R.string.pref_tone_texture,
                    R.integer.pref_tone_texture_default);

    public final FloatRef toneClarity =
            new FloatRef(R.string.pref_tone_clarity,
                    R.integer.pref_tone_clarity_default);

    public final FloatRef toneDehaze =
            new FloatRef(R.string.pref_tone_dehaze,
                    R.integer.pref_tone_dehaze_default);

    public final FloatRef toneVibrance =
            new FloatRef(R.string.pref_tone_vibrance,
                    R.integer.pref_tone_vibrance_default);

    public final FloatRef toneSaturation =
            new FloatRef(R.string.pref_tone_saturation,
                    R.integer.pref_tone_saturation_default);

    public final StringRef externalLutPath =
            new StringRef(R.string.pref_external_lut_path,
                    R.string.pref_external_lut_path_default);

    /*
     * ADAPTIVE SATURATION
     */

    public final BooleanRef adaptiveSaturationCurveEnabled =
            new BooleanRef(R.string.pref_adaptive_saturation_curve_enabled,
                    R.bool.pref_adaptive_saturation_curve_enabled_default);

    public final FloatRef histFactor =
            new FloatRef(R.string.pref_hist_factor,
                    R.integer.pref_hist_factor_default);

    public final FloatRef adaptiveSaturationPower =
            new FloatRef(R.string.pref_adaptive_saturation_power,
                    R.integer.pref_adaptive_saturation_power_default);

    /*
     * LOCAL LAPLACIAN FILTER (darktable-style local contrast)
     */
    
    public final BooleanRef localLaplacianEnabled =
            new BooleanRef(R.string.pref_local_laplacian_enabled,
                    R.bool.pref_local_laplacian_enabled_default);
    
    public final FloatRef localLaplacianShadows =
            new FloatRef(R.string.pref_local_laplacian_shadows,
                    R.integer.pref_local_laplacian_shadows_default);
    
    public final FloatRef localLaplacianHighlights =
            new FloatRef(R.string.pref_local_laplacian_highlights,
                    R.integer.pref_local_laplacian_highlights_default);
    
    public final FloatRef localLaplacianClarity =
            new FloatRef(R.string.pref_local_laplacian_clarity,
                    R.integer.pref_local_laplacian_clarity_default);
    
    public final FloatRef localLaplacianSigma =
            new FloatRef(R.string.pref_local_laplacian_sigma,
                    R.integer.pref_local_laplacian_sigma_default);
    
    public final BooleanRef localLaplacianAutoTune =
            new BooleanRef(R.string.pref_local_laplacian_autotune,
                    R.bool.pref_local_laplacian_autotune_default);

    /*
     * TONE EQUALIZER (EV-band based adjustment)
     */
    
    public final BooleanRef toneEqualizerEnabled =
            new BooleanRef(R.string.pref_tone_equalizer_enabled,
                    R.bool.pref_tone_equalizer_enabled_default);
    
    public final FloatRef toneEqBlacks =
            new FloatRef(R.string.pref_tone_eq_blacks,
                    R.integer.pref_tone_eq_blacks_default);
    
    public final FloatRef toneEqDeepShadows =
            new FloatRef(R.string.pref_tone_eq_deep_shadows,
                    R.integer.pref_tone_eq_deep_shadows_default);
    
    public final FloatRef toneEqShadows =
            new FloatRef(R.string.pref_tone_eq_shadows,
                    R.integer.pref_tone_eq_shadows_default);
    
    public final FloatRef toneEqLightShadows =
            new FloatRef(R.string.pref_tone_eq_light_shadows,
                    R.integer.pref_tone_eq_light_shadows_default);
    
    public final FloatRef toneEqMidtones =
            new FloatRef(R.string.pref_tone_eq_midtones,
                    R.integer.pref_tone_eq_midtones_default);
    
    public final FloatRef toneEqDarkHighlights =
            new FloatRef(R.string.pref_tone_eq_dark_highlights,
                    R.integer.pref_tone_eq_dark_highlights_default);
    
    public final FloatRef toneEqHighlights =
            new FloatRef(R.string.pref_tone_eq_highlights,
                    R.integer.pref_tone_eq_highlights_default);
    
    public final FloatRef toneEqWhites =
            new FloatRef(R.string.pref_tone_eq_whites,
                    R.integer.pref_tone_eq_whites_default);
    
    public final FloatRef toneEqSpeculars =
            new FloatRef(R.string.pref_tone_eq_speculars,
                    R.integer.pref_tone_eq_speculars_default);
    
    public final FloatRef toneEqSmoothing =
            new FloatRef(R.string.pref_tone_eq_smoothing,
                    R.integer.pref_tone_eq_smoothing_default);
    
    public final FloatRef toneEqFeathering =
            new FloatRef(R.string.pref_tone_eq_feathering,
                    R.integer.pref_tone_eq_feathering_default);
    
    public final BooleanRef toneEqualizerAutoTune =
            new BooleanRef(R.string.pref_tone_equalizer_autotune,
                    R.bool.pref_tone_equalizer_autotune_default);

    public enum PostProcessMode {
        Disabled,
        Classic,  // Original amirz Natural mode LCE, with modern HDR handling
        Natural,
        Boosted,
        MAT,      // Subtle sigmoidal curve + green/yellow hue shifts
        LeicaM9   // Leica M9 CCD sensor look: warm reds, film-like tones, classic rendering
    }

    public static class Fragment extends PreferenceFragment {
        private MainActivity mActivity;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            mActivity = (MainActivity) getActivity();
            
            // Migration now runs in Application.onCreate() before any Activity/Fragment is created
            // This ensures preferences are converted before Android's PreferenceFragment tries to read them
            // No need to run migration here anymore, but kept as safety net
            Preferences.global().migratePreferences(mActivity);
            
            super.onCreate(savedInstanceState);

            getPreferenceManager().setSharedPreferencesName(mActivity.getPackageName());
            addPreferencesFromResource(R.xml.preferences);

            // Set up mutual exclusivity between Ultra HDR and Copy Gain Map from JPEG
            SwitchPreference ultraHdrPref = (SwitchPreference) findPreference(
                    getString(R.string.pref_ultra_hdr));
            SwitchPreference copyGainMapPref = (SwitchPreference) findPreference(
                    getString(R.string.pref_copy_gain_map_from_jpeg));
            
            if (ultraHdrPref != null && copyGainMapPref != null) {
                ultraHdrPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (Boolean.TRUE.equals(newValue)) {
                        // If Ultra HDR is enabled, disable Copy Gain Map
                        copyGainMapPref.setChecked(false);
                    }
                    return true;
                });
                
                copyGainMapPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (Boolean.TRUE.equals(newValue)) {
                        // If Copy Gain Map is enabled, disable Ultra HDR
                        ultraHdrPref.setChecked(false);
                    }
                    return true;
                });
            }

            // Enable/disable MAT mode options based on selected post-processing mode
            android.preference.ListPreference postProcessPref = (android.preference.ListPreference) findPreference(
                    getString(R.string.pref_post_process));
            Preference matModeOptionsPref = findPreference(getString(R.string.pref_mat_mode_options));
            
            if (postProcessPref != null && matModeOptionsPref != null) {
                // Update enabled state based on current selection
                updateMatModeOptionsEnabled(postProcessPref, matModeOptionsPref);
                
                // Update enabled state when post-processing mode changes
                postProcessPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    updateMatModeOptionsEnabled(postProcessPref, matModeOptionsPref);
                    return true;
                });
            }

            findPreference(getString(R.string.pref_manual_select))
                    .setOnPreferenceClickListener(mActivity::requestImage);

            findPreference(getString(R.string.pref_burst_select))
                    .setOnPreferenceClickListener(mActivity::requestBurst);

            // External LUT file picker
            String prefKey = mActivity.getResources().getString(R.string.pref_external_lut_path);
            Preference externalLutPref = findPreference(prefKey);
            if (externalLutPref != null) {
                externalLutPref.setOnPreferenceClickListener(preference -> {
                    mActivity.requestLutFile(preference);
                    return true;
                });
                // Update summary to show current file path
                updateExternalLutSummary(externalLutPref);
            }
            
            // External LUT clear button
            Preference externalLutClearPref = findPreference(getString(R.string.pref_external_lut_clear));
            if (externalLutClearPref != null) {
                externalLutClearPref.setOnPreferenceClickListener(preference -> {
                    // Clear the LUT path from preferences
                    android.content.SharedPreferences prefs = Utilities.prefs(mActivity);
                    prefs.edit()
                        .putString(getString(R.string.pref_external_lut_path), "")
                        .apply();
                    // Update the summary and enabled state
                    if (externalLutPref != null) {
                        updateExternalLutSummary(externalLutPref);
                    }
                    updateClearLutEnabled(externalLutClearPref);
                    return true;
                });
                // Set initial enabled state
                updateClearLutEnabled(externalLutClearPref);
            }

            findPreference(getString(R.string.pref_reprocess))
                    .setOnPreferenceClickListener(p -> {
                        String uri = Utilities.prefs(mActivity)
                                .getString(mActivity.getString(R.string.pref_reprocess), "");
                        if (!TextUtils.isEmpty(uri)) {
                            DngParseWorker.enqueueWork(mActivity, Uri.parse(uri));
                        }
                        return false;
                    });

            // Add warning when enabling "delete original"
            SwitchPreference deleteOriginalPref = (SwitchPreference) findPreference(
                    getString(R.string.pref_delete_original));
            
            if (deleteOriginalPref != null) {
                final boolean[] isApplyingChange = {false};
                deleteOriginalPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    // If we're applying the change programmatically, allow it
                    if (isApplyingChange[0]) {
                        return true;
                    }
                    
                    if (Boolean.TRUE.equals(newValue)) {
                        // Show warning dialog
                        new AlertDialog.Builder(mActivity)
                                .setTitle(R.string.pref_delete_original_warning_title)
                                .setMessage(R.string.pref_delete_original_warning_message)
                                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                    // User confirmed, allow the change
                                    isApplyingChange[0] = true;
                                    deleteOriginalPref.setChecked(true);
                                    isApplyingChange[0] = false;
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .setOnCancelListener(dialog -> {
                                    // User cancelled or dismissed, keep the preference unchanged
                                })
                                .show();
                        // Prevent the default change
                        return false;
                    }
                    // Allow turning it off without warning
                    return true;
                });
            }

            // Add warning when enabling "experimental settings"
            SwitchPreference experimentalSettingsPref = (SwitchPreference) findPreference(
                    getString(R.string.pref_experimental_settings));
            
            if (experimentalSettingsPref != null) {
                final boolean[] isApplyingChange = {false};
                experimentalSettingsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    // If we're applying the change programmatically, allow it
                    if (isApplyingChange[0]) {
                        return true;
                    }
                    
                    if (Boolean.TRUE.equals(newValue)) {
                        // Show warning dialog
                        new AlertDialog.Builder(mActivity)
                                .setTitle(R.string.pref_experimental_settings_warning_title)
                                .setMessage(R.string.pref_experimental_settings_warning_message)
                                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                    // User confirmed, allow the change
                                    isApplyingChange[0] = true;
                                    experimentalSettingsPref.setChecked(true);
                                    isApplyingChange[0] = false;
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .setOnCancelListener(dialog -> {
                                    // User cancelled or dismissed, keep the preference unchanged
                                })
                                .show();
                        // Prevent the default change
                        return false;
                    }
                    // Allow turning it off without warning
                    return true;
                });
            }

            // Add reset button for all advanced options
            findPreference(getString(R.string.pref_advanced_options_reset))
                    .setOnPreferenceClickListener(p -> {
                        Preferences prefs = global();
                        prefs.reset(Utilities.prefs(mActivity), mActivity.getResources(), ctx -> {
                            // Boolean preferences
                            ctx.reset(prefs.exposeFuse);
                            ctx.reset(prefs.referencePreview);
                            ctx.reset(prefs.edgeAwareHistEq);
                            ctx.reset(prefs.forwardMatrix);
                            ctx.reset(prefs.gainMap);
                            ctx.reset(prefs.ultraHdr);
                            ctx.reset(prefs.copyGainMapFromJpeg);
                            ctx.reset(prefs.saveHdrGainMap);
                            ctx.reset(prefs.saveExposureFusionFrames);
                            
                            // String preferences
                            ctx.reset(prefs.demosaicingMethod);
                            ctx.reset(prefs.baselineExposureCompression);
                            ctx.reset(prefs.hdrCompressionMethod);
                            
                            // Integer preferences
                            ctx.reset(prefs.jpegQuality);
                        });
                        // Notify preferences to refresh their displayed values
                        // Refresh ListPreferences
                        Preference pref = findPreference(getString(R.string.pref_demosaicing_method));
                        if (pref != null && pref instanceof android.preference.ListPreference) {
                            ((android.preference.ListPreference) pref).setSummary("%s");
                        }
                        pref = findPreference(getString(R.string.pref_baseline_exposure_compression));
                        if (pref != null && pref instanceof android.preference.ListPreference) {
                            ((android.preference.ListPreference) pref).setSummary("%s");
                        }
                        pref = findPreference(getString(R.string.pref_hdr_compression_method));
                        if (pref != null && pref instanceof android.preference.ListPreference) {
                            ((android.preference.ListPreference) pref).setSummary("%s");
                        }
                        // Refresh SliderPreference for JPEG quality
                        pref = findPreference(getString(R.string.pref_jpeg_quality));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        return true;
                    });

            findPreference(getString(R.string.pref_saturation_reset))
                    .setOnPreferenceClickListener(p -> {
                        Preferences prefs = global();
                        prefs.reset(Utilities.prefs(mActivity), mActivity.getResources(), ctx -> {
                            ctx.reset(prefs.saturationRed);
                            ctx.reset(prefs.saturationYellow);
                            ctx.reset(prefs.saturationGreen);
                            ctx.reset(prefs.saturationCyan);
                            ctx.reset(prefs.saturationBlue);
                            ctx.reset(prefs.saturationIndigo);
                            ctx.reset(prefs.saturationViolet);
                            ctx.reset(prefs.saturationMagenta);
                        });
                        // Notify preferences to refresh their displayed values
                        Preference pref = findPreference(getString(R.string.pref_saturation_r));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_saturation_y));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_saturation_g));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_saturation_c));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_saturation_b));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_saturation_i));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_saturation_v));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_saturation_m));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        return true;
                    });

            findPreference(getString(R.string.pref_tone_reset))
                    .setOnPreferenceClickListener(p -> {
                        Preferences prefs = global();
                        prefs.reset(Utilities.prefs(mActivity), mActivity.getResources(), ctx -> {
                            ctx.reset(prefs.toneExposure);
                            ctx.reset(prefs.toneHighlights);
                            ctx.reset(prefs.toneShadows);
                            ctx.reset(prefs.toneWhites);
                            ctx.reset(prefs.toneContrast);
                            ctx.reset(prefs.toneBlacks);
                            ctx.reset(prefs.toneTexture);
                            ctx.reset(prefs.toneClarity);
                            ctx.reset(prefs.toneDehaze);
                            ctx.reset(prefs.toneVibrance);
                            ctx.reset(prefs.toneSaturation);
                        });
                        // Notify preferences to refresh their displayed values
                        Preference pref = findPreference(getString(R.string.pref_tone_exposure));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_highlights));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_shadows));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_whites));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_contrast));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_blacks));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_texture));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_clarity));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_dehaze));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_vibrance));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_saturation));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        return true;
                    });

            findPreference(getString(R.string.pref_color_transform_reset))
                    .setOnPreferenceClickListener(p -> {
                        Preferences prefs = global();
                        prefs.reset(Utilities.prefs(mActivity), mActivity.getResources(), ctx -> {
                            ctx.reset(prefs.colorTransformRR);
                            ctx.reset(prefs.colorTransformRG);
                            ctx.reset(prefs.colorTransformRB);
                            ctx.reset(prefs.colorTransformGR);
                            ctx.reset(prefs.colorTransformGG);
                            ctx.reset(prefs.colorTransformGB);
                            ctx.reset(prefs.colorTransformBR);
                            ctx.reset(prefs.colorTransformBG);
                            ctx.reset(prefs.colorTransformBB);
                        });
                        // Notify preferences to refresh their displayed values
                        Preference pref = findPreference(getString(R.string.pref_color_transform_rr));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_color_transform_rg));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_color_transform_rb));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_color_transform_gr));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_color_transform_gg));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_color_transform_gb));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_color_transform_br));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_color_transform_bg));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_color_transform_bb));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        return true;
                    });

            // LCE reset buttons
            findPreference(getString(R.string.pref_lce_reset_xstrong))
                    .setOnPreferenceClickListener(p -> {
                        Preferences prefs = global();
                        prefs.reset(Utilities.prefs(mActivity), mActivity.getResources(), ctx -> {
                            ctx.reset(prefs.lceRadiusXstrong);
                            ctx.reset(prefs.lceStrengthXstrong);
                            ctx.reset(prefs.lceLimitXstrong);
                        });
                        // Notify preferences to refresh their displayed values
                        Preference pref = findPreference(getString(R.string.pref_lce_radius_xstrong));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_lce_strength_xstrong));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_lce_limit_xstrong));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        return true;
                    });

            findPreference(getString(R.string.pref_lce_reset_strong))
                    .setOnPreferenceClickListener(p -> {
                        Preferences prefs = global();
                        prefs.reset(Utilities.prefs(mActivity), mActivity.getResources(), ctx -> {
                            ctx.reset(prefs.lceRadiusStrong);
                            ctx.reset(prefs.lceStrengthStrong);
                            ctx.reset(prefs.lceLimitStrong);
                        });
                        // Notify preferences to refresh their displayed values
                        Preference pref = findPreference(getString(R.string.pref_lce_radius_strong));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_lce_strength_strong));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_lce_limit_strong));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        return true;
                    });

            findPreference(getString(R.string.pref_lce_reset_medium))
                    .setOnPreferenceClickListener(p -> {
                        Preferences prefs = global();
                        prefs.reset(Utilities.prefs(mActivity), mActivity.getResources(), ctx -> {
                            ctx.reset(prefs.lceRadiusMedium);
                            ctx.reset(prefs.lceStrengthMedium);
                            ctx.reset(prefs.lceLimitMedium);
                        });
                        // Notify preferences to refresh their displayed values
                        Preference pref = findPreference(getString(R.string.pref_lce_radius_medium));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_lce_strength_medium));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_lce_limit_medium));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        return true;
                    });

            findPreference(getString(R.string.pref_lce_reset_weak))
                    .setOnPreferenceClickListener(p -> {
                        Preferences prefs = global();
                        prefs.reset(Utilities.prefs(mActivity), mActivity.getResources(), ctx -> {
                            ctx.reset(prefs.lceRadiusWeak);
                            ctx.reset(prefs.lceStrengthWeak);
                            ctx.reset(prefs.lceLimitWeak);
                        });
                        // Notify preferences to refresh their displayed values
                        Preference pref = findPreference(getString(R.string.pref_lce_radius_weak));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_lce_strength_weak));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_lce_limit_weak));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        return true;
                    });

            findPreference(getString(R.string.pref_lce_reset_fine))
                    .setOnPreferenceClickListener(p -> {
                        Preferences prefs = global();
                        prefs.reset(Utilities.prefs(mActivity), mActivity.getResources(), ctx -> {
                            ctx.reset(prefs.lceRadiusFine);
                            ctx.reset(prefs.lceStrengthFine);
                            ctx.reset(prefs.lceLimitFine);
                        });
                        // Notify preferences to refresh their displayed values
                        Preference pref = findPreference(getString(R.string.pref_lce_radius_fine));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_lce_strength_fine));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_lce_limit_fine));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        return true;
                    });

            findPreference(getString(R.string.pref_lce_reset_xfine))
                    .setOnPreferenceClickListener(p -> {
                        Preferences prefs = global();
                        prefs.reset(Utilities.prefs(mActivity), mActivity.getResources(), ctx -> {
                            ctx.reset(prefs.lceRadiusXfine);
                            ctx.reset(prefs.lceStrengthXfine);
                            ctx.reset(prefs.lceLimitXfine);
                        });
                        // Notify preferences to refresh their displayed values
                        Preference pref = findPreference(getString(R.string.pref_lce_radius_xfine));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_lce_strength_xfine));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_lce_limit_xfine));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        return true;
                    });

            // Adaptive Saturation reset button
            findPreference(getString(R.string.pref_adaptive_saturation_reset))
                    .setOnPreferenceClickListener(p -> {
                        Preferences prefs = global();
                        prefs.reset(Utilities.prefs(mActivity), mActivity.getResources(), ctx -> {
                            ctx.reset(prefs.histFactor);
                            ctx.reset(prefs.adaptiveSaturationPower);
                        });
                        // Also clear the saved curve to reset to defaults
                        Utilities.prefs(mActivity).edit()
                                .remove("adaptive_saturation_curve")
                                .apply();
                        // Notify preferences to refresh their displayed values
                        Preference pref = findPreference(getString(R.string.pref_hist_factor));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_adaptive_saturation_power));
                        if (pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        return true;
                    });

            // Local Laplacian reset button
            Preference localLaplacianResetPref = findPreference(getString(R.string.pref_local_laplacian_reset));
            if (localLaplacianResetPref != null) {
                localLaplacianResetPref.setOnPreferenceClickListener(p -> {
                        Preferences prefs = global();
                        prefs.reset(Utilities.prefs(mActivity), mActivity.getResources(), ctx -> {
                            ctx.reset(prefs.localLaplacianShadows);
                            ctx.reset(prefs.localLaplacianHighlights);
                            ctx.reset(prefs.localLaplacianClarity);
                            ctx.reset(prefs.localLaplacianSigma);
                        });
                        // Notify preferences to refresh their displayed values
                        Preference pref = findPreference(getString(R.string.pref_local_laplacian_shadows));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_local_laplacian_highlights));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_local_laplacian_clarity));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_local_laplacian_sigma));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        return true;
                    });
            }

            // Tone Equalizer reset button
            Preference toneEqResetPref = findPreference(getString(R.string.pref_tone_eq_reset));
            if (toneEqResetPref != null) {
                toneEqResetPref.setOnPreferenceClickListener(p -> {
                        Preferences prefs = global();
                        prefs.reset(Utilities.prefs(mActivity), mActivity.getResources(), ctx -> {
                            ctx.reset(prefs.toneEqBlacks);
                            ctx.reset(prefs.toneEqDeepShadows);
                            ctx.reset(prefs.toneEqShadows);
                            ctx.reset(prefs.toneEqLightShadows);
                            ctx.reset(prefs.toneEqMidtones);
                            ctx.reset(prefs.toneEqDarkHighlights);
                            ctx.reset(prefs.toneEqHighlights);
                            ctx.reset(prefs.toneEqWhites);
                            ctx.reset(prefs.toneEqSpeculars);
                            ctx.reset(prefs.toneEqSmoothing);
                            ctx.reset(prefs.toneEqFeathering);
                        });
                        // Notify preferences to refresh their displayed values
                        Preference pref = findPreference(getString(R.string.pref_tone_eq_blacks));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_eq_deep_shadows));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_eq_shadows));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_eq_light_shadows));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_eq_midtones));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_eq_dark_highlights));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_eq_highlights));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_eq_whites));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_eq_speculars));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_eq_smoothing));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        pref = findPreference(getString(R.string.pref_tone_eq_feathering));
                        if (pref != null && pref instanceof SliderPreference) {
                            ((SliderPreference) pref).refresh();
                        }
                        return true;
                    });
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            // Update external LUT summary when fragment resumes
            // Use getResources().getString() to avoid Bundle access issues
            android.util.Log.d("Preferences", "onResume: updating LUT summary");
            if (mActivity != null && getResources() != null) {
                try {
                    String prefKey = getResources().getString(R.string.pref_external_lut_path);
                    android.util.Log.d("Preferences", "onResume: prefKey=" + prefKey);
                    Preference externalLutPref = findPreference(prefKey);
                    if (externalLutPref != null) {
                        android.util.Log.d("Preferences", "onResume: found preference, calling updateExternalLutSummary");
                        updateExternalLutSummary(externalLutPref);
                    } else {
                        android.util.Log.w("Preferences", "onResume: preference not found with key: " + prefKey);
                    }
                    // Update Clear LUT enabled state
                    String clearKey = getResources().getString(R.string.pref_external_lut_clear);
                    Preference externalLutClearPref = findPreference(clearKey);
                    if (externalLutClearPref != null) {
                        updateClearLutEnabled(externalLutClearPref);
                    }
                } catch (Exception e) {
                    android.util.Log.e("Preferences", "Failed to update LUT preferences in onResume", e);
                }
            } else {
                android.util.Log.w("Preferences", "onResume: mActivity or getResources() is null");
            }
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            
            // Add padding to account for the action bar
            if (mActivity != null) {
                int actionBarHeight = 0;
                
                // Try to get action bar height
                if (mActivity.getActionBar() != null) {
                    actionBarHeight = mActivity.getActionBar().getHeight();
                }
                
                // If action bar height is not available yet, get it from theme
                if (actionBarHeight == 0) {
                    android.util.TypedValue tv = new android.util.TypedValue();
                    if (mActivity.getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
                        actionBarHeight = android.util.TypedValue.complexToDimensionPixelSize(
                                tv.data, mActivity.getResources().getDisplayMetrics());
                    }
                }
                
                if (actionBarHeight > 0) {
                    // PreferenceFragment uses a ListView - find it in the view hierarchy
                    ListView listView = findListView(view);
                    if (listView != null) {
                        listView.setClipToPadding(false);
                        listView.setPadding(
                                listView.getPaddingLeft(),
                                listView.getPaddingTop() + actionBarHeight,
                                listView.getPaddingRight(),
                                listView.getPaddingBottom()
                        );
                    } else if (view instanceof ViewGroup) {
                        // Fallback: add padding to root view
                        ViewGroup rootView = (ViewGroup) view;
                        rootView.setPadding(
                                rootView.getPaddingLeft(),
                                rootView.getPaddingTop() + actionBarHeight,
                                rootView.getPaddingRight(),
                                rootView.getPaddingBottom()
                        );
                    }
                }
            }
        }
        
        private ListView findListView(View view) {
            if (view instanceof ListView) {
                return (ListView) view;
            } else if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    ListView found = findListView(group.getChildAt(i));
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }
        
        /**
         * Public method to refresh the external LUT summary.
         * Can be called from MainActivity after selecting a LUT file.
         * This method is safe to call even if the fragment isn't fully initialized.
         */
        public void refreshExternalLutSummary() {
            // Only update if fragment is attached and has an activity
            if (!isAdded() || mActivity == null) {
                return;
            }
            try {
                // Use the same approach as in onCreate - get the key from resources
                String prefKey = mActivity.getResources().getString(R.string.pref_external_lut_path);
                Preference externalLutPref = findPreference(prefKey);
                if (externalLutPref != null) {
                    updateExternalLutSummary(externalLutPref);
                } else {
                    android.util.Log.w("Preferences", "Could not find preference with key: " + prefKey);
                }
            } catch (Exception e) {
                android.util.Log.e("Preferences", "Failed to refresh LUT summary", e);
            }
        }
        
        private void updateExternalLutSummary(Preference pref) {
            if (pref == null || mActivity == null) {
                return;
            }
            // Read directly from SharedPreferences instead of using StringRef.get()
            // because StringRef.get() only returns cached value after load() is called
            android.content.SharedPreferences sharedPrefs = Utilities.prefs(mActivity);
            String prefKey = mActivity.getResources().getString(R.string.pref_external_lut_path);
            String lutPath = sharedPrefs.getString(prefKey, "");
            android.util.Log.d("Preferences", "updateExternalLutSummary: prefKey=" + prefKey + ", lutPath=" + lutPath);
            if (lutPath != null && !lutPath.isEmpty()) {
                String fileName = null;
                try {
                    // Try to get display name from ContentResolver directly
                    android.net.Uri uri = android.net.Uri.parse(lutPath);
                    android.database.Cursor cursor = mActivity.getContentResolver().query(
                        uri, null, null, null, null);
                    if (cursor != null) {
                        try {
                            if (cursor.moveToFirst()) {
                                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                                if (nameIndex >= 0) {
                                    fileName = cursor.getString(nameIndex);
                                }
                            }
                        } finally {
                            cursor.close();
                        }
                    }
                } catch (Exception e) {
                    // If ContentResolver query fails, try Path.getFileFromUri
                    try {
                        fileName = Path.getFileFromUri(mActivity, android.net.Uri.parse(lutPath));
                    } catch (Exception e2) {
                        // Ignore
                    }
                }
                
                // Fallback: if we got just a number (document ID like "446"), try to extract from URI path
                if (fileName == null || fileName.isEmpty() || (fileName.matches("\\d+") && !fileName.endsWith(".cube"))) {
                    // Extract from path/URI string
                    if (lutPath.contains("/")) {
                        String lastPart = lutPath.substring(lutPath.lastIndexOf('/') + 1);
                        // Remove query parameters
                        if (lastPart.contains("?")) {
                            lastPart = lastPart.substring(0, lastPart.indexOf('?'));
                        }
                        // If it's not just a number, use it
                        if (!lastPart.matches("\\d+")) {
                            fileName = lastPart;
                        }
                    }
                }
                
                // Final fallback - if still just a number, show generic name
                if (fileName == null || fileName.isEmpty() || (fileName.matches("\\d+") && !fileName.endsWith(".cube"))) {
                    fileName = "LUT file";
                }
                
                android.util.Log.d("Preferences", "Setting summary to: " + fileName);
                pref.setSummary(fileName);
            } else {
                if (mActivity != null && getResources() != null) {
                    String defaultDesc = getResources().getString(R.string.pref_external_lut_path_desc);
                    android.util.Log.d("Preferences", "Setting summary to default: " + defaultDesc);
                    pref.setSummary(defaultDesc);
                }
            }
            
            // Update Clear LUT enabled state when summary is updated
            if (mActivity != null && getResources() != null) {
                String clearKey = getResources().getString(R.string.pref_external_lut_clear);
                Preference clearLutPref = findPreference(clearKey);
                if (clearLutPref != null) {
                    updateClearLutEnabled(clearLutPref, lutPath);
                }
            }
        }
        
        private void updateClearLutEnabled(Preference clearLutPref) {
            if (clearLutPref == null || mActivity == null) {
                return;
            }
            // Read directly from SharedPreferences instead of using StringRef.get()
            android.content.SharedPreferences sharedPrefs = Utilities.prefs(mActivity);
            String prefKey = mActivity.getResources().getString(R.string.pref_external_lut_path);
            String lutPath = sharedPrefs.getString(prefKey, "");
            boolean hasLut = lutPath != null && !lutPath.isEmpty();
            clearLutPref.setEnabled(hasLut);
        }
        
        private void updateClearLutEnabled(Preference clearLutPref, String lutPath) {
            if (clearLutPref == null) {
                return;
            }
            boolean hasLut = lutPath != null && !lutPath.isEmpty();
            clearLutPref.setEnabled(hasLut);
        }
        
        private void updateMatModeOptionsEnabled(android.preference.ListPreference postProcessPref, Preference matModeOptionsPref) {
            if (postProcessPref == null || matModeOptionsPref == null) {
                return;
            }
            String currentValue = postProcessPref.getValue();
            boolean isMatMode = "MAT".equals(currentValue);
            matModeOptionsPref.setEnabled(isMatMode);
        }
    }

    public static PostProcessMode postProcess() {
        return PostProcessMode.valueOf(global().processMode.get());
    }
}
