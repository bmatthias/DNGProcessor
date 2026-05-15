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
 * Activity for editing the tone curve.
 * Provides a full-screen curve editor with save/reset buttons.
 */
public class ToneCurveActivity extends Activity {
    
    private static final String PREF_KEY = "user_tone_curve";
    private static final String PREF_ENABLED_KEY = "user_tone_curve_enabled";
    
    private ToneCurveView curveView;
    private SharedPreferences prefs;
    
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
        title.setText("Tone Curve");
        title.setTextSize(20);
        title.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleParams);
        titleBar.addView(title);
        
        root.addView(titleBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        
        // Instructions
        TextView instructions = new TextView(this);
        instructions.setText("Tap to add points • Drag to adjust • Endpoints cannot be removed");
        instructions.setTextSize(12);
        instructions.setTextColor(0xFF888888);
        instructions.setPadding(32, 16, 32, 16);
        root.addView(instructions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        
        // Curve view (takes up most of the screen)
        curveView = new ToneCurveView(this);
        LinearLayout.LayoutParams curveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        curveParams.setMargins(16, 16, 16, 16);
        root.addView(curveView, curveParams);
        
        // Channel selector (future: RGB/R/G/B channels)
        LinearLayout channelBar = new LinearLayout(this);
        channelBar.setOrientation(LinearLayout.HORIZONTAL);
        channelBar.setPadding(32, 8, 32, 8);
        channelBar.setBackgroundColor(0xFF2A2A30);
        
        String[] channels = {"RGB", "Red", "Green", "Blue"};
        for (int i = 0; i < channels.length; i++) {
            Button channelBtn = createChannelButton(channels[i], i == 0);
            channelBar.addView(channelBtn);
        }
        
        root.addView(channelBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        
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
        
        Button saveBtn = createButton("Save", 0xFF0066CC);
        saveBtn.setOnClickListener(v -> {
            saveCurve();
            finish();
        });
        buttonBar.addView(saveBtn);
        
        root.addView(buttonBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        
        setContentView(root);
        
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
    
    private Button createChannelButton(String text, boolean selected) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(selected ? 0xFFFFFFFF : 0xFF888888);
        btn.setBackgroundColor(selected ? 0xFF0066CC : 0xFF3A3A40);
        btn.setPadding(32, 16, 32, 16);
        btn.setAllCaps(false);
        btn.setTextSize(12);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(4, 0, 4, 0);
        btn.setLayoutParams(params);
        
        // TODO: Implement per-channel curves
        btn.setOnClickListener(v -> {
            // For now, only RGB is functional
        });
        
        return btn;
    }
    
    private void loadCurve() {
        String curveData = prefs.getString(PREF_KEY, null);
        if (curveData != null && !curveData.isEmpty()) {
            try {
                // Use semicolon as separator to avoid locale issues with decimal commas
                String[] parts = curveData.split(";");
                float[] points = new float[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    points[i] = Float.parseFloat(parts[i].trim());
                }
                curveView.setCurvePoints(points);
            } catch (Exception e) {
                curveView.resetCurve();
            }
        }
    }
    
    private void saveCurve() {
        float[] points = curveView.getCurvePoints();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < points.length; i++) {
            if (i > 0) sb.append(";");
            // Use Locale.US to ensure decimal point (not comma) is used
            sb.append(String.format(Locale.US, "%.4f", points[i]));
        }
        
        prefs.edit()
                .putString(PREF_KEY, sb.toString())
                .putBoolean(PREF_ENABLED_KEY, true)
                .apply();
    }
    
    /**
     * Static helper to get the saved curve points.
     * @return float array of [x0, y0, x1, y1, ...] or null if no custom curve
     */
    public static float[] getSavedCurvePoints(SharedPreferences prefs) {
        if (!prefs.getBoolean(PREF_ENABLED_KEY, false)) {
            return null;
        }
        
        String curveData = prefs.getString(PREF_KEY, null);
        if (curveData == null || curveData.isEmpty()) {
            return null;
        }
        
        try {
            // Use semicolon as separator to avoid locale issues with decimal commas
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
    
    /**
     * Check if a user-defined tone curve is enabled.
     */
    public static boolean isUserCurveEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_ENABLED_KEY, false);
    }
    
    /**
     * Disable the user tone curve.
     */
    public static void disableUserCurve(SharedPreferences prefs) {
        prefs.edit().putBoolean(PREF_ENABLED_KEY, false).apply();
    }
}

