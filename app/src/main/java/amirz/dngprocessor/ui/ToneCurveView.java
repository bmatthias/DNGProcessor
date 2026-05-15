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
 * Custom view for editing a tone curve with draggable control points.
 * The curve maps input luminance (x-axis) to output luminance (y-axis).
 * Uses cubic spline interpolation for smooth curves.
 */
public class ToneCurveView extends View {
    
    private static final int GRID_LINES = 4;
    private static final float CONTROL_POINT_RADIUS = 24f;
    private static final float TOUCH_TOLERANCE = 48f;
    
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint histogramPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint diagonalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curvePath = new Path();
    
    // Control points in normalized coordinates (0-1)
    private final List<PointF> controlPoints = new ArrayList<>();
    private int selectedPointIndex = -1;
    
    // Histogram data (optional, for visual reference)
    private int[] histogram = null;
    
    // Callback for curve changes
    private OnCurveChangedListener curveChangedListener;
    
    public interface OnCurveChangedListener {
        void onCurveChanged(float[] curvePoints);
    }
    
    public ToneCurveView(Context context) {
        super(context);
        init();
    }
    
    public ToneCurveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public ToneCurveView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        // Grid paint
        gridPaint.setColor(Color.argb(60, 255, 255, 255));
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);
        
        // Diagonal reference line
        diagonalPaint.setColor(Color.argb(80, 255, 255, 255));
        diagonalPaint.setStrokeWidth(2f);
        diagonalPaint.setStyle(Paint.Style.STROKE);
        
        // Curve paint
        curvePaint.setColor(Color.WHITE);
        curvePaint.setStrokeWidth(3f);
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        curvePaint.setStrokeJoin(Paint.Join.ROUND);
        
        // Control point fill
        pointPaint.setColor(Color.WHITE);
        pointPaint.setStyle(Paint.Style.FILL);
        
        // Control point stroke
        pointStrokePaint.setColor(Color.argb(180, 0, 150, 255));
        pointStrokePaint.setStrokeWidth(3f);
        pointStrokePaint.setStyle(Paint.Style.STROKE);
        
        // Histogram paint
        histogramPaint.setColor(Color.argb(40, 255, 255, 255));
        histogramPaint.setStyle(Paint.Style.FILL);
        
        // Initialize with default linear curve (just endpoints)
        resetCurve();
    }
    
    /**
     * Reset the curve to a linear (identity) mapping.
     */
    public void resetCurve() {
        controlPoints.clear();
        controlPoints.add(new PointF(0f, 0f));  // Black point
        controlPoints.add(new PointF(1f, 1f));  // White point
        invalidate();
        notifyCurveChanged();
    }
    
    /**
     * Set the control points from an array of [x0, y0, x1, y1, ...] values.
     */
    public void setCurvePoints(float[] points) {
        controlPoints.clear();
        for (int i = 0; i < points.length - 1; i += 2) {
            controlPoints.add(new PointF(points[i], points[i + 1]));
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
     */
    public float[] getCurvePoints() {
        float[] points = new float[controlPoints.size() * 2];
        for (int i = 0; i < controlPoints.size(); i++) {
            points[i * 2] = controlPoints.get(i).x;
            points[i * 2 + 1] = controlPoints.get(i).y;
        }
        return points;
    }
    
    /**
     * Set histogram data for visual reference.
     * @param histogram 256-element array of counts
     */
    public void setHistogram(int[] histogram) {
        this.histogram = histogram;
        invalidate();
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
        int padding = (int) (CONTROL_POINT_RADIUS + 4);
        
        float graphWidth = width - 2 * padding;
        float graphHeight = height - 2 * padding;
        
        // Dark background
        canvas.drawColor(Color.argb(230, 30, 30, 35));
        
        // Draw histogram if available
        if (histogram != null && histogram.length == 256) {
            int maxCount = 1;
            for (int count : histogram) {
                maxCount = Math.max(maxCount, count);
            }
            
            float barWidth = graphWidth / 256f;
            for (int i = 0; i < 256; i++) {
                float barHeight = (histogram[i] / (float) maxCount) * graphHeight * 0.8f;
                float x = padding + (i / 255f) * graphWidth;
                canvas.drawRect(x, height - padding - barHeight, 
                               x + barWidth + 1, height - padding, histogramPaint);
            }
        }
        
        // Draw grid
        for (int i = 1; i < GRID_LINES; i++) {
            float pos = i / (float) GRID_LINES;
            // Vertical lines
            float x = padding + pos * graphWidth;
            canvas.drawLine(x, padding, x, height - padding, gridPaint);
            // Horizontal lines
            float y = padding + pos * graphHeight;
            canvas.drawLine(padding, y, width - padding, y, gridPaint);
        }
        
        // Draw border
        canvas.drawRect(padding, padding, width - padding, height - padding, gridPaint);
        
        // Draw diagonal reference (identity line)
        canvas.drawLine(padding, height - padding, width - padding, padding, diagonalPaint);
        
        // Build and draw the curve using cubic spline interpolation
        curvePath.reset();
        if (controlPoints.size() >= 2) {
            float[] splineY = computeCubicSpline();
            
            boolean first = true;
            for (int i = 0; i < splineY.length; i++) {
                float x = padding + (i / (float) (splineY.length - 1)) * graphWidth;
                float y = height - padding - splineY[i] * graphHeight;
                
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
            float x = padding + point.x * graphWidth;
            float y = height - padding - point.y * graphHeight;
            
            // Draw point
            canvas.drawCircle(x, y, CONTROL_POINT_RADIUS, pointPaint);
            
            // Highlight selected point
            if (i == selectedPointIndex) {
                pointStrokePaint.setColor(Color.argb(255, 0, 180, 255));
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
     * Returns 256 y-values for x from 0 to 1.
     */
    private float[] computeCubicSpline() {
        int n = controlPoints.size();
        if (n < 2) {
            float[] result = new float[256];
            for (int i = 0; i < 256; i++) {
                result[i] = i / 255f;
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
        int padding = (int) (CONTROL_POINT_RADIUS + 4);
        float graphWidth = width - 2 * padding;
        float graphHeight = height - 2 * padding;
        
        float touchX = event.getX();
        float touchY = event.getY();
        
        // Convert to normalized coordinates
        float normX = (touchX - padding) / graphWidth;
        float normY = 1f - (touchY - padding) / graphHeight;
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Check if touching an existing point
                selectedPointIndex = -1;
                float minDist = TOUCH_TOLERANCE;
                
                for (int i = 0; i < controlPoints.size(); i++) {
                    PointF point = controlPoints.get(i);
                    float px = padding + point.x * graphWidth;
                    float py = height - padding - point.y * graphHeight;
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
                    
                    // Y can move freely
                    point.y = Math.max(0f, Math.min(1f, normY));
                    
                    invalidate();
                    notifyCurveChanged();
                }
                return true;
                
            case MotionEvent.ACTION_UP:
                // Double-tap to remove a point (if not endpoint)
                if (selectedPointIndex > 0 && selectedPointIndex < controlPoints.size() - 1) {
                    // Check if this is a quick tap (potential delete)
                    // For now, just deselect
                }
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
     * Build a 256-element LUT from the current curve for use in shaders.
     */
    public float[] buildLUT() {
        return computeCubicSpline();
    }
}

