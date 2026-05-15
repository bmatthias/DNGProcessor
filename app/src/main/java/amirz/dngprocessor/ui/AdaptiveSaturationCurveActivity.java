package amirz.dngprocessor.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import amirz.dngprocessor.R;
import amirz.dngprocessor.util.Utilities;

/**
 * Activity for editing the adaptive saturation curve.
 * Provides a full-screen curve editor with save/reset buttons.
 * 
 * The curve maps Light Value (X-axis: -4 to 16) to adaptive saturation strength (Y-axis: 0 to 5).
 * This allows the app to apply different levels of saturation enhancement based on
 * the scene's lighting conditions:
 * - Indoor scenes (low LV) can have lower saturation to avoid oversaturation
 * - Outdoor scenes (high LV) can have higher saturation for vibrant colors
 */
public class AdaptiveSaturationCurveActivity extends Activity {
    
    private static final String PREF_KEY_MULTIPLIER_A = "adaptive_vibrance_curve_multiplier_a";
    private static final String PREF_KEY_MULTIPLIER_B = "adaptive_vibrance_curve_multiplier_b";
    
    // Legacy key for backward compatibility
    private static final String PREF_KEY = "adaptive_saturation_curve";
    
    private AdaptiveSaturationCurveView curveView;
    private SharedPreferences prefs;
    private AdaptiveSaturationCurveView.CurveType currentCurveType = AdaptiveSaturationCurveView.CurveType.MULTIPLIER_A;
    private TextView hintTextView;
    private Button multABtn, multBBtn;  // Store button references
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Full screen, no title
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        
        prefs = Utilities.prefs(this);
        
