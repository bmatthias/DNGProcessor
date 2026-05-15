package amirz.library.settings;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Class that provides synchronized preferences using the singleton design pattern.
 * Extensions should add a static getInstance() method.
 */
public abstract class GlobalPreferences {
    private static final String TAG = "GlobalPreferences";
    private final SparseArray<Ref> mPreferences = new SparseArray<>();

    /**
     * Loads all preferences from the SharedPreferences instance.
     * @param prefs Instance from which the data is pulled.
     * @param res Resources used to deserialize the default values as fallback values.
     */
    public void applyAll(SharedPreferences prefs, Resources res) {
        // IMPORTANT: Reset all cached values BEFORE loading to prevent stale data
        // from persisting if any load() call fails. This ensures that if we process
        // multiple images in the same app session, we never accidentally use values
        // from a previous image's processing run.
        for (int i = 0; i < mPreferences.size(); i++) {
            mPreferences.valueAt(i).value = null;
        }
        
        int loadedCount = 0;
        int failedCount = 0;
        for (int i = 0; i < mPreferences.size(); i++) {
            try {
                // noinspection unchecked
                apply(prefs, res, mPreferences.valueAt(i));
                loadedCount++;
            } catch (Exception e) {
                failedCount++;
                String key = "unknown";
                try {
                    key = res.getString(mPreferences.valueAt(i).settingId);
                } catch (Exception ignored) {}
                Log.e(TAG, "Failed to load preference: " + key, e);
            }
        }
        
        if (failedCount > 0) {
            Log.w(TAG, "Preferences loaded: " + loadedCount + " succeeded, " + failedCount + " failed");
        } else {
            Log.d(TAG, "All " + loadedCount + " preferences loaded successfully");
        }
    }

    /**
     * Loads one key's preference from the SharedPreferences instance.
     * @param prefs Instance from which the data is pulled.
     * @param res Resources used to deserialize the default value as a fallback value.
     * @param tunable Reference to preference.
     * @return New value of the preference.
     */
    public <T> T apply(SharedPreferences prefs, Resources res, Ref<T> tunable) {
        String key = res.getString(tunable.settingId);
        TypedValue defaultValue = new TypedValue();
        res.getValue(tunable.defaultId, defaultValue, true);

        Log.d(TAG, "Updating " + key);
        tunable.load(prefs, key, defaultValue, res);
        return tunable.get();
    }

    /**
     * Safely reads a preference value as a String, handling type conversion.
     * This helper gracefully handles cases where a preference was stored as Float or Integer
     * but should now be read as String (e.g., after migration).
     * 
     * @param prefs SharedPreferences instance
     * @param key Preference key
     * @param defaultValue Default value to return if key doesn't exist
     * @return String representation of the preference value
     */
    public static String getStringSafe(SharedPreferences prefs, String key, String defaultValue) {
        if (!prefs.contains(key)) {
            return defaultValue;
        }
        
        try {
            // Try to read as String first (expected format)
            return prefs.getString(key, defaultValue);
        } catch (ClassCastException e) {
            // Value is stored as a different type - convert it
            try {
                // Try Boolean first (common migration case)
                Boolean boolValue = prefs.getBoolean(key, false);
                // Special handling for baseline_exposure_compression: convert boolean to "0" (None)
                // This is the current default - don't try to preserve old boolean meaning
                String result;
                if ("pref_baseline_exposure_compression".equals(key)) {
                    result = "0";  // Always use current default
                    Log.w(TAG, "Converting boolean baseline_exposure_compression to String \"0\"");
                } else {
                    result = String.valueOf(boolValue);
                }
                // Migrate to String format for future reads
                prefs.edit().putString(key, result).apply();
                return result;
            } catch (ClassCastException e1) {
                try {
                    // Try Float
                    Float floatValue = prefs.getFloat(key, Float.NaN);
                    if (!floatValue.isNaN()) {
                        String result = String.valueOf(floatValue);
                        // Migrate to String format for future reads
                        prefs.edit().putString(key, result).apply();
                        return result;
                    }
                } catch (ClassCastException e2) {
                    try {
                        // Try Integer
                        Integer intValue = prefs.getInt(key, Integer.MIN_VALUE);
                        if (intValue != Integer.MIN_VALUE) {
                            String result = String.valueOf(intValue);
                            // Migrate to String format for future reads
                            prefs.edit().putString(key, result).apply();
                            return result;
                        }
                    } catch (ClassCastException e3) {
                        // Try Long
                        try {
                            Long longValue = prefs.getLong(key, Long.MIN_VALUE);
                            if (longValue != Long.MIN_VALUE) {
                                String result = String.valueOf(longValue);
                                // Migrate to String format for future reads
                                prefs.edit().putString(key, result).apply();
                                return result;
                            }
                        } catch (ClassCastException e4) {
                            // Unknown type - return default
                            Log.w(TAG, "Unknown preference type for key: " + key);
                        }
                    }
                }
            }
            return defaultValue;
        }
    }

