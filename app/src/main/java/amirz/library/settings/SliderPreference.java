package amirz.library.settings;

import android.content.Context;
import android.content.res.TypedArray;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import amirz.dngprocessor.R;

/**
 * A Preference that displays a SeekBar slider for float values.
 * Supports configurable min, max, and step values.
 */
public class SliderPreference extends Preference implements SeekBar.OnSeekBarChangeListener {
    
    private float mMinValue = -100f;
    private float mMaxValue = 100f;
    private float mStep = 1f;
    private int mDecimalPlaces = 0;
    private float mCurrentValue = 0f;
    
    private SeekBar mSeekBar;
    private TextView mValueText;
    
    public SliderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        
        // Read custom attributes
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SliderPreference);
        mMinValue = a.getFloat(R.styleable.SliderPreference_minValue, -100f);
        mMaxValue = a.getFloat(R.styleable.SliderPreference_maxValue, 100f);
        mStep = a.getFloat(R.styleable.SliderPreference_stepValue, 1f);
        mDecimalPlaces = a.getInt(R.styleable.SliderPreference_decimalPlaces, 0);
        a.recycle();
        
        setLayoutResource(R.layout.preference_slider);
    }
    
    private String mDefaultValue;
    
    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        
        // Re-read value from SharedPreferences in case it was changed externally
        String key = getKey();
        if (key != null) {
            android.content.SharedPreferences prefs = getSharedPreferences();
            String defaultVal = mDefaultValue != null ? mDefaultValue : "0";
            String stored = GlobalPreferences.getStringSafe(prefs, key, defaultVal);
            mCurrentValue = Float.parseFloat(stored);
        }
        
        mSeekBar = view.findViewById(R.id.seekbar);
        mValueText = view.findViewById(R.id.value_text);
        
        if (mSeekBar != null) {
            // Calculate the number of steps
            int steps = Math.round((mMaxValue - mMinValue) / mStep);
            mSeekBar.setMax(steps);
            
            // Set current progress
            int progress = Math.round((mCurrentValue - mMinValue) / mStep);
            mSeekBar.setProgress(progress);
            
            mSeekBar.setOnSeekBarChangeListener(this);
        }
        
        updateValueText();
    }
    
    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return a.getString(index);
    }
    
    @Override
    protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue) {
        if (defaultValue != null) {
            mDefaultValue = defaultValue.toString();
        }
        if (restorePersistedValue) {
            // Use the helper method to safely read preference value
            // This handles String, Float, Integer, and Long types gracefully
            String key = getKey();
            if (key != null) {
                android.content.SharedPreferences prefs = getSharedPreferences();
                String defaultVal = mDefaultValue != null ? mDefaultValue : "0";
                String stored = GlobalPreferences.getStringSafe(prefs, key, defaultVal);
                mCurrentValue = Float.parseFloat(stored);
            } else {
                mCurrentValue = 0f;
            }
        } else if (defaultValue != null) {
            mCurrentValue = Float.parseFloat(defaultValue.toString());
        }
    }
    
    private void updateValueText() {
        if (mValueText != null) {
            String format = "%." + mDecimalPlaces + "f";
            String valueStr = String.format(format, mCurrentValue);
            if (mCurrentValue > 0 && mDecimalPlaces == 0) {
                valueStr = "+" + valueStr;
            } else if (mCurrentValue > 0 && mDecimalPlaces > 0) {
                valueStr = "+" + valueStr;
            }
            mValueText.setText(valueStr);
        }
    }
    
    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {
            mCurrentValue = mMinValue + (progress * mStep);
            // Round to avoid floating point errors
            float factor = (float) Math.pow(10, mDecimalPlaces);
            mCurrentValue = Math.round(mCurrentValue * factor) / factor;
            updateValueText();
        }
    }
    
    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        // Not needed
    }
    
    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        // Save the value when user stops dragging
        persistString(String.valueOf(mCurrentValue));
        callChangeListener(mCurrentValue);
    }
    
    /**
     * Public method to refresh the preference display.
     * Re-reads the value from SharedPreferences and updates the UI.
     */
    public void refresh() {
        notifyChanged();
    }
}

