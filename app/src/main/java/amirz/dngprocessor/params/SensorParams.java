package amirz.dngprocessor.params;

import android.graphics.Bitmap;

public class SensorParams {
    public int inputWidth;
    public int inputHeight;
    public int inputStride;
    public int cfa;
    public byte[] cfaVal;
    public int samplesPerPixel = 1;  // 1 for Bayer CFA, 3 for Linear Raw RGB
    public boolean isLinearRaw = false;  // True if already demosaiced RGB data
    public boolean dimensionsSwapped = false;  // True if raw data was stored with swapped dimensions
    public int[] blackLevelPattern;
    public int whiteLevel;
    public int referenceIlluminant1;
    public int referenceIlluminant2;
    public float[] calibrationTransform1;
    public float[] calibrationTransform2;
    public float[] colorMatrix1;
    public float[] colorMatrix2;
    public float[] forwardTransform1;
    public float[] forwardTransform2;
    public float[] analogBalance;  // Analog balance multipliers for R, G, B channels
    public float[] neutralColorPoint;
    public float[] noiseProfile;
    public float noiseReductionApplied = 0f;  // Amount of NR already applied (0.0=none, 1.0=full)
    public float baselineNoise = 1f;  // Relative noise level vs reference camera at ISO 100
    public int outputOffsetX;
    public int outputOffsetY;
    public float[] gainMap;
    public int[] gainMapSize;
    public short[] hotPixels = new short[1];
    public int[] hotPixelsSize = new int[] { 1, 1 };

    // DNG tone mapping hints
    public float baselineExposure = 0f;  // EV compensation suggested by DNG creator
    public float linearResponseLimit = 1f;  // Fraction of max value that is linear (before highlight compression)
    public float lightValue = 0f;  // EXIF Light Value (negative = night mode)
    
    // DNG Camera Profile - for camera-intended color rendering
    public String profileName = null;           // Name of the embedded profile
    public String asShotProfileName = null;     // Camera's intended profile name
    
    // ProfileHueSatMap - per-hue HSL adjustments (like Adobe Camera Raw profiles)
    // This is how camera manufacturers create their signature color look
    public int[] profileHueSatMapDims = null;   // [hueDivisions, satDivisions, valDivisions]
    public float[] profileHueSatMapData1 = null; // HSL deltas for illuminant 1: [h,s,v] per cell
    public float[] profileHueSatMapData2 = null; // HSL deltas for illuminant 2: [h,s,v] per cell
    
    // ProfileToneCurve - the camera's contrast/tone response curve
    // Array of [x, y] pairs defining the curve (x=input, y=output, both 0-1)
    public float[] profileToneCurve = null;
    
    // ProfileLookTable - 3D color LUT for full color grading
    public int[] profileLookTableDims = null;   // [hueDivisions, satDivisions, valDivisions]
    public float[] profileLookTableData = null; // HSL deltas per cell
    public int profileLookTableEncoding = 0;    // 0=linear, 1=sRGB
    
    // Profile embed policy: 0=allow copying, 1=embed if used, 2=embed never, 3=no restrictions
    public int profileEmbedPolicy = 0;
    
    // Preview/reference JPEG image (for tone matching)
    public Bitmap previewImage = null;           // Decoded JPEG preview from DNG
    public int previewWidth = 0;
    public int previewHeight = 0;
    
    // Helper methods to check if profile data is available
    public boolean hasPreview() {
        return previewImage != null;
    }
    public boolean hasHueSatMap() {
        return profileHueSatMapDims != null && profileHueSatMapData1 != null;
    }
    
    public boolean hasToneCurve() {
        return profileToneCurve != null && profileToneCurve.length >= 4; // At least 2 points
    }
    
    public boolean hasLookTable() {
        return profileLookTableDims != null && profileLookTableData != null;
    }
    
    public boolean hasProfile() {
        return hasHueSatMap() || hasToneCurve() || hasLookTable();
    }
}