    public final class ResetContext implements AutoCloseable {
        private final SharedPreferences mPrefs;
        private final Resources mRes;
        private final List<String> mReset = new ArrayList<>();

        private ResetContext(SharedPreferences prefs, Resources res) {
            mPrefs = prefs;
            mRes = res;
        }

        public <T> void reset(Ref<T> tunable) {
            mReset.add(mRes.getString(tunable.settingId));
        }

        @Override
        public void close() {
            SharedPreferences.Editor edit = mPrefs.edit();
            for (String reset : mReset) {
                edit.remove(reset);
            }
            edit.apply();
            GlobalPreferences.this.applyAll(mPrefs, mRes);
        }
    }

    public interface ResetContextFunc {
        void onReset(ResetContext ctx);
    }

    public void reset(SharedPreferences prefs, Resources res, ResetContextFunc todo) {
        try (ResetContext ctx = new ResetContext(prefs, res)) {
            todo.onReset(ctx);
        }
    }

   /**
    * Referenced setting that holds a boolean.
    */
    public final class BooleanRef extends Ref<Boolean> {
        public BooleanRef(int settingId, int defaultId) {
            super(settingId, defaultId);
        }

        @Override
        void load(SharedPreferences prefs, String key, TypedValue defaultValue, Resources res) {
           value = prefs.getBoolean(key, defaultValue.data == 1);
        }
    }

    /**
     * Referenced setting that holds a floating point number.
     */
    public final class FloatRef extends Ref<Float> {
        public FloatRef(int settingId, int defaultId) {
            super(settingId, defaultId);
        }

        @Override
        void load(SharedPreferences prefs, String key, TypedValue defaultValue, Resources res) {
            // Extract float value from TypedValue
            // For type="integer" format="float", use Resources.getFloat() which handles it correctly
            float defaultFloat;
            try {
                // Use Resources.getFloat() - this correctly handles type="integer" format="float"
                defaultFloat = res.getFloat(defaultId);
            } catch (Resources.NotFoundException e) {
                // Fallback: try getFloat() on TypedValue if it's TYPE_FLOAT
                try {
                    if (defaultValue.type == TypedValue.TYPE_FLOAT) {
                        defaultFloat = defaultValue.getFloat();
                    } else {
                        // Last resort: parse from string representation
                        String defaultString = defaultValue.coerceToString().toString();
                        defaultFloat = Float.parseFloat(defaultString);
                    }
                } catch (Exception e2) {
                    Log.w(TAG, "Failed to parse float default for key: " + key + ", using 0.0", e2);
                    defaultFloat = 0.0f;
                }
            }
            
            String stringValue = getStringSafe(prefs, key, String.valueOf(defaultFloat));
            value = Float.valueOf(stringValue);
        }
    }

    /**
     * Referenced setting that holds an integer.
     */
    public final class IntegerRef extends Ref<Integer> {
        public IntegerRef(int settingId, int defaultId) {
            super(settingId, defaultId);
        }

        @Override
        void load(SharedPreferences prefs, String key, TypedValue defaultValue, Resources res) {
            String defaultString = defaultValue.coerceToString().toString();
            String stringValue = getStringSafe(prefs, key, defaultString);
            try {
                // Try parsing as integer first
                value = Integer.valueOf(stringValue);
            } catch (NumberFormatException e) {
                // If parsing as integer fails (e.g., "95.0"), try parsing as float first
                try {
                    value = (int) Float.parseFloat(stringValue);
                    // Migrate to integer format for future reads
                    prefs.edit().putString(key, String.valueOf(value)).apply();
                } catch (NumberFormatException e2) {
                    // If both fail, try parsing default value
                    try {
                        value = Integer.valueOf(defaultString);
                    } catch (NumberFormatException e3) {
                        value = (int) Float.parseFloat(defaultString);
                    }
                }
            }
        }
    }

    /**
     * Referenced setting that holds a string.
     */
    public final class StringRef extends Ref<String> {
        public StringRef(int settingId, int entries) {
            super(settingId, entries);
        }

        @Override
        void load(SharedPreferences prefs, String key, TypedValue defaultValue, Resources res) {
            String defaultString = defaultValue.coerceToString().toString();
            // Use safe loading to handle type conversions (e.g., boolean to string migration)
            value = getStringSafe(prefs, key, defaultString);
        }
    }

    private abstract class Ref<T> {
        T value;
        final int settingId;
        final int defaultId;

        Ref(int settingId, int defaultId) {
            this.settingId = settingId;
            this.defaultId = defaultId;
            mPreferences.append(settingId, this);
        }

        public T get() {
            if (value == null) {
                // This can happen if:
                // 1. applyAll() was never called
                // 2. applyAll() was called but this preference failed to load
                // 3. The preference legitimately has a null value (rare for our use cases)
                Log.w(TAG, "Preference accessed before loading or failed to load (settingId=" + settingId + ")");
            }
            return value;
        }

        abstract void load(SharedPreferences prefs, String key, TypedValue defaultValue, Resources res);
    }

    /**
     * Empty constructor that prevents direct instantiation of this class.
     */
    protected GlobalPreferences() {
    }
}