        // Build the layout programmatically
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1E1E23);
        root.setPadding(0, 0, 0, 0);
        
        // Title bar
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setPadding(32, 24, 32, 24);
        titleBar.setBackgroundColor(0xFF2A2A30);
        
        TextView title = new TextView(this);
        title.setText("Adaptive Vibrance Curves");
        title.setTextSize(20);
        title.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        titleBar.addView(title);
        
        // Curve type selector buttons (only two curves: multiplier A and B)
        LinearLayout buttonGroup = new LinearLayout(this);
        buttonGroup.setOrientation(LinearLayout.HORIZONTAL);
        
        multABtn = createTabButton("Mult A", AdaptiveSaturationCurveView.CurveType.MULTIPLIER_A);
        multBBtn = createTabButton("Mult B", AdaptiveSaturationCurveView.CurveType.MULTIPLIER_B);
        
        buttonGroup.addView(multABtn);
        buttonGroup.addView(multBBtn);
        
        titleBar.addView(buttonGroup);
        
        root.addView(titleBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        
        // Instructions
        TextView instructions = new TextView(this);
        instructions.setText("Tap to add points • Drag to adjust • X-axis: -4 to 16");
        instructions.setTextSize(12);
        instructions.setTextColor(0xFF888888);
        instructions.setPadding(32, 16, 32, 8);
        root.addView(instructions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        
        // Additional hint (dynamic based on curve type)
        hintTextView = new TextView(this);
        updateHintText(hintTextView);
        hintTextView.setTextSize(11);
        hintTextView.setTextColor(0xFF666666);
        hintTextView.setPadding(32, 0, 32, 16);
        root.addView(hintTextView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        
        // Curve view (takes up most of the screen)
        curveView = new AdaptiveSaturationCurveView(this);
        curveView.setCurveType(currentCurveType);
        LinearLayout.LayoutParams curveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        curveParams.setMargins(16, 16, 16, 16);
        root.addView(curveView, curveParams);
        
        // Button bar
        LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setPadding(32, 24, 32, 48);
        buttonBar.setBackgroundColor(0xFF2A2A30);
        
        Button resetBtn = createButton("Reset", 0xFF666666);
        resetBtn.setOnClickListener(v -> {
            curveView.resetCurve();
        });
        buttonBar.addView(resetBtn);
        
        // Spacer
        View spacer = new View(this);
        buttonBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        
        Button cancelBtn = createButton("Cancel", 0xFF444444);
        cancelBtn.setOnClickListener(v -> {
            finish();
        });
        buttonBar.addView(cancelBtn);
        
        View spacer2 = new View(this);
        spacer2.setLayoutParams(new LinearLayout.LayoutParams(16, 1));
        buttonBar.addView(spacer2);
        
        Button saveBtn = createButton("Save", 0xFFCC6600);  // Orange to match curve
        saveBtn.setOnClickListener(v -> {
            saveCurve();
            finish();
        });
        buttonBar.addView(saveBtn);
        
        root.addView(buttonBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        
        setContentView(root);
        
        // Update tab button colors to show initial selection
        updateTabButtons();
        
        // Load existing curve
        loadCurve();
    }
    
    private Button createButton(String text, int backgroundColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setBackgroundColor(backgroundColor);
        btn.setPadding(48, 24, 48, 24);
        btn.setAllCaps(false);
        btn.setTextSize(14);
        return btn;
    }
    
    private Button createTabButton(String text, AdaptiveSaturationCurveView.CurveType type) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setBackgroundColor(0xFF444444);
        btn.setPadding(24, 12, 24, 12);
        btn.setAllCaps(false);
        btn.setTextSize(12);
        btn.setOnClickListener(v -> {
            switchCurveType(type);
            // Update button colors
            updateTabButtons();
        });
        btn.setTag(type);
        return btn;
    }
    
    private void switchCurveType(AdaptiveSaturationCurveView.CurveType type) {
        // Save current curve
        saveCurrentCurve();
        
        // Switch to new curve type
        currentCurveType = type;
        curveView.setCurveType(type);
        
        // Load curve for new type
        loadCurve();
        
        // Update hint text
        updateHintText(hintTextView);
    }
    
    private void updateTabButtons() {
        // Update button colors using stored references
        if (multABtn != null && multBBtn != null) {
            multABtn.setBackgroundColor(
                    currentCurveType == AdaptiveSaturationCurveView.CurveType.MULTIPLIER_A ? 
                    0xFFCC6600 : 0xFF444444);
            multBBtn.setBackgroundColor(
                    currentCurveType == AdaptiveSaturationCurveView.CurveType.MULTIPLIER_B ? 
                    0xFFCC6600 : 0xFF444444);
        }
    }
    
    private void updateHintText(TextView hint) {
        if (hint == null) return;
        String hintText;
        switch (currentCurveType) {
            case MULTIPLIER_A:
                hintText = "Multiplier A: scales result based on baseline exposure EV (identity by default)";
                break;
            case MULTIPLIER_B:
                hintText = "Multiplier B: scales result based on light value (identity by default)";
                break;
            default:
                hintText = "";
        }
        hint.setText(hintText);
    }
    
    private void loadCurve() {
        String prefKey = getPrefKeyForCurveType(currentCurveType);
        String curveData = prefs.getString(prefKey, null);
        if (curveData != null && !curveData.isEmpty()) {
            try {
                // Use semicolon as separator to avoid locale issues with decimal commas
                String[] parts = curveData.split(";");
                float[] points = new float[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    points[i] = Float.parseFloat(parts[i].trim());
                }
                // Validate: should have at least 4 values (2 points: x, y, x, y)
                // Check if it looks like old format (y values 0 and 2 instead of 1 and 1)
                if (points.length >= 4) {
                    // Check if this is old format data (y values are 0 and 2)
                    // If so, reset to identity
                    boolean isOldFormat = false;
                    for (int i = 1; i < points.length; i += 2) {
                        if (i < points.length && (points[i] == 0.0f || points[i] == 2.0f)) {
                            // Might be old format, but check if it's actually the identity
                            if (points.length == 4 && points[1] == 0.0f && points[3] == 2.0f) {
                                isOldFormat = true;
                                break;
                            }
                        }
                    }
                    if (isOldFormat) {
                        // Old format detected, reset to identity
                        curveView.resetCurve();
                    } else {
                        curveView.setCurvePoints(points);
                    }
                } else {
                    curveView.resetCurve();
                }
            } catch (Exception e) {
                curveView.resetCurve();
            }
        } else {
            // Try legacy key for backward compatibility
            if (currentCurveType == AdaptiveSaturationCurveView.CurveType.MULTIPLIER_B) {
                curveData = prefs.getString(PREF_KEY, null);
                if (curveData != null && !curveData.isEmpty()) {
                    try {
                        String[] parts = curveData.split(";");
                        float[] points = new float[parts.length];
                        for (int i = 0; i < parts.length; i++) {
                            points[i] = Float.parseFloat(parts[i].trim());
                        }
                        // Check for old format
                        if (points.length >= 4 && points.length == 4 && 
                            points[0] == -4f && points[1] == 0.0f && 
                            points[2] == 16f && points[3] == 2.0f) {
                            // Old format detected, reset to identity
                            curveView.resetCurve();
                        } else {
                            curveView.setCurvePoints(points);
                        }
                    } catch (Exception e) {
                        curveView.resetCurve();
                    }
                } else {
                    curveView.resetCurve();
                }
            } else {
                curveView.resetCurve();
            }
        }
    }
    
    private void saveCurrentCurve() {
        float[] points = curveView.getCurvePoints();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < points.length; i++) {
            if (i > 0) sb.append(";");
            // Use Locale.US to ensure decimal point (not comma) is used
            sb.append(String.format(Locale.US, "%.4f", points[i]));
        }
        
        String prefKey = getPrefKeyForCurveType(currentCurveType);
        prefs.edit()
                .putString(prefKey, sb.toString())
                .apply();
    }
    
    private void saveCurve() {
        saveCurrentCurve();
    }
    
    private String getPrefKeyForCurveType(AdaptiveSaturationCurveView.CurveType type) {
        switch (type) {
            case MULTIPLIER_A:
                return PREF_KEY_MULTIPLIER_A;
            case MULTIPLIER_B:
                return PREF_KEY_MULTIPLIER_B;
            default:
                return PREF_KEY_MULTIPLIER_B;
        }
    }
    
    
    /**
     * Get the default curve points (identity: multiplier = 1.0 for all x).
     * Identity means no scaling - multiplier stays constant at 1.0.
     * @return float array of [x0, y0, x1, y1, ...]
     */
    public static float[] getDefaultCurvePoints(AdaptiveSaturationCurveView.CurveType type) {
        // Identity: multiplier = 1.0 (flat line)
        // At x=-4: multiplier = 1.0
        // At x=16: multiplier = 1.0
        return new float[] {
            -4f, 1.0f,   // x=-4 → multiplier=1.0
            16f, 1.0f    // x=16 → multiplier=1.0
        };
    }
    
    /**
     * Look up the multiplier A for a given Baseline Exposure EV.
     * @param prefs SharedPreferences to read curve from
     * @param baselineEV Baseline Exposure in EV (typically -4 to 16)
     * @return Multiplier value
     */
    public static float lookupMultiplierA(SharedPreferences prefs, float baselineEV) {
        return lookupCurve(prefs, PREF_KEY_MULTIPLIER_A, AdaptiveSaturationCurveView.CurveType.MULTIPLIER_A, baselineEV);
    }
    
    /**
     * Look up the multiplier B for a given Light Value.
     * @param prefs SharedPreferences to read curve from
     * @param lv Light Value (typically -4 to 16)
     * @return Multiplier value
     */
    public static float lookupMultiplierB(SharedPreferences prefs, float lv) {
        return lookupCurve(prefs, PREF_KEY_MULTIPLIER_B, AdaptiveSaturationCurveView.CurveType.MULTIPLIER_B, lv);
    }
    
    private static float lookupCurve(SharedPreferences prefs, String prefKey, AdaptiveSaturationCurveView.CurveType type, float x) {
        float[] points = getSavedCurvePoints(prefs, prefKey);
        if (points == null || points.length < 4) {
            // Try legacy key for multiplier B
            if (type == AdaptiveSaturationCurveView.CurveType.MULTIPLIER_B) {
                points = getSavedCurvePoints(prefs, PREF_KEY);
            }
            if (points == null || points.length < 4) {
                points = getDefaultCurvePoints(type);
            }
        }
        
        // Clamp x to valid range
        x = Math.max(AdaptiveSaturationCurveView.MIN_X, Math.min(AdaptiveSaturationCurveView.MAX_X, x));
        
        // Find the two control points to interpolate between
        int n = points.length / 2;
        
        // If x is before first point, return first point's value
        if (x <= points[0]) {
            return points[1];
        }
        
        // If x is after last point, return last point's value
        if (x >= points[(n - 1) * 2]) {
            return points[(n - 1) * 2 + 1];
        }
        
        // Find the segment containing x
        for (int i = 0; i < n - 1; i++) {
            float x1 = points[i * 2];
            float y1 = points[i * 2 + 1];
            float x2 = points[(i + 1) * 2];
            float y2 = points[(i + 1) * 2 + 1];
            
            if (x >= x1 && x <= x2) {
                // Linear interpolation
                float t = (x - x1) / (x2 - x1);
                return y1 + t * (y2 - y1);
            }
        }
        
        // Fallback: identity (multiplier = 1.0)
        return 1.0f;
    }
    
    public static float[] getSavedCurvePoints(SharedPreferences prefs, String prefKey) {
        String curveData = prefs.getString(prefKey, null);
        if (curveData == null || curveData.isEmpty()) {
            return null;
        }
        
        try {
            String[] parts = curveData.split(";");
            float[] points = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                points[i] = Float.parseFloat(parts[i].trim());
            }
            return points;
        } catch (Exception e) {
            return null;
        }
    }
    
    // Legacy methods for backward compatibility
    public static float[] getDefaultCurvePoints() {
        return getDefaultCurvePoints(AdaptiveSaturationCurveView.CurveType.MULTIPLIER_B);
    }
    
    public static float[] getSavedCurvePoints(SharedPreferences prefs) {
        return getSavedCurvePoints(prefs, PREF_KEY_MULTIPLIER_B);
    }
    
    public static float lookupStrength(SharedPreferences prefs, float lv) {
        return lookupMultiplierB(prefs, lv);
    }
}
