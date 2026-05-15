package amirz.dngprocessor.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Custom view for editing an adaptive saturation curve based on Light Value.
 * X-axis: Light Value (LV -4 to 16, covering dark indoor to bright outdoor scenes)
 * Y-axis: Adaptive saturation strength (0 to 5)
 * 
 * Uses cubic spline interpolation for smooth curves.
 * 
 * Typical Light Values:
 *   -4 to 0: Very dark indoor scenes (candlelight, night)
 *   1 to 6:  Indoor lighting (home, office)
 *   7 to 10: Overcast outdoor, bright indoor
 *   11 to 14: Sunny outdoor
 *   15 to 16: Bright snow, beach
 */
public class AdaptiveSaturationCurveView extends View {
    
    private static final int GRID_LINES_X = 5;  // For LV: -4, 0, 5, 10, 15
    private static final int GRID_LINES_Y = 5;  // For strength: 0, 1, 2, 3, 4, 5
    private static final float CONTROL_POINT_RADIUS = 24f;
    private static final float TOUCH_TOLERANCE = 48f;
    
    // X-axis range: -4 to 16 (for both lightValue and baselineExposure EV)
    public static final float MIN_X = -4f;
    public static final float MAX_X = 16f;
    
    // Legacy constants for backward compatibility
    public static final float MIN_LV = MIN_X;
    public static final float MAX_LV = MAX_X;
    
    // Offset range: -10 to +10 (for offset curve)
    public static final float MIN_OFFSET = -10f;
    public static final float MAX_OFFSET = 10f;
    
    // Multiplier range: 0 to 2 (for multiplier curves A and B)
    public static final float MIN_MULTIPLIER = 0f;
    public static final float MAX_MULTIPLIER = 2f;
    
    // Legacy constants for backward compatibility
    public static final float MIN_STRENGTH = MIN_MULTIPLIER;
    public static final float MAX_STRENGTH = MAX_MULTIPLIER;
    
    // Curve type enum
    public enum CurveType {
        MULTIPLIER_A, // Multiplier A: baselineExposure → multiplier
        MULTIPLIER_B  // Multiplier B: lightValue → multiplier
    }
    
    private CurveType mCurveType = CurveType.MULTIPLIER_A;
    
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint diagonalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curvePath = new Path();
    
    // Control points in normalized coordinates (0-1 for both axes)
    private final List<PointF> controlPoints = new ArrayList<>();
    private int selectedPointIndex = -1;
    
    // Callback for curve changes
    private OnCurveChangedListener curveChangedListener;
    
    public interface OnCurveChangedListener {
        void onCurveChanged(float[] curvePoints);
    }
    
    public AdaptiveSaturationCurveView(Context context) {
        super(context);
        init();
    }
    
    public AdaptiveSaturationCurveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public AdaptiveSaturationCurveView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        // Grid paint
        gridPaint.setColor(Color.argb(60, 255, 255, 255));
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);
        
        // Zone paint (for indoor/outdoor indicators)
        zonePaint.setColor(Color.argb(20, 255, 255, 255));
        zonePaint.setStyle(Paint.Style.FILL);
        
        // Diagonal reference line (disabled for this view - doesn't make sense for LV vs strength)
        diagonalPaint.setColor(Color.argb(40, 255, 255, 255));
        diagonalPaint.setStrokeWidth(1f);
        diagonalPaint.setStyle(Paint.Style.STROKE);
        
        // Curve paint
        curvePaint.setColor(Color.rgb(255, 180, 50));  // Orange for saturation
        curvePaint.setStrokeWidth(3f);
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        curvePaint.setStrokeJoin(Paint.Join.ROUND);
        
        // Control point fill
        pointPaint.setColor(Color.WHITE);
        pointPaint.setStyle(Paint.Style.FILL);
        
        // Control point stroke
        pointStrokePaint.setColor(Color.argb(180, 255, 150, 0));
        pointStrokePaint.setStrokeWidth(3f);
        pointStrokePaint.setStyle(Paint.Style.STROKE);
        
        // Label paint
        labelPaint.setColor(Color.argb(150, 255, 255, 255));
        labelPaint.setTextSize(28f);
        labelPaint.setAntiAlias(true);
        
        // Initialize with default curve
        resetCurve();
    }
    
    /**
     * Set the curve type (OFFSET, MULTIPLIER_A, or MULTIPLIER_B)
     */
    public void setCurveType(CurveType type) {
        mCurveType = type;
        resetCurve();
    }
    
    /**
     * Get the current curve type
     */
    public CurveType getCurveType() {
        return mCurveType;
    }
    
    /**
     * Reset the curve to identity (multiplier = 1.0 for all x values).
     * Identity means no scaling: multiplier stays at 1.0 regardless of input.
     */
    public void resetCurve() {
        controlPoints.clear();
        
        // Default: identity (flat line at multiplier = 1.0)
        // This means no scaling - multiplier is always 1.0
        controlPoints.add(new PointF(xToNorm(-4f), multiplierToNorm(1.0f)));  // x=-4 → mult=1.0
        controlPoints.add(new PointF(xToNorm(16f), multiplierToNorm(1.0f)));   // x=16 → mult=1.0
        
        invalidate();
        notifyCurveChanged();
    }
    
    /**
     * Convert X value to normalized 0-1 range
     */
    private float xToNorm(float x) {
        return (x - MIN_X) / (MAX_X - MIN_X);
    }
    
    /**
     * Convert normalized 0-1 to X value
     */
    private float normToX(float norm) {
        return MIN_X + norm * (MAX_X - MIN_X);
    }
    
    /**
     * Convert offset to normalized 0-1 range
     */
    private float offsetToNorm(float offset) {
        return (offset - MIN_OFFSET) / (MAX_OFFSET - MIN_OFFSET);
    }
    
    /**
     * Convert normalized 0-1 to offset
     */
    private float normToOffset(float norm) {
        return MIN_OFFSET + norm * (MAX_OFFSET - MIN_OFFSET);
    }
    
    /**
     * Convert multiplier to normalized 0-1 range
     */
    private float multiplierToNorm(float multiplier) {
        return (multiplier - MIN_MULTIPLIER) / (MAX_MULTIPLIER - MIN_MULTIPLIER);
    }
    
    /**
     * Convert normalized 0-1 to multiplier
     */
    private float normToMultiplier(float norm) {
        return MIN_MULTIPLIER + norm * (MAX_MULTIPLIER - MIN_MULTIPLIER);
    }
    
    // Legacy methods for backward compatibility
    private float lvToNorm(float lv) {
        return xToNorm(lv);
    }
    
    private float normToLv(float norm) {
        return normToX(norm);
    }
    
    private float strengthToNorm(float strength) {
        return multiplierToNorm(strength);
    }
    
    private float normToStrength(float norm) {
        return normToMultiplier(norm);
    }
    
    /**
     * Set the control points from an array of [x0, y0, x1, y1, ...] values.
     * Note: Values are in actual units, not normalized.
     */
    public void setCurvePoints(float[] points) {
        controlPoints.clear();
        for (int i = 0; i < points.length - 1; i += 2) {
            float x = points[i];
            float y = points[i + 1];
            float normX = xToNorm(x);
            float normY = multiplierToNorm(y);
            controlPoints.add(new PointF(normX, normY));
        }
        if (controlPoints.isEmpty()) {
            resetCurve();
        } else {
            sortControlPoints();
            invalidate();
        }
    }
    
    /**
     * Get the current control points as a flat array [x0, y0, x1, y1, ...].
     * Note: Values are in actual units, not normalized.
     */
    public float[] getCurvePoints() {
        float[] points = new float[controlPoints.size() * 2];
        for (int i = 0; i < controlPoints.size(); i++) {
            points[i * 2] = normToX(controlPoints.get(i).x);
            points[i * 2 + 1] = normToMultiplier(controlPoints.get(i).y);
        }
        return points;
    }
    
    public void setOnCurveChangedListener(OnCurveChangedListener listener) {
        this.curveChangedListener = listener;
    }
    
    private void notifyCurveChanged() {
        if (curveChangedListener != null) {
            curveChangedListener.onCurveChanged(getCurvePoints());
        }
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        int width = getWidth();
        int height = getHeight();
        int paddingLeft = 60;  // Extra padding for Y-axis labels
        int paddingRight = 30;
        int paddingTop = 30;
        int paddingBottom = 50;  // Extra padding for X-axis labels
        
        float graphWidth = width - paddingLeft - paddingRight;
        float graphHeight = height - paddingTop - paddingBottom;
        
        // Dark background
        canvas.drawColor(Color.argb(230, 30, 30, 35));
        
        // Draw zone indicators (indoor vs outdoor)
        // Indoor zone (LV < 7)
        float indoorEndX = paddingLeft + lvToNorm(7f) * graphWidth;
        zonePaint.setColor(Color.argb(15, 100, 150, 255));  // Slight blue tint for indoor
        canvas.drawRect(paddingLeft, paddingTop, indoorEndX, height - paddingBottom, zonePaint);
        
        // Outdoor zone (LV > 10)
        float outdoorStartX = paddingLeft + lvToNorm(10f) * graphWidth;
        zonePaint.setColor(Color.argb(15, 255, 200, 100));  // Slight warm tint for outdoor
        canvas.drawRect(outdoorStartX, paddingTop, width - paddingRight, height - paddingBottom, zonePaint);
        
        // Draw grid
        // Vertical lines at specific X values: -4, 0, 5, 10, 15, 16
        float[] xMarkers = {-4f, 0f, 5f, 10f, 15f, 16f};
        for (float x : xMarkers) {
            float xPos = paddingLeft + xToNorm(x) * graphWidth;
            canvas.drawLine(xPos, paddingTop, xPos, height - paddingBottom, gridPaint);
            
            // Draw X label
            String label = String.valueOf((int) x);
            float labelWidth = labelPaint.measureText(label);
            canvas.drawText(label, xPos - labelWidth / 2, height - paddingBottom + 30, labelPaint);
        }
        
        // Horizontal lines for multipliers: 0, 0.5, 1.0, 1.5, 2.0
        float[] multiplierMarkers = {0f, 0.5f, 1.0f, 1.5f, 2.0f};
        for (float mult : multiplierMarkers) {
            float y = height - paddingBottom - multiplierToNorm(mult) * graphHeight;
            canvas.drawLine(paddingLeft, y, width - paddingRight, y, gridPaint);
            
            // Draw multiplier label
            canvas.drawText(String.format("%.1f", mult), 10, y + 8, labelPaint);
        }
        
        // Draw border
        canvas.drawRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom, gridPaint);
        
        // Draw axis labels
        labelPaint.setTextSize(22f);
        String xLabel;
        if (mCurveType == CurveType.MULTIPLIER_A) {
            xLabel = "Baseline Exposure (EV)";
        } else {
            xLabel = "Light Value (LV)";
        }
        canvas.drawText(xLabel, width / 2f - 80, height - 5, labelPaint);
        
        // Save and rotate for Y-axis label
        canvas.save();
        canvas.rotate(-90, 15, height / 2f);
        canvas.drawText("Multiplier", height / 2f - 50, 30, labelPaint);
        canvas.restore();
        labelPaint.setTextSize(28f);
        
        // Draw zone labels
        labelPaint.setTextSize(18f);
        labelPaint.setColor(Color.argb(100, 255, 255, 255));
        canvas.drawText("Indoor", paddingLeft + 10, paddingTop + 25, labelPaint);
        canvas.drawText("Outdoor", width - paddingRight - 60, paddingTop + 25, labelPaint);
        labelPaint.setColor(Color.argb(150, 255, 255, 255));
        labelPaint.setTextSize(28f);
        
        // Build and draw the curve using cubic spline interpolation
        curvePath.reset();
        if (controlPoints.size() >= 2) {
            float[] splineY = computeCubicSpline();
            
            boolean first = true;
            for (int i = 0; i < splineY.length; i++) {
                float x = paddingLeft + (i / (float) (splineY.length - 1)) * graphWidth;
                float y = height - paddingBottom - splineY[i] * graphHeight;
                
                if (first) {
                    curvePath.moveTo(x, y);
                    first = false;
                } else {
                    curvePath.lineTo(x, y);
                }
            }
            canvas.drawPath(curvePath, curvePaint);
        }
        
        // Draw control points
        for (int i = 0; i < controlPoints.size(); i++) {
            PointF point = controlPoints.get(i);
            float x = paddingLeft + point.x * graphWidth;
            float y = height - paddingBottom - point.y * graphHeight;
            
            // Draw point
            canvas.drawCircle(x, y, CONTROL_POINT_RADIUS, pointPaint);
            
            // Highlight selected point
            if (i == selectedPointIndex) {
                pointStrokePaint.setColor(Color.argb(255, 255, 180, 0));
                pointStrokePaint.setStrokeWidth(4f);
            } else {
                pointStrokePaint.setColor(Color.argb(150, 100, 100, 100));
                pointStrokePaint.setStrokeWidth(2f);
            }
            canvas.drawCircle(x, y, CONTROL_POINT_RADIUS, pointStrokePaint);
        }
    }
    
    /**
     * Compute a cubic spline interpolation through the control points.
     * Returns 256 y-values (normalized) for x from 0 to 1.
     */
    private float[] computeCubicSpline() {
        int n = controlPoints.size();
        if (n < 2) {
            float[] result = new float[256];
            for (int i = 0; i < 256; i++) {
                result[i] = 0.5f;  // Default to middle
            }
            return result;
        }
        
        // Extract x and y arrays
        float[] x = new float[n];
        float[] y = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = controlPoints.get(i).x;
            y[i] = controlPoints.get(i).y;
        }
        
        // Compute spline coefficients using natural cubic spline
        float[] a = y.clone();
        float[] b = new float[n];
        float[] d = new float[n];
        float[] h = new float[n - 1];
        
        for (int i = 0; i < n - 1; i++) {
            h[i] = x[i + 1] - x[i];
            if (h[i] < 0.0001f) h[i] = 0.0001f; // Avoid division by zero
        }
        
        float[] alpha = new float[n - 1];
        for (int i = 1; i < n - 1; i++) {
            alpha[i] = (3f / h[i]) * (a[i + 1] - a[i]) - (3f / h[i - 1]) * (a[i] - a[i - 1]);
        }
        
        float[] c = new float[n];
        float[] l = new float[n];
        float[] mu = new float[n];
        float[] z = new float[n];
        
        l[0] = 1f;
        mu[0] = 0f;
        z[0] = 0f;
        
        for (int i = 1; i < n - 1; i++) {
            l[i] = 2f * (x[i + 1] - x[i - 1]) - h[i - 1] * mu[i - 1];
            if (Math.abs(l[i]) < 0.0001f) l[i] = 0.0001f;
            mu[i] = h[i] / l[i];
            z[i] = (alpha[i] - h[i - 1] * z[i - 1]) / l[i];
        }
        
        l[n - 1] = 1f;
        z[n - 1] = 0f;
        c[n - 1] = 0f;
        
        for (int j = n - 2; j >= 0; j--) {
            c[j] = z[j] - mu[j] * c[j + 1];
            b[j] = (a[j + 1] - a[j]) / h[j] - h[j] * (c[j + 1] + 2f * c[j]) / 3f;
            d[j] = (c[j + 1] - c[j]) / (3f * h[j]);
        }
        
        // Sample the spline at 256 points
        float[] result = new float[256];
        int segment = 0;
        
        for (int i = 0; i < 256; i++) {
            float xi = i / 255f;
            
            // Find the right segment
            while (segment < n - 2 && xi > x[segment + 1]) {
                segment++;
            }
            
            // Clamp segment
            segment = Math.max(0, Math.min(segment, n - 2));
            
            // Evaluate cubic polynomial
            float dx = xi - x[segment];
            float yi = a[segment] + b[segment] * dx + c[segment] * dx * dx + d[segment] * dx * dx * dx;
            
            // Clamp output to [0, 1]
            result[i] = Math.max(0f, Math.min(1f, yi));
        }
        
        return result;
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int width = getWidth();
        int height = getHeight();
        int paddingLeft = 60;
        int paddingRight = 30;
        int paddingTop = 30;
        int paddingBottom = 50;
        float graphWidth = width - paddingLeft - paddingRight;
        float graphHeight = height - paddingTop - paddingBottom;
        
        float touchX = event.getX();
        float touchY = event.getY();
        
        // Convert to normalized coordinates
        float normX = (touchX - paddingLeft) / graphWidth;
        float normY = 1f - (touchY - paddingTop) / graphHeight;
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Check if touching an existing point
                selectedPointIndex = -1;
                float minDist = TOUCH_TOLERANCE;
                
                for (int i = 0; i < controlPoints.size(); i++) {
                    PointF point = controlPoints.get(i);
                    float px = paddingLeft + point.x * graphWidth;
                    float py = height - paddingBottom - point.y * graphHeight;
                    float dist = (float) Math.sqrt((touchX - px) * (touchX - px) + (touchY - py) * (touchY - py));
                    
                    if (dist < minDist) {
                        minDist = dist;
                        selectedPointIndex = i;
                    }
                }
                
                // If not touching an existing point, add a new one
                if (selectedPointIndex == -1 && normX > 0.02f && normX < 0.98f) {
                    normX = Math.max(0.01f, Math.min(0.99f, normX));
                    normY = Math.max(0f, Math.min(1f, normY));
                    controlPoints.add(new PointF(normX, normY));
                    sortControlPoints();
                    
                    // Find the index of the newly added point
                    for (int i = 0; i < controlPoints.size(); i++) {
                        if (Math.abs(controlPoints.get(i).x - normX) < 0.001f) {
                            selectedPointIndex = i;
                            break;
                        }
                    }
                    notifyCurveChanged();
                }
                
                invalidate();
                return true;
                
            case MotionEvent.ACTION_MOVE:
                if (selectedPointIndex >= 0) {
                    PointF point = controlPoints.get(selectedPointIndex);
                    
                    // Don't allow moving the first or last point's X
                    if (selectedPointIndex == 0) {
                        point.x = 0f;
                    } else if (selectedPointIndex == controlPoints.size() - 1) {
                        point.x = 1f;
                    } else {
                        // Constrain X between neighbors
                        float minX = controlPoints.get(selectedPointIndex - 1).x + 0.02f;
                        float maxX = controlPoints.get(selectedPointIndex + 1).x - 0.02f;
                        point.x = Math.max(minX, Math.min(maxX, normX));
                    }
                    
                    // Y can move freely (clamped to valid range)
                    point.y = Math.max(0f, Math.min(1f, normY));
                    
                    invalidate();
                    notifyCurveChanged();
                }
                return true;
                
            case MotionEvent.ACTION_UP:
                selectedPointIndex = -1;
                invalidate();
                return true;
        }
        
        return super.onTouchEvent(event);
    }
    
    /**
     * Remove the currently selected point (if it's not an endpoint).
     * @return true if a point was removed
     */
    public boolean removeSelectedPoint() {
        if (selectedPointIndex > 0 && selectedPointIndex < controlPoints.size() - 1) {
            controlPoints.remove(selectedPointIndex);
            selectedPointIndex = -1;
            invalidate();
            notifyCurveChanged();
            return true;
        }
        return false;
    }
    
    private void sortControlPoints() {
        Collections.sort(controlPoints, (a, b) -> Float.compare(a.x, b.x));
    }
    
    /**
     * Build a 256-element LUT from the current curve.
     * Returns an array of values (offset or multiplier) for X values from -4 to 16.
     */
    public float[] buildLUT() {
        float[] normalized = computeCubicSpline();
        float[] lut = new float[256];
        for (int i = 0; i < 256; i++) {
            lut[i] = normToMultiplier(normalized[i]);
        }
        return lut;
    }
    
    /**
     * Look up the value for a given X (lightValue or baselineExposure).
     * @param x X value (typically -4 to 16)
     * @return Offset (if OFFSET curve) or Multiplier (if MULTIPLIER curve)
     */
    public float lookupValue(float x) {
        float[] lut = buildLUT();
        float norm = xToNorm(Math.max(MIN_X, Math.min(MAX_X, x)));
        int index = Math.round(norm * 255f);
        index = Math.max(0, Math.min(255, index));
        return lut[index];
    }
    
    /**
     * Legacy method for backward compatibility
     */
    public float lookupStrength(float lv) {
        return lookupValue(lv);
    }
}
