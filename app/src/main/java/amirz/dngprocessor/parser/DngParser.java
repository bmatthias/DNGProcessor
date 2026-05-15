package amirz.dngprocessor.parser;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import androidx.exifinterface.media.ExifInterface;
import android.net.Uri;
import android.util.Log;
import android.util.Rational;
import android.util.SparseArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import amirz.dngprocessor.gl.GLContext;
import amirz.dngprocessor.pipeline.StagePipeline;
import amirz.dngprocessor.pipeline.LinearRawPipeline;
import amirz.dngprocessor.util.NotifHandler;
import amirz.dngprocessor.util.Path;
import amirz.dngprocessor.Preferences;
import amirz.dngprocessor.device.DeviceMap;
import amirz.dngprocessor.ui.ToneCurveActivity;
import amirz.dngprocessor.ui.AdaptiveSaturationCurveActivity;
import amirz.dngprocessor.util.ShaderLoader;
import amirz.dngprocessor.util.Utilities;
import amirz.dngprocessor.util.HdrJpegWriter;
import amirz.dngprocessor.util.UltraHdrEncoder;
import amirz.dngprocessor.util.HdrJpegVerifier;
import amirz.dngprocessor.params.ProcessParams;
import amirz.dngprocessor.params.SensorParams;

import static amirz.dngprocessor.util.Constants.DIAGONAL;

public class DngParser {
    private static final String TAG = "DngParser";

    private static int ADD_STEPS = 0;
    private static int STEP_SAVE = ADD_STEPS++;
    private static int STEP_META = ADD_STEPS++;

    private final Context mContext;
    private final Uri mUri;
    private final String mFile;

    public DngParser(Context context, Uri uri, String file) {
        mContext = context;
        mUri = uri;
        mFile = file;
    }

    private TIFFTag getTag(SparseArray<TIFFTag> tags, int id) {
        return tags.get(id, TIFFTag.exceptionWrapper(id));
    }

    public void run() {
        NotifHandler.progress(mContext, 1, 0);

        Preferences pref = Preferences.global();

        ByteReader.ReaderWithExif reader = ByteReader.fromUri(mContext, mUri);
        Log.i(TAG, "Starting processing of " + mFile + " (" + mUri.getPath() + ") size " +
                reader.length);

        ByteBuffer wrap = reader.wrap;

        byte[] format = { wrap.get(), wrap.get() };
        if (!new String(format).equals("II"))
            throw new ParseException("Can only parse Intel byte order");

        short version = wrap.getShort();
        if (version != 42)
            throw new ParseException("Can only parse v42");

        int start = wrap.getInt();
        wrap.position(start);

        SparseArray<TIFFTag> tags = TagParser.parse(wrap);

        // Try to extract embedded JPEG preview from IFD0 before navigating to SubIFD
        // The preview can be used as a reference for tone matching
        // Only extract if the user has enabled the "match embedded jpeg" option
        Bitmap previewBitmap = null;
        byte[] embeddedJpegData = null; // Store JPEG data for gain map extraction
        TIFFTag type = tags.get(TIFF.TAG_NewSubfileType);
        if (pref.referencePreview.get() || pref.copyGainMapFromJpeg.get()) {
            // Try extracting from IFD0 first (regardless of NewSubfileType)
            previewBitmap = extractPreviewJpeg(wrap, tags);
            if (previewBitmap != null) {
                Log.d(TAG, "Extracted preview JPEG from IFD0: " + previewBitmap.getWidth() + "x" + previewBitmap.getHeight());
            } else {
                // Log what tags are present for debugging
                TIFFTag jpegOffsetTag = tags.get(TIFF.TAG_JpegInterchangeFormat);
                TIFFTag jpegLengthTag = tags.get(TIFF.TAG_JpegInterchangeFormatLength);
                TIFFTag compressionTag = tags.get(TIFF.TAG_Compression);
                if (jpegOffsetTag != null || jpegLengthTag != null || 
                    (compressionTag != null && compressionTag.getInt() == TIFF.COMPRESSION_JPEG)) {
                    Log.d(TAG, "Preview tags found in IFD0 but extraction failed - jpegOffset=" + 
                          (jpegOffsetTag != null) + ", jpegLength=" + (jpegLengthTag != null) + 
                          ", compression=" + (compressionTag != null ? compressionTag.getInt() : "null"));
                }
            }
            
            // If we need to copy gain map from JPEG, extract the raw JPEG data
            if (pref.copyGainMapFromJpeg.get()) {
                // Check if HdrImageReader is available before attempting extraction
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Log.w(TAG, "Copy gain map from JPEG requires Android 14+ (API 34+), current: " + Build.VERSION.SDK_INT);
                } else {
                    // Try to check if HdrImageReader class is accessible
                    try {
                        Class.forName("android.graphics.HdrImageReader");
                        Log.d(TAG, "HdrImageReader is accessible, proceeding with gain map extraction");
                    } catch (ClassNotFoundException e) {
                        Log.w(TAG, "HdrImageReader not accessible (hidden API) - copy gain map feature may not work on this device");
                    }
                }
                
                embeddedJpegData = extractJpegData(wrap, tags);
                if (embeddedJpegData != null) {
                    Log.d(TAG, "Extracted embedded JPEG data for gain map: " + embeddedJpegData.length + " bytes");
                } else {
                    Log.w(TAG, "Failed to extract embedded JPEG data for gain map");
                }
            }
        }

        TIFFTag subIFD = tags.get(TIFF.TAG_SubIFDs);
        // Separate preview extraction from tag merging:
        // - Preview extraction: can happen from SubIFD regardless of type (if preview option enabled)
        // - Tag merging: only merge SubIFD tags when type == 1 (original behavior to avoid breaking images)
        if (subIFD != null) {
            try {
                // SubIFD can be a single offset or an array
                // Check by trying to get as array first, then fall back to single value
                int[] subIFDOffsets = null;
                try {
                    subIFDOffsets = subIFD.getIntArray();
                } catch (Exception e) {
                    // Not an array, try as single value
                }
                
                if (subIFDOffsets != null && subIFDOffsets.length == 1) {
                    // Single SubIFD offset (stored as array with one element)
                    int offset = subIFDOffsets[0];
                    wrap.position(offset);
                    SparseArray<TIFFTag> subTags = TagParser.parse(wrap);
                    // Try extracting preview (regardless of type)
                    if ((pref.referencePreview.get() || pref.copyGainMapFromJpeg.get()) && previewBitmap == null) {
                        Bitmap subPreview = extractPreviewJpeg(wrap, subTags);
                        if (subPreview != null) {
                            previewBitmap = subPreview;
                            Log.d(TAG, "Extracted preview JPEG from SubIFD: " + previewBitmap.getWidth() + "x" + previewBitmap.getHeight());
                        }
                    }
                    // Extract JPEG data for gain map if needed
                    if (pref.copyGainMapFromJpeg.get() && embeddedJpegData == null) {
                        embeddedJpegData = extractJpegData(wrap, subTags);
                        if (embeddedJpegData != null) {
                            Log.d(TAG, "Extracted embedded JPEG data from SubIFD for gain map: " + embeddedJpegData.length + " bytes");
                        }
                    }
                    // RESTORED: Only merge tags if type == 1 (original behavior)
                    if (type != null && type.getInt() == 1) {
                        // Merge tags for raw image parsing (don't restore position - subsequent code uses tag offsets)
                        for (int i = 0; i < subTags.size(); i++) {
                            tags.put(subTags.keyAt(i), subTags.valueAt(i));
                        }
                    }
                } else if (subIFDOffsets != null && subIFDOffsets.length > 1) {
                    // Multiple SubIFDs - check each one for previews
                    int firstSubIFDPosition = -1;
                    for (int i = 0; i < subIFDOffsets.length; i++) {
                        int offset = subIFDOffsets[i];
                        int savedPosition = wrap.position();
                        wrap.position(offset);
                        SparseArray<TIFFTag> subTags = TagParser.parse(wrap);
                        // Try extracting preview (regardless of type)
                        if ((pref.referencePreview.get() || pref.copyGainMapFromJpeg.get()) && previewBitmap == null) {
                            Bitmap subPreview = extractPreviewJpeg(wrap, subTags);
                            if (subPreview != null) {
                                previewBitmap = subPreview;
                                Log.d(TAG, "Extracted preview JPEG from SubIFD[" + i + "]: " + previewBitmap.getWidth() + "x" + previewBitmap.getHeight());
                            }
                        }
                        // Extract JPEG data for gain map if needed
                        if (pref.copyGainMapFromJpeg.get() && embeddedJpegData == null) {
                            embeddedJpegData = extractJpegData(wrap, subTags);
                            if (embeddedJpegData != null) {
                                Log.d(TAG, "Extracted embedded JPEG data from SubIFD[" + i + "] for gain map: " + embeddedJpegData.length + " bytes");
                            }
                        }
                        // RESTORED: Only merge tags from first SubIFD if type == 1 (original behavior)
                        if (i == 0 && type != null && type.getInt() == 1) {
                            firstSubIFDPosition = wrap.position();
                            for (int j = 0; j < subTags.size(); j++) {
                                tags.put(subTags.keyAt(j), subTags.valueAt(j));
                            }
                        } else if (i == 0) {
                            // Save position even if we don't merge tags (for consistency)
                            firstSubIFDPosition = wrap.position();
                        } else {
                            // Restore position for other SubIFDs (we only need tags from first one)
                            wrap.position(savedPosition);
                        }
                    }
                    // Restore to first SubIFD position if we parsed it (matching original behavior)
                    if (firstSubIFDPosition >= 0 && type != null && type.getInt() == 1) {
                        wrap.position(firstSubIFDPosition);
                    }
                } else {
                    // Try as single int value
                    try {
                        int offset = subIFD.getInt();
                        wrap.position(offset);
                        SparseArray<TIFFTag> subTags = TagParser.parse(wrap);
                        // Try extracting preview (regardless of type)
                        if ((pref.referencePreview.get() || pref.copyGainMapFromJpeg.get()) && previewBitmap == null) {
                            Bitmap subPreview = extractPreviewJpeg(wrap, subTags);
                            if (subPreview != null) {
                                previewBitmap = subPreview;
                                Log.d(TAG, "Extracted preview JPEG from SubIFD: " + previewBitmap.getWidth() + "x" + previewBitmap.getHeight());
                            }
                        }
                        // Extract JPEG data for gain map if needed
                        if (pref.copyGainMapFromJpeg.get() && embeddedJpegData == null) {
                            embeddedJpegData = extractJpegData(wrap, subTags);
                            if (embeddedJpegData != null) {
                                Log.d(TAG, "Extracted embedded JPEG data from SubIFD for gain map: " + embeddedJpegData.length + " bytes");
                            }
                        }
                        // RESTORED: Only merge tags if type == 1 (original behavior)
                        if (type != null && type.getInt() == 1) {
                            // Merge tags for raw image parsing
                            for (int i = 0; i < subTags.size(); i++) {
                                tags.put(subTags.keyAt(i), subTags.valueAt(i));
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not parse SubIFD as single value or array: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error parsing SubIFD: " + e.getMessage());
            }
        }

        //int rowsPerStrip = tags.get(TIFF.TAG_RowsPerStrip).getInt();
        //if (rowsPerStrip != 1)
        //    throw new ParseException("Can only parse RowsPerStrip = 1");

        SensorParams sensor = new SensorParams();

        // Continue image parsing.
        sensor.inputHeight = getTag(tags, TIFF.TAG_ImageLength).getInt();
        sensor.inputWidth = getTag(tags, TIFF.TAG_ImageWidth).getInt();

        // Detect compression type and photometric interpretation
        TIFFTag compressionTag = tags.get(TIFF.TAG_Compression);
        int compression = compressionTag != null ? compressionTag.getInt() : TIFF.COMPRESSION_NONE;

        TIFFTag samplesTag = tags.get(TIFF.TAG_SamplesPerPixel);
        sensor.samplesPerPixel = samplesTag != null ? samplesTag.getInt() : 1;

        // Parse bits per sample (important for packed bit depths)
        TIFFTag bitsTag = tags.get(TIFF.TAG_BitsPerSample);
        int bitsPerSample = 16;  // Default to 16-bit
        if (bitsTag != null) {
            int[] bits = bitsTag.getIntArray();
            if (bits.length > 0) {
                bitsPerSample = bits[0];
            }
        }

        // Detect Linear Raw (already demosaiced RGB data, like Xiaomi UltraRAW)
        TIFFTag photometricTag = tags.get(TIFF.TAG_PhotometricInterpretation);
        int photometric = photometricTag != null ? photometricTag.getInt() : TIFF.PHOTOMETRIC_CFA;
        sensor.isLinearRaw = (sensor.samplesPerPixel == 3) || (photometric == TIFF.PHOTOMETRIC_LINEAR_RAW);

        Log.d(TAG, "Compression: " + compression + ", SamplesPerPixel: " + sensor.samplesPerPixel +
                ", BitsPerSample: " + bitsPerSample +
                ", PhotometricInterpretation: " + photometric + ", isLinearRaw: " + sensor.isLinearRaw);

        byte[] rawImageInput;

        // Check if using tiles or strips
        TIFFTag tileOffsetsTag = tags.get(TIFF.TAG_TileOffsets);
        TIFFTag tileByteCounts = tags.get(TIFF.TAG_TileByteCounts);
        boolean usesTiles = tileOffsetsTag != null && tileByteCounts != null;

        if (usesTiles && compression == TIFF.COMPRESSION_JPEG) {
            // JPEG-compressed tiles (supports both Bayer CFA and Linear Raw formats)
            Log.d(TAG, "Parsing JPEG-compressed tiles (" + 
                    (sensor.isLinearRaw ? "Linear Raw RGB" : "Bayer CFA") + " format)");
            rawImageInput = decodeJpegTiles(wrap, tags, sensor);
        } else if (usesTiles) {
            // Uncompressed tile-based format
            Log.d(TAG, "Parsing uncompressed tiles (" + 
                    (sensor.isLinearRaw ? "Linear Raw RGB" : "Bayer CFA") + " format)");
            rawImageInput = decodeUncompressedTiles(wrap, tags, sensor, bitsPerSample);
        } else {
            // Traditional strip-based format
            TIFFTag stripOffsetsTag = tags.get(TIFF.TAG_StripOffsets);
            TIFFTag stripByteCountsTag = tags.get(TIFF.TAG_StripByteCounts);

            int[] stripOffsets;
            int[] stripByteCounts;

            if (stripOffsetsTag != null && stripByteCountsTag != null) {
                stripOffsets = stripOffsetsTag.getIntArray();
                stripByteCounts = stripByteCountsTag.getIntArray();
            } else {
                throw new ParseException("No valid strip or tile data found in DNG file");
            }

            if (stripOffsets.length != stripByteCounts.length) {
                throw new RuntimeException("StripOffsets was not equal to StripByteCounts");
            }

            sensor.inputStride = stripByteCounts[0];

            // Calculate output buffer size: always 16-bit per sample for pipeline
            // samplesPerPixel = 1 for CFA/Bayer, 3 for Linear Raw RGB
            int bytesPerPixel = sensor.samplesPerPixel * 2;
            rawImageInput = new byte[sensor.inputWidth * sensor.inputHeight * bytesPerPixel];
            Log.d(TAG, "Strip-based " + (sensor.isLinearRaw ? "Linear Raw RGB" : "Bayer CFA") +
                    ": " + sensor.inputWidth + "x" + sensor.inputHeight + 
                    ", bitsPerSample=" + bitsPerSample + ", bytesPerPixel=" + bytesPerPixel);

            if (bitsPerSample == 16) {
                // Direct 16-bit data - just copy
                int rawImageOffset = 0;
                for (int i = 0; i < stripOffsets.length; i++) {
                    ((ByteBuffer) wrap.position(stripOffsets[i]))
                            .get(rawImageInput, rawImageOffset, stripByteCounts[i]);
                    rawImageOffset += stripByteCounts[i];
                }
            } else if (bitsPerSample >= 10 && bitsPerSample <= 14) {
                // Packed bit depths (10, 12, 14-bit) - need to unpack to 16-bit
                Log.d(TAG, "Unpacking " + bitsPerSample + "-bit data to 16-bit");
                rawImageInput = unpackBits(wrap, stripOffsets, stripByteCounts, 
                        sensor.inputWidth, sensor.inputHeight, sensor.samplesPerPixel, bitsPerSample);
            } else {
                throw new ParseException("Unsupported bits per sample: " + bitsPerSample);
            }
        }

        // CFA pattern handling - only needed for Bayer pattern data
        TIFFTag cfaTag = tags.get(TIFF.TAG_CFAPattern);
        if (cfaTag != null && !sensor.isLinearRaw) {
            sensor.cfaVal = cfaTag.getByteArray();
            sensor.cfa = CFAPattern.get(sensor.cfaVal);
        } else if (sensor.isLinearRaw) {
            // Linear Raw doesn't have CFA pattern - use dummy values
            sensor.cfaVal = new byte[] { 0, 1, 1, 2 };  // RGGB placeholder
            sensor.cfa = 0;  // Will be ignored for Linear Raw pipeline
            Log.d(TAG, "Linear Raw format detected - CFA pattern not applicable");
        } else {
            throw new ParseException("No CFA pattern found and not Linear Raw format");
        }
        sensor.blackLevelPattern = getTag(tags, TIFF.TAG_BlackLevel).getIntArray();
        sensor.whiteLevel = getTag(tags, TIFF.TAG_WhiteLevel).getInt();
        sensor.referenceIlluminant1 = getTag(tags, TIFF.TAG_CalibrationIlluminant1).getInt();
        sensor.referenceIlluminant2 = getTag(tags, TIFF.TAG_CalibrationIlluminant2).getInt();
        TIFFTag CC1 = tags.get(TIFF.TAG_CameraCalibration1);
        TIFFTag CC2 = tags.get(TIFF.TAG_CameraCalibration2);
        if (CC1 != null && CC2 != null) {
            sensor.calibrationTransform1 = CC1.getFloatArray();
            sensor.calibrationTransform2 = CC2.getFloatArray();
        }
        // Parse AnalogBalance (tag 50727) - multipliers for R, G, B channels
        // Per DNG spec, dcraw, LibRaw, and darktable: AnalogBalance must be multiplied into
        // the CameraCalibration matrix: cc[i][c] *= ab[i]
        // This is applied in LinearRawColorspaceConverter.applyAnalogBalance()
        TIFFTag analogBalanceTag = tags.get(TIFF.TAG_AnalogBalance);
        if (analogBalanceTag != null) {
            sensor.analogBalance = analogBalanceTag.getFloatArray();
            Log.d(TAG, "AnalogBalance: R=" + sensor.analogBalance[0] + 
                       ", G=" + sensor.analogBalance[1] + 
                       ", B=" + sensor.analogBalance[2]);
        } else {
            // Default to no correction (1.0 for all channels)
            sensor.analogBalance = new float[] { 1.0f, 1.0f, 1.0f };
            Log.d(TAG, "AnalogBalance not found, using default [1.0, 1.0, 1.0]");
        }
        sensor.colorMatrix1 = getTag(tags, TIFF.TAG_ColorMatrix1).getFloatArray();
        sensor.colorMatrix2 = getTag(tags, TIFF.TAG_ColorMatrix2).getFloatArray();
        if (pref.forwardMatrix.get()) {
            TIFFTag fm1 = tags.get(TIFF.TAG_ForwardMatrix1);
            TIFFTag fm2 = tags.get(TIFF.TAG_ForwardMatrix2);
            if (fm1 != null && fm2 != null) {
                sensor.forwardTransform1 = fm1.getFloatArray();
                sensor.forwardTransform2 = fm2.getFloatArray();
            }
        }
        Rational[] asShotNeutral = getTag(tags, TIFF.TAG_AsShotNeutral).getRationalArray();
        sensor.neutralColorPoint = new float[] {
                asShotNeutral[0].floatValue(),
                asShotNeutral[1].floatValue(),
                asShotNeutral[2].floatValue()
        };

        TIFFTag noiseProfile = tags.get(TIFF.TAG_NoiseProfile);
        if (noiseProfile == null) {
            sensor.noiseProfile = new float[6];
        } else {
            sensor.noiseProfile = noiseProfile.getFloatArray();
        }

        // Parse NoiseReductionApplied - indicates how much NR has already been applied
        // 0.0 = no NR applied, 1.0 = full/ideal NR applied, 0/0 = unknown
        TIFFTag noiseReductionAppliedTag = tags.get(TIFF.TAG_NoiseReductionApplied);
        if (noiseReductionAppliedTag != null) {
            Rational nrApplied = noiseReductionAppliedTag.getRational();
            // Check for 0/0 (unknown) - treat as 0 (no NR applied)
            if (nrApplied.getDenominator() == 0) {
                sensor.noiseReductionApplied = 0f;
                Log.d(TAG, "NoiseReductionApplied: unknown (0/0), treating as 0");
            } else {
                sensor.noiseReductionApplied = nrApplied.floatValue();
                // Clamp to valid range [0.0, 1.0]
                sensor.noiseReductionApplied = Math.max(0f, Math.min(1f, sensor.noiseReductionApplied));
                Log.d(TAG, "NoiseReductionApplied: " + sensor.noiseReductionApplied);
            }
        }

        // Parse BaselineNoise - relative noise level vs reference camera at ISO 100
        // Used as fallback when NoiseProfile is missing
        TIFFTag baselineNoiseTag = tags.get(TIFF.TAG_BaselineNoise);
        if (baselineNoiseTag != null) {
            sensor.baselineNoise = baselineNoiseTag.getFloat();
            // Sanity check - should be positive
            sensor.baselineNoise = Math.max(0.1f, sensor.baselineNoise);
            Log.d(TAG, "BaselineNoise: " + sensor.baselineNoise);
        }

        // Parse DNG tone mapping hints
        TIFFTag baselineExposureTag = tags.get(TIFF.TAG_BaselineExposure);
        if (baselineExposureTag != null) {
            sensor.baselineExposure = baselineExposureTag.getFloat();
            Log.d(TAG, "BaselineExposure: " + sensor.baselineExposure + " EV");
        }

        TIFFTag linearResponseLimitTag = tags.get(TIFF.TAG_LinearResponseLimit);
        if (linearResponseLimitTag != null) {
            sensor.linearResponseLimit = linearResponseLimitTag.getFloat();
            // Clamp to valid range [0.1, 1.0]
            sensor.linearResponseLimit = Math.max(0.1f, Math.min(1.0f, sensor.linearResponseLimit));
            Log.d(TAG, "LinearResponseLimit: " + sensor.linearResponseLimit);
        }
        
        // Read Light Value from EXIF for night mode detection
        // Light Value < 0 indicates a dark scene (night mode)
        // This is used to apply special processing for night images (similar to convert_uraw script)
        // The convert_uraw script uses: exiftool -a $URAW | grep "Light Value"
        boolean lightValueCalculated = false;
        if (reader.exif != null) {
            // Try multiple possible EXIF attribute names for Light Value
            // Different cameras/store it under different names
            String[] lightValueKeys = {
                "LightValue",
                "ExposureValue", 
                "Light Value",
                "LightValue (APEX)",
                "Exposure Value (APEX)"
            };
            
            String lightValueStr = null;
            for (String key : lightValueKeys) {
                lightValueStr = reader.exif.getAttribute(key);
                if (lightValueStr != null && !lightValueStr.trim().isEmpty()) {
                    Log.d(TAG, "Found Light Value using key '" + key + "': " + lightValueStr);
                    break;
                }
            }
            
            if (lightValueStr != null && !lightValueStr.trim().isEmpty()) {
                try {
                    // Handle formats like "Light Value: -2.0" (exiftool format)
                    String cleaned = lightValueStr.trim();
                    // Extract number if it's in "Label: value" format
                    int colonIndex = cleaned.indexOf(':');
                    if (colonIndex >= 0) {
                        cleaned = cleaned.substring(colonIndex + 1).trim();
                    }
                    sensor.lightValue = Float.parseFloat(cleaned);
                    lightValueCalculated = true;
                    Log.d(TAG, "Light Value: " + sensor.lightValue + " (parsed from EXIF: " + lightValueStr + ")");
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Could not parse EXIF Light Value '" + lightValueStr + "': " + e.getMessage());
                }
            } else {
                Log.d(TAG, "Light Value not found in EXIF using standard keys");
            }
            
            // If not found, calculate from exposure parameters: LV = log2(f²/t) - log2(ISO/100)
            // Read from TIFF tags directly (DNG stores metadata in TIFF, not standard EXIF)
            if (!lightValueCalculated) {
                TIFFTag exposureTimeTag = tags.get(TIFF.TAG_ExposureTime);
                TIFFTag apertureTag = tags.get(TIFF.TAG_FNumber);
                TIFFTag isoTag = tags.get(TIFF.TAG_ISOSpeedRatings);
                
                if (exposureTimeTag != null && apertureTag != null && isoTag != null) {
                    try {
                        // Read from TIFF tags
                        double exposureTime = exposureTimeTag.getFloat();  // Exposure time in seconds
                        double aperture = apertureTag.getRational().floatValue();  // F-number (aperture)
                        int iso = isoTag.getInt();  // ISO speed rating
                        
                        Log.d(TAG, "TIFF values: exposureTime=" + exposureTime + "s, aperture=f/" + aperture + ", ISO=" + iso);
                        
                        // Calculate Light Value: LV = log2(f²/t) - log2(ISO/100)
                        double fSquared = aperture * aperture;
                        double lightValue = (Math.log(fSquared / exposureTime) / Math.log(2.0)) - 
                                           (Math.log(iso / 100.0) / Math.log(2.0));
                        sensor.lightValue = (float) lightValue;
                        lightValueCalculated = true;
                        Log.d(TAG, "Light Value: " + sensor.lightValue + " (calculated from TIFF tags: exposure=" + 
                              exposureTime + "s, f/" + aperture + ", ISO=" + iso + ")");
                    } catch (Exception e) {
                        Log.w(TAG, "Could not read exposure parameters from TIFF tags for Light Value calculation: " + e.getMessage());
                    }
                } else {
                    // Fallback: try ExifInterface (might work for some files)
                    String exposureTimeStr = reader.exif != null ? reader.exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) : null;
                    String apertureStr = reader.exif != null ? reader.exif.getAttribute(ExifInterface.TAG_F_NUMBER) : null;
                    String isoStr = reader.exif != null ? reader.exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY) : null;
                    
                    Log.d(TAG, "TIFF tags not found, trying EXIF: exposureTime=" + exposureTimeStr + ", aperture=" + apertureStr + ", ISO=" + isoStr);
                    
                    if (exposureTimeStr != null && apertureStr != null && isoStr != null) {
                        try {
                            double exposureTime = Double.parseDouble(exposureTimeStr);
                            double aperture = Double.parseDouble(apertureStr);
                            double iso = Double.parseDouble(isoStr);
                            
                            // Calculate Light Value: LV = log2(f²/t) - log2(ISO/100)
                            double fSquared = aperture * aperture;
                            double lightValue = (Math.log(fSquared / exposureTime) / Math.log(2.0)) - 
                                               (Math.log(iso / 100.0) / Math.log(2.0));
                            sensor.lightValue = (float) lightValue;
                            lightValueCalculated = true;
                            Log.d(TAG, "Light Value: " + sensor.lightValue + " (calculated from EXIF: exposure=" + 
                                  exposureTime + "s, f/" + aperture + ", ISO=" + iso + ")");
                        } catch (NumberFormatException e) {
                            Log.w(TAG, "Could not parse EXIF exposure parameters for Light Value calculation: " + e.getMessage());
                        }
                    } else {
                        Log.w(TAG, "Missing exposure parameters in both TIFF tags and EXIF for Light Value calculation");
                    }
                }
            }
        } else {
            Log.w(TAG, "EXIF reader is null, cannot calculate Light Value");
        }
        
        // Fallback: If lightValue couldn't be calculated, use heuristics based on image characteristics
        // High baselineExposure + high ISO or long exposure suggests night mode
        if (!lightValueCalculated && sensor.baselineExposure > 2.0f) {
            // For HDR images with high baseline exposure, assume it might be a night scene
            // Use a conservative negative light value to trigger night mode processing
            sensor.lightValue = -1.0f;
            Log.d(TAG, "Light Value fallback: using -1.0 (high baselineExposure=" + sensor.baselineExposure + 
                      " EV suggests night scene)");
        }
        
        // Log night mode detection
        if (sensor.lightValue < 0f) {
            Log.d(TAG, "Night mode detected (Light Value < 0: " + sensor.lightValue + ")");
        }

        // =====================================================
        // Parse DNG Camera Profile tags
        // These define the camera manufacturer's intended color rendering
        // =====================================================
        
        // Profile name
        TIFFTag profileNameTag = tags.get(TIFF.TAG_ProfileName);
        if (profileNameTag != null) {
            sensor.profileName = profileNameTag.toString();
            Log.d(TAG, "ProfileName: " + sensor.profileName);
        }
        
        TIFFTag asShotProfileNameTag = tags.get(TIFF.TAG_AsShotProfileName);
        if (asShotProfileNameTag != null) {
            sensor.asShotProfileName = asShotProfileNameTag.toString();
            Log.d(TAG, "AsShotProfileName: " + sensor.asShotProfileName);
        }
        
        // ProfileHueSatMap - per-hue HSL adjustments
        // This is how camera manufacturers create their signature color look
        TIFFTag hueSatDimsTag = tags.get(TIFF.TAG_ProfileHueSatMapDims);
        if (hueSatDimsTag != null) {
            sensor.profileHueSatMapDims = hueSatDimsTag.getIntArray();
            Log.d(TAG, "ProfileHueSatMapDims: " + sensor.profileHueSatMapDims[0] + "x" + 
                       sensor.profileHueSatMapDims[1] + "x" + sensor.profileHueSatMapDims[2]);
            
            TIFFTag hueSatData1Tag = tags.get(TIFF.TAG_ProfileHueSatMapData1);
            if (hueSatData1Tag != null) {
                sensor.profileHueSatMapData1 = hueSatData1Tag.getFloatArray();
                Log.d(TAG, "ProfileHueSatMapData1: " + sensor.profileHueSatMapData1.length + " values");
            }
            
            TIFFTag hueSatData2Tag = tags.get(TIFF.TAG_ProfileHueSatMapData2);
            if (hueSatData2Tag != null) {
                sensor.profileHueSatMapData2 = hueSatData2Tag.getFloatArray();
                Log.d(TAG, "ProfileHueSatMapData2: " + sensor.profileHueSatMapData2.length + " values");
            }
        }
        
        // ProfileToneCurve - the camera's contrast/tone response curve
        TIFFTag toneCurveTag = tags.get(TIFF.TAG_ProfileToneCurve);
        if (toneCurveTag != null) {
            sensor.profileToneCurve = toneCurveTag.getFloatArray();
            Log.d(TAG, "ProfileToneCurve: " + (sensor.profileToneCurve.length / 2) + " points");
        }
        
        // ProfileLookTable - 3D color LUT for color grading
        TIFFTag lookTableDimsTag = tags.get(TIFF.TAG_ProfileLookTableDims);
        if (lookTableDimsTag != null) {
            sensor.profileLookTableDims = lookTableDimsTag.getIntArray();
            Log.d(TAG, "ProfileLookTableDims: " + sensor.profileLookTableDims[0] + "x" + 
                       sensor.profileLookTableDims[1] + "x" + sensor.profileLookTableDims[2]);
            
            TIFFTag lookTableDataTag = tags.get(TIFF.TAG_ProfileLookTableData);
            if (lookTableDataTag != null) {
                sensor.profileLookTableData = lookTableDataTag.getFloatArray();
                Log.d(TAG, "ProfileLookTableData: " + sensor.profileLookTableData.length + " values");
            }
            
            TIFFTag lookTableEncodingTag = tags.get(TIFF.TAG_ProfileLookTableEncoding);
            if (lookTableEncodingTag != null) {
                sensor.profileLookTableEncoding = lookTableEncodingTag.getInt();
                Log.d(TAG, "ProfileLookTableEncoding: " + sensor.profileLookTableEncoding);
            }
        }
        
        // Profile embed policy
        TIFFTag embedPolicyTag = tags.get(TIFF.TAG_ProfileEmbedPolicy);
        if (embedPolicyTag != null) {
            sensor.profileEmbedPolicy = embedPolicyTag.getInt();
        }
        
        // Log summary of profile availability
        if (sensor.hasProfile()) {
            Log.i(TAG, "DNG Profile found: " + 
                  (sensor.hasHueSatMap() ? "HueSatMap " : "") +
                  (sensor.hasToneCurve() ? "ToneCurve " : "") +
                  (sensor.hasLookTable() ? "LookTable " : ""));
        } else {
            Log.d(TAG, "No embedded DNG profile found");
        }
        
        // Store preview image for tone matching (extracted earlier)
        if (previewBitmap != null) {
            sensor.previewImage = previewBitmap;
            sensor.previewWidth = previewBitmap.getWidth();
            sensor.previewHeight = previewBitmap.getHeight();
            Log.i(TAG, "Preview image available for tone matching: " + 
                  sensor.previewWidth + "x" + sensor.previewHeight);
        }

        int[] defaultCropOrigin = getTag(tags, TIFF.TAG_DefaultCropOrigin).getIntArray();
        sensor.outputOffsetX = defaultCropOrigin[0];
        sensor.outputOffsetY = defaultCropOrigin[1];

        int[] defaultCropSize = getTag(tags, TIFF.TAG_DefaultCropSize).getIntArray();
        Bitmap argbOutput = Bitmap.createBitmap(defaultCropSize[0], defaultCropSize[1], Bitmap.Config.ARGB_8888);

        TIFFTag Op2 = tags.get(TIFF.TAG_OpcodeList2);
        if (Op2 != null) {
            Object[] opParsed = OpParser.parseAll(Op2.getByteArray());
            OpParser.GainMap[] mapPlanes = new OpParser.GainMap[4];

            for (Object o : opParsed) {
                Log.i(TAG, "Parsed opcode: " + o.getClass().getSimpleName());
                if (o instanceof OpParser.GainMap) {
                    OpParser.GainMap mapPlane = (OpParser.GainMap) o;
                    mapPlanes[(mapPlane.top << 1) | mapPlane.left] = mapPlane;
                }
            }

            if (mapPlanes[0] != null && mapPlanes[1] != null
                    && mapPlanes[2] != null && mapPlanes[3] != null) {
                sensor.gainMapSize = new int[] { mapPlanes[0].mapPointsH, mapPlanes[0].mapPointsV };
                sensor.gainMap = new float[sensor.gainMapSize[0] * sensor.gainMapSize[1] * 4];
                for (int i = 0; i < sensor.gainMap.length; i++) {
                    sensor.gainMap[i] = mapPlanes[i % 4].px[i / 4];
                }
            }
        }

        ProcessParams process = ProcessParams.getPreset(Preferences.postProcess());
        process.saturationMap = new float[] {
                pref.saturationRed.get(),
                pref.saturationYellow.get(),
                pref.saturationGreen.get(),
                pref.saturationCyan.get(),
                pref.saturationBlue.get(),
                pref.saturationIndigo.get(),
                pref.saturationViolet.get(),
                pref.saturationMagenta.get()
        };
        process.satLimit = pref.saturationLimit.get();
        process.exposeFuse = pref.exposeFuse.get();
        process.exposeFusionMethod = pref.exposeFusionMethod.get();
        process.lce = pref.lce.get();
        process.lceMethod = pref.lceMethod.get();
        
        // LCE parameters
        process.lceRadiusWeak = pref.lceRadiusWeak.get();
        process.lceRadiusMedium = pref.lceRadiusMedium.get();
        process.lceRadiusStrong = pref.lceRadiusStrong.get();
        process.lceRadiusXfine = pref.lceRadiusXfine.get();
        process.lceRadiusFine = pref.lceRadiusFine.get();
        process.lceRadiusXstrong = pref.lceRadiusXstrong.get();
        
        process.lceStrengthWeak = pref.lceStrengthWeak.get();
        process.lceStrengthMedium = pref.lceStrengthMedium.get();
        process.lceStrengthStrong = pref.lceStrengthStrong.get();
        process.lceStrengthXfine = pref.lceStrengthXfine.get();
        process.lceStrengthFine = pref.lceStrengthFine.get();
        process.lceStrengthXstrong = pref.lceStrengthXstrong.get();
        
        process.lceLimitWeak = pref.lceLimitWeak.get();
        process.lceLimitMedium = pref.lceLimitMedium.get();
        process.lceLimitStrong = pref.lceLimitStrong.get();
        process.lceLimitXfine = pref.lceLimitXfine.get();
        process.lceLimitFine = pref.lceLimitFine.get();
        process.lceLimitXstrong = pref.lceLimitXstrong.get();
        
        process.ahe = pref.ahe.get();
        process.edgeAwareHistEq = pref.edgeAwareHistEq.get();
        process.noiseReduce = true;  // Always enabled, no toggle
        process.useReferencePreview = pref.referencePreview.get();
        process.histMatchStrength = pref.histMatchStrength.get();
        process.demosaicingMethod = pref.demosaicingMethod.get();
        // Parse string preference to int (0=None, 1=Reinhard, 2=ACES Filmic, 3=Uncharted 2, 4=Improved Rational)
        // Handle both numeric strings and boolean strings (from migration)
        String compressionStr = pref.baselineExposureCompression.get();
        try {
            process.baselineExposureCompression = Integer.parseInt(compressionStr);
        } catch (NumberFormatException e) {
            // Handle boolean strings that might have been converted to "true"/"false"
            if ("true".equalsIgnoreCase(compressionStr)) {
                process.baselineExposureCompression = 1;  // Reinhard
            } else if ("false".equalsIgnoreCase(compressionStr)) {
                process.baselineExposureCompression = 4;  // Improved Rational (was Exposure Slider)
            } else {
                // Fallback to Reinhard (1) if parsing fails
                process.baselineExposureCompression = 1;
            }
        }
        
        // HDR compression method
        String hdrCompressionStr = pref.hdrCompressionMethod.get();
        try {
            process.hdrCompressionMethod = Integer.parseInt(hdrCompressionStr);
        } catch (NumberFormatException e) {
            // Fallback to Reinhard (0) if parsing fails
            process.hdrCompressionMethod = 0;
        }
        
        // Sharpening: slider is now additive (0-100 range, default 0)
        // Convert from 0-100 range to 0.0-0.5 range for sharpenFactor
        float sharpenAdd = pref.sharpening.get() / 200f;  // 0-100 -> 0.0-0.5
        process.sharpenFactor += sharpenAdd;
        
        // Noise reduction: slider is now additive (0-100 range, default 0)
        // Convert from 0-100 range to 0-100 range for denoiseFactor
        int denoiseAdd = pref.noiseReduction.get().intValue();  // 0-100 -> 0-100
        process.denoiseFactor += denoiseAdd;
        
        // Force noise reduction: apply at full strength regardless of EXIF metadata
        process.forceNoiseReduction = pref.forceNoiseReduction.get();
        
        // Wavelet noise reduction: use edge-aware wavelet denoising instead of bilateral
        process.waveletNoiseReduction = pref.waveletNoiseReduction.get();

        // MAT mode options
        process.matGreenToYellowShift = pref.matGreenToYellowShift.get();
        process.matYellowToWarmShift = pref.matYellowToWarmShift.get();

        // Local Laplacian Filter (darktable-style local contrast)
        process.localLaplacianEnabled = pref.localLaplacianEnabled.get();
        process.localLaplacianShadows = pref.localLaplacianShadows.get();
        process.localLaplacianHighlights = pref.localLaplacianHighlights.get();
        process.localLaplacianClarity = pref.localLaplacianClarity.get();
        process.localLaplacianSigma = pref.localLaplacianSigma.get();
        process.localLaplacianAutoTune = pref.localLaplacianAutoTune.get();
        
        // Tone Equalizer (EV-band based adjustment)
        process.toneEqualizerEnabled = pref.toneEqualizerEnabled.get();
        process.toneEqBlacks = pref.toneEqBlacks.get();
        process.toneEqDeepShadows = pref.toneEqDeepShadows.get();
        process.toneEqShadows = pref.toneEqShadows.get();
        process.toneEqLightShadows = pref.toneEqLightShadows.get();
        process.toneEqMidtones = pref.toneEqMidtones.get();
        process.toneEqDarkHighlights = pref.toneEqDarkHighlights.get();
        process.toneEqHighlights = pref.toneEqHighlights.get();
        process.toneEqWhites = pref.toneEqWhites.get();
        process.toneEqSpeculars = pref.toneEqSpeculars.get();
        process.toneEqSmoothing = pref.toneEqSmoothing.get();
        process.toneEqFeathering = pref.toneEqFeathering.get();
        process.toneEqualizerAutoTune = pref.toneEqualizerAutoTune.get();

        // Color Transform matrix
        process.colorTransform = new float[] {
                pref.colorTransformRR.get(),
                pref.colorTransformRG.get(),
                pref.colorTransformRB.get(),
                pref.colorTransformGR.get(),
                pref.colorTransformGG.get(),
                pref.colorTransformGB.get(),
                pref.colorTransformBR.get(),
                pref.colorTransformBG.get(),
                pref.colorTransformBB.get()
        };

        // Tone adjustments (Lightroom-style)
        process.toneExposure = pref.toneExposure.get();
        process.toneHighlights = pref.toneHighlights.get();
        process.toneShadows = pref.toneShadows.get();
        process.toneWhites = pref.toneWhites.get();
        process.toneContrast = pref.toneContrast.get();
        process.toneBlacks = pref.toneBlacks.get();
        process.toneTexture = pref.toneTexture.get();
        process.toneClarity = pref.toneClarity.get();
        process.toneDehaze = pref.toneDehaze.get();
        process.toneVibrance = pref.toneVibrance.get();
        process.toneSaturation = pref.toneSaturation.get();
        
        // External LUT file path
        String lutPath = pref.externalLutPath.get();
        if (lutPath != null && !lutPath.isEmpty()) {
            process.externalLutPath = lutPath;
        } else {
            process.externalLutPath = null;
        }
        
        // Load user-defined tone curve if enabled
        process.userToneCurve = ToneCurveActivity.getSavedCurvePoints(Utilities.prefs(mContext));
        // Check if tone curves are enabled (user toggle)
        process.toneCurveEnabled = ToneCurveActivity.isUserCurveEnabled(Utilities.prefs(mContext));
        
        // Adaptive saturation settings (Light Value based)
        // Apply user's histFactor preference (overrides preset value)
        process.histFactor = pref.histFactor.get();
        
        // Apply adaptive saturation from user preferences
        // If adaptive saturation curve is enabled, use the Light Value to compute strength
        if (pref.adaptiveSaturationCurveEnabled.get()) {
            float strength = AdaptiveSaturationCurveActivity.lookupStrength(
                    Utilities.prefs(mContext), sensor.lightValue);
            float power = pref.adaptiveSaturationPower.get();
            process.adaptiveSaturation = new float[] { strength, power };
            Log.d(TAG, "Adaptive saturation (LV-based): strength=" + strength + 
                      " (from curve at LV=" + sensor.lightValue + "), power=" + power);
        } else {
            // Use the preset's adaptive saturation, but still allow user to override the power
            float power = pref.adaptiveSaturationPower.get();
            process.adaptiveSaturation[1] = power;
            Log.d(TAG, "Adaptive saturation (preset): strength=" + process.adaptiveSaturation[0] + 
                      ", power=" + power + " (from preferences)");
        }

        // Override sensor and process settings with model specific ones
        TIFFTag modelTag = tags.get(TIFF.TAG_Model);
        DeviceMap.Device device = DeviceMap.get(modelTag == null ? "" : modelTag.toString());
        device.sensorCorrection(tags, sensor);
        device.processCorrection(tags, process);

        if (!pref.gainMap.get()) {
            sensor.gainMap = null;
            sensor.gainMapSize = null;
        }

        if (sensor.calibrationTransform1 == null || sensor.calibrationTransform2 == null) {
            sensor.calibrationTransform1 = DIAGONAL;
            sensor.calibrationTransform2 = DIAGONAL;
        }

        ShaderLoader loader = ShaderLoader.getInstance(mContext);

        final int[] steps = { 0 };
        final long[] lastTimestamp = { System.currentTimeMillis() };

        // Check if we should create Ultra HDR JPEG
        // Only create UHDR if:
        // 1. User has enabled it in preferences
        // 2. Image has HDR content (natural or synthetic):
        //    - Natural HDR: baseline exposure > 0.5 EV (compressed highlights)
        //    - Synthetic HDR: baseline exposure <= 0.0 EV (expanded highlights)
        //    - Exposure fusion: always creates HDR content
        // 3. Android version supports it (API 34+)
        boolean ultraHdrEnabled = pref.ultraHdr.get();
        boolean hasNaturalHdr = sensor.baselineExposure > 0.5f;
        boolean hasSyntheticHdr = sensor.baselineExposure <= 0.0f;  // Synthetic expansion applies
        boolean hasExposureFusion = process.exposeFuse;
        boolean hasHdrContent = hasNaturalHdr || hasSyntheticHdr || hasExposureFusion;
        boolean androidSupports = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
        boolean shouldCreateUHDR = ultraHdrEnabled && hasHdrContent && androidSupports;
        
        // Check if we should save HDR frames (only when Ultra HDR is enabled)
        boolean saveHdrGainMap = pref.saveHdrGainMap.get();
        // Only capture HDR when UHDR is enabled (either to create UHDR JPEG or to save HDR gain map)
        boolean shouldCaptureHdr = shouldCreateUHDR || (saveHdrGainMap && ultraHdrEnabled);
        
        Log.d(TAG, "Ultra HDR check: enabled=" + ultraHdrEnabled + 
              ", hasHdrContent=" + hasHdrContent + " (naturalHdr=" + hasNaturalHdr + 
              ", syntheticHdr=" + hasSyntheticHdr + ", exposeFuse=" + hasExposureFusion +
              ", baselineExposure=" + sensor.baselineExposure + " EV), androidSupports=" + androidSupports + 
              " (API " + Build.VERSION.SDK_INT + "), shouldCreate=" + shouldCreateUHDR +
              ", saveHdrGainMap=" + saveHdrGainMap + ", shouldCaptureHdr=" + shouldCaptureHdr);
        
        // Pass UHDR enabled flag to ProcessParams for shader optimizations
        process.ultraHdrEnabled = shouldCreateUHDR;
        
        // Set output base name for saving debug frames (e.g., exposure fusion frames)
        // Compute from input filename: remove .dng extension
        String outputBaseName = mFile;
        int lastDot = outputBaseName.lastIndexOf('.');
        if (lastDot > 0) {
            outputBaseName = outputBaseName.substring(0, lastDot);
        }
        // Apply suffix preference if enabled (IMG -> MAT)
        if (pref.suffix.get() && outputBaseName.startsWith("IMG")) {
            outputBaseName = "MAT" + outputBaseName.substring(3);
        }
        process.outputBaseName = outputBaseName;
        
        // Set context for LUT file loading (needed for URI-based files)
        process.context = mContext;
        
        Bitmap hdrBitmap = null;

        // Create GL context for this processing run - will be cleaned up automatically
        try (GLContext glContext = new GLContext()) {
            // Choose pipeline based on data format
            if (sensor.isLinearRaw) {
                Log.d(TAG, "Using LinearRawPipeline for demosaiced RGB data");
                try (LinearRawPipeline pipeline = new LinearRawPipeline(
                        glContext, sensor, process, rawImageInput, argbOutput, loader)) {
                    pipeline.execute((completed, total, tag) -> {
                        if (completed > 0) {
                            long ts = System.currentTimeMillis();
                            Log.w(TAG, "Took " + (ts - lastTimestamp[0]) + " ms");
                            lastTimestamp[0] = ts;
                        }

                        steps[0] = total;
                        NotifHandler.progress(mContext, total + ADD_STEPS, completed);
                        Log.w(TAG, "Linear Raw conversion step " + tag + ": " + completed + "/" + total);
                    });
                    
                    // Capture HDR version if needed (before pipeline closes)
                    if (shouldCaptureHdr) {
                        try {
                            Log.d(TAG, "Attempting to capture HDR version from LinearRawPipeline...");
                            hdrBitmap = pipeline.captureHdrOutput();
                            if (hdrBitmap != null) {
                                Log.i(TAG, "Captured HDR version: " + 
                                      hdrBitmap.getWidth() + "x" + hdrBitmap.getHeight());
                            } else {
                                Log.w(TAG, "HDR capture returned null from LinearRawPipeline");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to capture HDR version from LinearRawPipeline", e);
                            hdrBitmap = null;
                        }
                    } else {
                        Log.d(TAG, "Skipping HDR capture (shouldCaptureHdr=false)");
                    }
                }
            } else {
                try (StagePipeline pipeline = new StagePipeline(
                        glContext, sensor, process, rawImageInput, argbOutput, loader)) {
                    pipeline.execute((completed, total, tag) -> {
                        if (completed > 0) {
                            long ts = System.currentTimeMillis();
                            Log.w(TAG, "Took " + (ts - lastTimestamp[0]) + " ms");
                            lastTimestamp[0] = ts;
                        }

                        steps[0] = total;
                        NotifHandler.progress(mContext, total + ADD_STEPS, completed);
                        Log.w(TAG, "Raw conversion step " + tag + ": " + completed + "/" + total);
                    });
                    
                    // Capture HDR version if needed (before pipeline closes)
                    if (shouldCaptureHdr) {
                        try {
                            Log.d(TAG, "Attempting to capture HDR version...");
                            hdrBitmap = pipeline.captureHdrOutput();
                            if (hdrBitmap != null) {
                                Log.i(TAG, "Captured HDR version: " + 
                                      hdrBitmap.getWidth() + "x" + hdrBitmap.getHeight());
                            } else {
                                Log.w(TAG, "HDR capture returned null");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to capture HDR version", e);
                            hdrBitmap = null;
                        }
                    } else {
                        Log.d(TAG, "Skipping HDR capture (shouldCaptureHdr=false)");
                    }
                }
            }
        } // GLContext is automatically closed here, releasing all GL resources

        // Explicitly clear large raw image data to help GC reclaim memory
        // This is especially important when processing multiple files
        rawImageInput = null;
        
        // Suggest GC to run after processing each file to free native memory
        // DirectByteBuffers hold native memory that needs to be collected
        System.gc();

        NotifHandler.progress(mContext, steps[0] + ADD_STEPS, steps[0] + STEP_SAVE);
        String savePath = Path.processedPath(pref.savePath.get(), mFile);
        int jpegQuality = pref.jpegQuality.get();
        // Clamp JPEG quality to valid range [0, 100]
        jpegQuality = Math.max(0, Math.min(100, jpegQuality));
        
        // Check if we should copy gain map from embedded JPEG instead of generating it
        boolean copyGainMapFromJpeg = pref.copyGainMapFromJpeg.get() && embeddedJpegData != null;
        
        // Try to create Ultra HDR JPEG if:
        // 1. User enabled Ultra HDR (and we'll generate gain map), OR
        // 2. User enabled copy gain map from JPEG (and we have embedded JPEG data)
        boolean shouldCreateUHDRWithGainMap = (shouldCreateUHDR && hdrBitmap != null) || copyGainMapFromJpeg;
        
        Log.d(TAG, "Ultra HDR creation check: shouldCreateUHDR=" + shouldCreateUHDR + 
              ", hdrBitmap=" + (hdrBitmap != null) + ", copyGainMapFromJpeg=" + copyGainMapFromJpeg +
              ", shouldCreateUHDRWithGainMap=" + shouldCreateUHDRWithGainMap);
        
        if (shouldCreateUHDRWithGainMap) {
            try {
                Bitmap gainMap = null;
                
                if (copyGainMapFromJpeg) {
                    // Extract gain map from embedded JPEG
                    Log.d(TAG, "Extracting gain map from embedded JPEG...");
                    gainMap = HdrJpegVerifier.extractGainMapFromJpegData(embeddedJpegData);
                    
                    // If embedded JPEG extraction failed, try original JPEG file as fallback
                    if (gainMap == null) {
                        String jpegPath = Path.getOriginalJpegPath(pref.jpegLocation.get(), mFile);
                        if (jpegPath != null) {
                            File jpegFile = new File(jpegPath);
                            if (jpegFile.exists()) {
                                Log.d(TAG, "Failed to extract gain map from embedded JPEG, trying original JPEG file: " + jpegPath);
                                gainMap = HdrJpegVerifier.extractGainMapFromJpeg(jpegPath);
                                if (gainMap != null) {
                                    Log.i(TAG, "Extracted gain map from original JPEG file: " + 
                                          gainMap.getWidth() + "x" + gainMap.getHeight());
                                } else {
                                    Log.d(TAG, "No gain map found in original JPEG file either");
                                }
                            } else {
                                Log.d(TAG, "Original JPEG file not found: " + jpegPath);
                            }
                        } else {
                            Log.d(TAG, "No original JPEG path available");
                        }
                    }
                    
                    if (gainMap != null) {
                        Log.i(TAG, "Extracted gain map from JPEG: " + 
                              gainMap.getWidth() + "x" + gainMap.getHeight());
                    } else {
                        Log.w(TAG, "Failed to extract gain map from embedded or original JPEG, falling back to standard JPEG");
                        // Fall through to standard JPEG writing
                    }
                } else if (shouldCreateUHDR && hdrBitmap != null) {
                    // With libultrahdr, we don't need to generate gain map separately
                    // The encoder will generate it automatically from SDR and HDR images
                    // (libultrahdr is much faster than our Java implementation)
                    Log.d(TAG, "HDR bitmap available, libultrahdr will generate gain map automatically");
                }
                
                // Save HDR frame to file if enabled in preferences (for debugging)
                // Note: The gain map is now generated by libultrahdr and embedded in the JPEG
                // To extract it, use the UltraHDRDecoder on the output file
                if (saveHdrGainMap && hdrBitmap != null) {
                    try {
                        File outputDir = new File(savePath).getParentFile();
                        String baseName = new File(savePath).getName();
                        // Remove extension
                        int baseNameLastDot = baseName.lastIndexOf('.');
                        String nameWithoutExt = baseNameLastDot > 0 ? baseName.substring(0, baseNameLastDot) : baseName;
                        
                        File hdrFile = new File(outputDir, nameWithoutExt + "_hdr.png");
                        try (FileOutputStream hdrOut = new FileOutputStream(hdrFile)) {
                            hdrBitmap.compress(Bitmap.CompressFormat.PNG, 100, hdrOut);
                            Log.i(TAG, "Saved HDR bitmap (log-encoded) to: " + hdrFile.getAbsolutePath());
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to save HDR file", e);
                        // Don't fail the whole process if debug file saving fails
                    }
                }
                
                if (hdrBitmap != null) {
                    Log.d(TAG, "Writing Ultra HDR JPEG to: " + savePath);
                    File outputFile = new File(savePath);
                    
                    // Use libultrahdr to encode Ultra HDR JPEG
                    // The encoder automatically generates the gain map from SDR and HDR images
                    // Pass baselineExposure to configure gain map thresholds appropriately
                    if (UltraHdrEncoder.encodeUltraHdr(argbOutput, hdrBitmap, outputFile, jpegQuality, sensor.baselineExposure)) {
                        Log.i(TAG, "Successfully encoded Ultra HDR JPEG using libultrahdr: " + savePath);
                        
                        // Copy EXIF data (including orientation) to the HDR JPEG
                        copyExifDataToFile(savePath, reader, tags, pref, steps);
                        
                        // Verify the written file
                        Log.d(TAG, "Verifying Ultra HDR JPEG...");
                        boolean verified = HdrJpegVerifier.verifyUltraHdrJpeg(savePath);
                        long fileSize = HdrJpegVerifier.getFileSize(savePath);
                        
                        if (verified) {
                            Log.i(TAG, "✓ Verified: Output file is a valid Ultra HDR JPEG " +
                                  "(size: " + fileSize + " bytes)");
                        } else {
                            Log.w(TAG, "✗ Warning: Output file verification failed - " +
                                  "file may not be Ultra HDR (size: " + fileSize + " bytes)");
                            // Still continue - verification may fail due to API differences
                        }
                        
                        // Recycle bitmaps
                        if (gainMap != null) {
                            gainMap.recycle();
                        }
                        hdrBitmap.recycle();
                        hdrBitmap = null;
                        argbOutput.recycle();
                        return; // Success, skip standard JPEG fallback
                    } else {
                        Log.w(TAG, "Failed to encode Ultra HDR JPEG using libultrahdr, falling back to standard JPEG");
                    }
                } else if (gainMap != null) {
                    // Fallback: if we have a pre-computed gain map (e.g., copied from embedded JPEG)
                    // but no HDR bitmap, use the old method
                    Log.w(TAG, "No HDR bitmap available, using fallback method with pre-computed gain map");
                    File outputFile = new File(savePath);
                    if (HdrJpegWriter.writeHdrJpeg(argbOutput, gainMap, outputFile, jpegQuality)) {
                        Log.i(TAG, "Successfully wrote Ultra HDR JPEG (fallback method): " + savePath);
                        
                        // Copy EXIF data (including orientation) to the HDR JPEG
                        copyExifDataToFile(savePath, reader, tags, pref, steps);
                        
                        gainMap.recycle();
                        argbOutput.recycle();
                        return; // Success, skip standard JPEG fallback
                    } else {
                        Log.w(TAG, "Failed to write Ultra HDR JPEG (fallback), falling back to standard JPEG");
                        gainMap.recycle();
                    }
                }
                // Recycle hdrBitmap if it exists (saving is handled inside Ultra HDR block)
                if (hdrBitmap != null) {
                    hdrBitmap.recycle();
                    hdrBitmap = null;
                }
            } catch (Throwable e) {
                // Catch Throwable (not just Exception) to also handle OutOfMemoryError
                // which can occur when processing large images for Ultra HDR encoding
                Log.e(TAG, "Error creating Ultra HDR JPEG, falling back to standard JPEG", e);
                // Recycle hdrBitmap if it exists
                if (hdrBitmap != null) {
                    try {
                        hdrBitmap.recycle();
                    } catch (Exception recycleEx) {
                        Log.w(TAG, "Failed to recycle hdrBitmap", recycleEx);
                    }
                    hdrBitmap = null;
                }
                // Try to help GC recover from OOM
                if (e instanceof OutOfMemoryError) {
                    System.gc();
                }
            }
        } else {
            if (!shouldCreateUHDR) {
                Log.d(TAG, "Skipping Ultra HDR creation (conditions not met)");
            } else if (hdrBitmap == null) {
                Log.w(TAG, "Skipping Ultra HDR creation (HDR bitmap capture failed)");
            }
        }
        
        // Recycle HDR bitmap if it exists (saving is handled inside Ultra HDR block)
        if (hdrBitmap != null) {
            hdrBitmap.recycle();
            hdrBitmap = null;
        }
        
        // Fall back to standard JPEG
        try (FileOutputStream out = new FileOutputStream(savePath)) {
            argbOutput.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save JPEG to " + savePath, e);
        }
        argbOutput.recycle();

        // Copy EXIF data (including orientation) to the output file
        copyExifDataToFile(savePath, reader, tags, pref, steps);

        mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                Uri.fromFile(new File(savePath))));

        NotifHandler.progress(mContext, steps[0] + ADD_STEPS, steps[0] + ADD_STEPS);
    }

    /**
     * Extract embedded JPEG preview from IFD0.
     * DNGs typically store a JPEG preview in IFD0 when NewSubfileType = 1.
     * The preview is useful for tone matching - it shows the camera's intended rendering.
     * 
     * @param wrap ByteBuffer containing the DNG data
     * @param tags IFD tags from IFD0
     * @return Bitmap of the preview, or null if extraction fails
     */
    /**
     * Check if image dimensions are too large for preview extraction.
     * Skip extraction for images > 12MP (longer side > 5000 pixels) to avoid memory issues.
     */
    private boolean shouldSkipPreviewExtraction(SparseArray<TIFFTag> tags) {
        try {
            TIFFTag widthTag = tags.get(TIFF.TAG_ImageWidth);
            TIFFTag heightTag = tags.get(TIFF.TAG_ImageLength);
            
            if (widthTag != null && heightTag != null) {
                int width = widthTag.getInt();
                int height = heightTag.getInt();
                int longerSide = Math.max(width, height);
                
                // Skip if longer side > 5000 pixels (> 12MP for typical aspect ratios)
                if (longerSide > 5000) {
                    Log.d(TAG, "Skipping preview extraction for large image: " + width + "x" + height + " (longer side: " + longerSide + " > 5000)");
                    return true;
                }
            }
        } catch (Exception e) {
            // If we can't determine dimensions, allow extraction (fail-safe)
            Log.w(TAG, "Could not check image dimensions for preview extraction: " + e.getMessage());
        }
        return false;
    }
    
    private Bitmap extractPreviewJpeg(ByteBuffer wrap, SparseArray<TIFFTag> tags) {
        // Skip extraction for large images to avoid memory issues
        if (shouldSkipPreviewExtraction(tags)) {
            return null;
        }
        try {
            // Method 1: Try JpegInterchangeFormat (common for thumbnails)
            TIFFTag jpegOffsetTag = tags.get(TIFF.TAG_JpegInterchangeFormat);
            TIFFTag jpegLengthTag = tags.get(TIFF.TAG_JpegInterchangeFormatLength);
            
            if (jpegOffsetTag != null && jpegLengthTag != null) {
                int offset = jpegOffsetTag.getInt();
                int length = jpegLengthTag.getInt();
                
                if (offset > 0 && length > 0) {
                    byte[] jpegData = new byte[length];
                    ((ByteBuffer) wrap.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(offset))
                            .get(jpegData);
                    
                    Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
                    if (bitmap != null) {
                        Log.d(TAG, "Extracted preview via JpegInterchangeFormat");
                        return bitmap;
                    }
                }
            }
            
            // Method 2: Try StripOffsets with JPEG compression
            TIFFTag compressionTag = tags.get(TIFF.TAG_Compression);
            if (compressionTag != null && compressionTag.getInt() == TIFF.COMPRESSION_JPEG) {
                TIFFTag stripOffsetsTag = tags.get(TIFF.TAG_StripOffsets);
                TIFFTag stripByteCountsTag = tags.get(TIFF.TAG_StripByteCounts);
                
                if (stripOffsetsTag != null && stripByteCountsTag != null) {
                    int[] offsets = stripOffsetsTag.getIntArray();
                    int[] byteCounts = stripByteCountsTag.getIntArray();
                    
                    if (offsets.length > 0 && byteCounts.length > 0) {
                        // For JPEG preview, usually all data is in first strip
                        int totalSize = 0;
                        for (int count : byteCounts) {
                            totalSize += count;
                        }
                        
                        byte[] jpegData = new byte[totalSize];
                        int pos = 0;
                        for (int i = 0; i < offsets.length; i++) {
                            ((ByteBuffer) wrap.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(offsets[i]))
                                    .get(jpegData, pos, byteCounts[i]);
                            pos += byteCounts[i];
                        }
                        
                        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
                        if (bitmap != null) {
                            Log.d(TAG, "Extracted preview via StripOffsets");
                            return bitmap;
                        }
                    }
                }
            }
            
            Log.d(TAG, "No embedded JPEG preview found in IFD0");
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract preview JPEG: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extracts embedded JPEG data (byte array) from IFD tags.
     * This is used to extract gain maps from Ultra HDR JPEG previews.
     * 
     * @param wrap ByteBuffer containing the DNG data
     * @param tags IFD tags
     * @return JPEG data as byte array, or null if extraction fails
     */
    private byte[] extractJpegData(ByteBuffer wrap, SparseArray<TIFFTag> tags) {
        try {
            // Method 1: Try JpegInterchangeFormat (common for thumbnails)
            TIFFTag jpegOffsetTag = tags.get(TIFF.TAG_JpegInterchangeFormat);
            TIFFTag jpegLengthTag = tags.get(TIFF.TAG_JpegInterchangeFormatLength);
            
            if (jpegOffsetTag != null && jpegLengthTag != null) {
                int offset = jpegOffsetTag.getInt();
                int length = jpegLengthTag.getInt();
                
                if (offset > 0 && length > 0) {
                    byte[] jpegData = new byte[length];
                    ((ByteBuffer) wrap.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(offset))
                            .get(jpegData);
                    Log.d(TAG, "Extracted JPEG data via JpegInterchangeFormat: " + length + " bytes");
                    return jpegData;
                } else {
                    Log.d(TAG, "JpegInterchangeFormat tags found but invalid: offset=" + offset + ", length=" + length);
                }
            } else {
                Log.d(TAG, "JpegInterchangeFormat tags missing: offsetTag=" + (jpegOffsetTag != null) + 
                      ", lengthTag=" + (jpegLengthTag != null));
            }
            
            // Method 2: Try StripOffsets with JPEG compression
            TIFFTag compressionTag = tags.get(TIFF.TAG_Compression);
            if (compressionTag != null) {
                int compression = compressionTag.getInt();
                Log.d(TAG, "Compression type: " + compression + " (JPEG=" + TIFF.COMPRESSION_JPEG + ")");
                
                if (compression == TIFF.COMPRESSION_JPEG) {
                    TIFFTag stripOffsetsTag = tags.get(TIFF.TAG_StripOffsets);
                    TIFFTag stripByteCountsTag = tags.get(TIFF.TAG_StripByteCounts);
                    
                    Log.d(TAG, "StripOffsets tags: offsetsTag=" + (stripOffsetsTag != null) + 
                          ", byteCountsTag=" + (stripByteCountsTag != null));
                    
                    if (stripOffsetsTag != null && stripByteCountsTag != null) {
                        try {
                            int[] offsets = stripOffsetsTag.getIntArray();
                            int[] byteCounts = stripByteCountsTag.getIntArray();
                            
                            Log.d(TAG, "StripOffsets arrays: offsets.length=" + 
                                  (offsets != null ? offsets.length : 0) + 
                                  ", byteCounts.length=" + (byteCounts != null ? byteCounts.length : 0));
                            
                            if (offsets != null && byteCounts != null && 
                                offsets.length > 0 && byteCounts.length > 0 && 
                                offsets.length == byteCounts.length) {
                                // For JPEG preview, usually all data is in first strip
                                int totalSize = 0;
                                for (int count : byteCounts) {
                                    totalSize += count;
                                }
                                
                                if (totalSize > 0) {
                                    byte[] jpegData = new byte[totalSize];
                                    int pos = 0;
                                    for (int i = 0; i < offsets.length; i++) {
                                        if (offsets[i] > 0 && byteCounts[i] > 0) {
                                            ((ByteBuffer) wrap.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(offsets[i]))
                                                    .get(jpegData, pos, byteCounts[i]);
                                            pos += byteCounts[i];
                                        }
                                    }
                                    Log.d(TAG, "Extracted JPEG data via StripOffsets: " + totalSize + " bytes");
                                    return jpegData;
                                } else {
                                    Log.d(TAG, "StripOffsets found but total size is 0");
                                }
                            } else {
                                Log.d(TAG, "StripOffsets arrays invalid or mismatched lengths");
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to read StripOffsets arrays: " + e.getMessage());
                        }
                    } else {
                        Log.d(TAG, "StripOffsets tags missing for JPEG compression");
                    }
                }
            } else {
                Log.d(TAG, "No Compression tag found");
            }
            
            Log.d(TAG, "No embedded JPEG data found in IFD");
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract JPEG data: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Decode JPEG-compressed tiles from DNG file (used for Xiaomi UltraRAW format).
     * Returns 16-bit RGB data in interleaved format.
     * Supports both standard 8-bit JPEG and lossless/high-bit-depth JPEG.
     */
    private byte[] decodeJpegTiles(ByteBuffer wrap, SparseArray<TIFFTag> tags, SensorParams sensor)
            throws ParseException {
        TIFFTag tileWidthTag = tags.get(TIFF.TAG_TileWidth);
        TIFFTag tileLengthTag = tags.get(TIFF.TAG_TileLength);
        TIFFTag tileOffsetsTag = tags.get(TIFF.TAG_TileOffsets);
        TIFFTag tileByteCountsTag = tags.get(TIFF.TAG_TileByteCounts);

        if (tileWidthTag == null || tileLengthTag == null ||
                tileOffsetsTag == null || tileByteCountsTag == null) {
            throw new ParseException("Missing tile tags for JPEG tile decoding");
        }

        int tileWidth = tileWidthTag.getInt();
        int tileLength = tileLengthTag.getInt();
        // Use long arrays for tile offsets/counts as they may be 64-bit in larger files
        long[] tileOffsets = tileOffsetsTag.getLongArray();
        long[] tileByteCounts = tileByteCountsTag.getLongArray();

        Log.d(TAG, "Tile dimensions: " + tileWidth + "x" + tileLength +
                ", tiles count: " + tileOffsets.length);
        
        if (tileOffsets.length > 0) {
            Log.d(TAG, "First tile offset: " + tileOffsets[0] + ", byte count: " + tileByteCounts[0]);
        }

        // Check bits per sample
        TIFFTag bitsTag = tags.get(TIFF.TAG_BitsPerSample);
        int bitsPerSample = 8;
        if (bitsTag != null) {
            int[] bits = bitsTag.getIntArray();
            if (bits.length > 0) {
                bitsPerSample = bits[0];
            }
        }

        Log.d(TAG, "Bits per sample: " + bitsPerSample);

        // Peek at first tile to get actual JPEG dimensions
        int[] jpegDims = null;
        int jpegTileWidth = tileWidth;
        int jpegTileHeight = tileLength;
        try {
            byte[] firstTileData = new byte[(int) tileByteCounts[0]];
            ((ByteBuffer) wrap.duplicate().order(ByteOrder.LITTLE_ENDIAN).position((int) tileOffsets[0]))
                    .get(firstTileData);
            jpegDims = LosslessJpegDecoder.getJpegDimensions(firstTileData);
            if (jpegDims != null) {
                jpegTileWidth = jpegDims[0];
                jpegTileHeight = jpegDims[1];
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not peek at first tile JPEG dimensions: " + e.getMessage());
        }
        
        Log.d(TAG, "JPEG tile dimensions: " + jpegTileWidth + "x" + jpegTileHeight);
        Log.d(TAG, "TIFF tile dimensions: " + tileWidth + "x" + tileLength);
        
        // Check if the raw data is stored with swapped dimensions
        // This happens when JPEG tiles are stored as (height×width) instead of (width×height)
        boolean dimensionsSwapped = (jpegTileWidth == tileLength && jpegTileHeight == tileWidth);
        
        // For assembly, use the actual JPEG dimensions
        int assemblyTileWidth = jpegTileWidth;
        int assemblyTileHeight = jpegTileHeight;
        int assemblyWidth, assemblyHeight;
        
        // Use TIFF dimensions for placement, not JPEG dimensions
        assemblyWidth = sensor.inputWidth;
        assemblyHeight = sensor.inputHeight;
        
        // For tile placement, use TIFF-declared dimensions
        int placementTileWidth = tileWidth;
        int placementTileHeight = tileLength;
        
        int tilesAcross = (assemblyWidth + placementTileWidth - 1) / placementTileWidth;
        int tilesDown = (assemblyHeight + placementTileHeight - 1) / placementTileHeight;
        
        Log.d(TAG, "Dimension swap detected: " + dimensionsSwapped);
        Log.d(TAG, "Placement tile size: " + placementTileWidth + "x" + placementTileHeight);
        Log.d(TAG, "JPEG tile size: " + jpegTileWidth + "x" + jpegTileHeight);

        Log.d(TAG, "Tiles layout: " + tilesAcross + " across x " + tilesDown + " down");
        Log.d(TAG, "Assembly dimensions: " + assemblyWidth + " x " + assemblyHeight);

        // Output buffer: 16-bit RGB (6 bytes per pixel)
        int bytesPerPixel = sensor.samplesPerPixel * 2;  // 16-bit per sample
        byte[] output = new byte[assemblyWidth * assemblyHeight * bytesPerPixel];
        
        // Update sensor params to reflect actual assembly dimensions
        int origWidth = sensor.inputWidth;
        int origHeight = sensor.inputHeight;
        sensor.inputWidth = assemblyWidth;
        sensor.inputHeight = assemblyHeight;
        sensor.inputStride = assemblyWidth * bytesPerPixel;
        
        Log.d(TAG, "Output buffer: " + output.length + " bytes, bytesPerPixel: " + bytesPerPixel);
        Log.d(TAG, "Final sensor dimensions: " + sensor.inputWidth + "x" + sensor.inputHeight);
        Log.d(TAG, "Tiles to decode: " + tileOffsets.length);

        LosslessJpegDecoder losslessDecoder = new LosslessJpegDecoder();
        int successfulTiles = 0;

        for (int tileIdx = 0; tileIdx < tileOffsets.length; tileIdx++) {
            int tileX = (tileIdx % tilesAcross) * placementTileWidth;
            int tileY = (tileIdx / tilesAcross) * placementTileHeight;

            // Read compressed tile data
            int tileByteCount = (int) tileByteCounts[tileIdx];
            int tileOffset = (int) tileOffsets[tileIdx];
            byte[] compressedTile = new byte[tileByteCount];
            ((ByteBuffer) wrap.duplicate().order(ByteOrder.LITTLE_ENDIAN).position(tileOffset))
                    .get(compressedTile);

            boolean decoded = false;

            // Log JPEG info for first tile
            if (tileIdx == 0) {
                String jpegInfo = LosslessJpegDecoder.getJpegInfo(compressedTile);
                Log.d(TAG, "First tile JPEG info: " + jpegInfo);
                Log.d(TAG, "Tile data first bytes: " + bytesToHex(compressedTile, 16));
            }

            // Check if this is lossless JPEG or high-bit-depth JPEG
            if (bitsPerSample > 8 || LosslessJpegDecoder.isLosslessOrHighBitDepth(compressedTile)) {
                // Use lossless JPEG decoder - pass TIFF placement dimensions
                decoded = decodeLosslessTile(losslessDecoder, compressedTile, output,
                        tileX, tileY, placementTileWidth, placementTileHeight, sensor, bytesPerPixel, bitsPerSample,
                        dimensionsSwapped);
                
                if (!decoded && tileIdx == 0) {
                    Log.w(TAG, "Lossless decoder failed for 16-bit tile. Trying standard decoder as fallback.");
                }
            }

            // Fall back to standard BitmapFactory for 8-bit JPEG
            if (!decoded) {
                decoded = decodeStandardJpegTile(compressedTile, output,
                        tileX, tileY, placementTileWidth, placementTileHeight, sensor, bytesPerPixel);
            }

            if (decoded) {
                if (successfulTiles == 0) {
                    Log.d(TAG, "First tile decoded successfully at (" + tileX + "," + tileY + ")");
                }
                successfulTiles++;
            } else {
                // Only log first few failures to avoid log spam
                if (successfulTiles == 0 || tileIdx < 3) {
                    Log.e(TAG, "Failed to decode tile " + tileIdx + " at (" + tileX + "," + tileY + ")");
                }
            }
        }

        if (successfulTiles == 0) {
            throw new ParseException("Failed to decode any JPEG tiles. The DNG format may not be supported.");
        }

        Log.d(TAG, "Successfully decoded " + successfulTiles + "/" + tileOffsets.length + " tiles");
        
        // Verify output is not all zeros (sanity check)
        boolean hasData = false;
        for (int i = 0; i < Math.min(1000, output.length); i++) {
            if (output[i] != 0) {
                hasData = true;
                break;
            }
        }
        if (!hasData) {
            Log.e(TAG, "WARNING: Decoded output appears to be all zeros!");
        } else {
            Log.d(TAG, "Output buffer size: " + output.length + " bytes, has non-zero data");
            // Log some sample pixel values for debugging
            if (output.length >= 12) {
                int r = (output[0] & 0xFF) | ((output[1] & 0xFF) << 8);
                int g = (output[2] & 0xFF) | ((output[3] & 0xFF) << 8);
                int b = (output[4] & 0xFF) | ((output[5] & 0xFF) << 8);
                Log.d(TAG, "First pixel RGB16: R=" + r + " G=" + g + " B=" + b);
                
                // Sample pixel from middle of image
                int midIdx = (sensor.inputHeight / 2 * sensor.inputWidth + sensor.inputWidth / 2) * bytesPerPixel;
                if (midIdx + 6 <= output.length) {
                    r = (output[midIdx] & 0xFF) | ((output[midIdx + 1] & 0xFF) << 8);
                    g = (output[midIdx + 2] & 0xFF) | ((output[midIdx + 3] & 0xFF) << 8);
                    b = (output[midIdx + 4] & 0xFF) | ((output[midIdx + 5] & 0xFF) << 8);
                    Log.d(TAG, "Middle pixel RGB16: R=" + r + " G=" + g + " B=" + b);
                }
            }
        }
        
        return output;
    }

    /**
     * Decode a tile using the lossless JPEG decoder.
     * 
     * This follows dcraw's lossless_dng_load_raw() approach exactly:
     * - For CFA data: jwide = jpeg_width * jpeg_components (all values are spatial data)
     * - For Linear Raw: each pixel has 3 color components
     * - Values are placed sequentially, wrapping at TIFF tile width
     * 
     * Supports both:
     * - Single-channel Bayer CFA data (bytesPerPixel = 2)
     * - 3-channel RGB Linear Raw data (bytesPerPixel = 6)
     */
    private boolean decodeLosslessTile(LosslessJpegDecoder decoder, byte[] compressedTile,
            byte[] output, int tileX, int tileY, int tileWidth, int tileLength,
            SensorParams sensor, int bytesPerPixel, int targetBits, boolean dimensionsMismatch) {

        LosslessJpegDecoder.DecodedTile decoded = decoder.decode(compressedTile);
        if (decoded == null || decoded.pixels == null) {
            Log.w(TAG, "Lossless decoder returned null for tile at (" + tileX + "," + tileY + ")");
            return false;
        }

        int jpegWidth = decoded.width;
        int jpegHeight = decoded.height;

        // Determine if this is single-channel (Bayer CFA) or multi-channel (RGB) data
        boolean isSingleChannel = (bytesPerPixel == 2);

        // Calculate jwide following dcraw's approach:
        // For CFA (filters != 0): jwide = jpeg_width * jpeg_components
        // For Linear Raw with 2 components: may represent 2 pixels per component pair
        // For Linear Raw with 3 components: standard RGB
        int jwide;
        if (isSingleChannel) {
            // CFA mode: JPEG components represent spatial positions, not colors
            // All decoded values should be placed sequentially
            jwide = jpegWidth * decoded.components;
        } else {
            // Linear Raw: Check if 2-component format represents 2 pixels horizontally
            // If JPEG width * 2 == TIFF tile width, then 2 components = 2 pixels
            // COMMENTED OUT: Special 2-component handling
            // boolean twoComponentsTwoPixels = (decoded.components == 2 && jpegWidth * 2 == tileWidth);
            // if (twoComponentsTwoPixels) {
            //     // 2-component format where each component pair = 2 pixels horizontally
            //     // jwide should be jpeg_width * 2 (one value per output pixel)
            //     jwide = jpegWidth * 2;
            // } else {
            //     // Standard: jwide = jpeg_width * components (one value per component)
            //     jwide = jpegWidth * decoded.components;
            // }
            // Standard: jwide = jpeg_width * components (one value per component)
            jwide = jpegWidth * decoded.components;
        }

        // Log dimension info for first tile to help debug
        if (tileX == 0 && tileY == 0) {
            Log.d(TAG, "Decoded JPEG: " + jpegWidth + "x" + jpegHeight + 
                       ", components=" + decoded.components + ", precision=" + decoded.precision);
            Log.d(TAG, "TIFF tile: " + tileWidth + "x" + tileLength);
            Log.d(TAG, "jwide=" + jwide + " (dcraw-style: jpeg_width" + 
                       (isSingleChannel ? " * components" : "") + ")");
            Log.d(TAG, "Output format: " + (isSingleChannel ? "single-channel Bayer" : "RGB") + 
                       ", bytesPerPixel=" + bytesPerPixel);
            Log.d(TAG, "Total decoded values: " + decoded.pixels.length + 
                       ", expected tile values: " + (tileWidth * tileLength));
        }

        // Scale factor to convert from decoded precision to 16-bit
        int shift = 16 - decoded.precision;
        if (shift < 0) shift = 0;

        // Calculate limits for this tile (may be clipped at image edges)
        int maxCol = Math.min(tileWidth, sensor.inputWidth - tileX);
        int maxRow = Math.min(tileLength, sensor.inputHeight - tileY);

        // Following dcraw's lossless_dng_load_raw() exactly:
        // for (row=col=jrow=0; jrow < jh.high; jrow++) {
        //     rp = ljpeg_row (jrow, &jh);
        //     for (jcol=0; jcol < jwide; jcol++) {
        //         adobe_copy_pixel (trow+row, tcol+col, &rp);
        //         if (++col >= tile_width || col >= raw_width)
        //             row += 1 + (col = 0);
        //     }
        // }
        int row = 0;
        int col = 0;
        int valueIndex = 0;  // Index into decoded.pixels array

        for (int jrow = 0; jrow < jpegHeight; jrow++) {
            for (int jcol = 0; jcol < jwide; jcol++) {
                if (isSingleChannel) {
                    // CFA mode: read one value at a time from decoded data
                    if (valueIndex >= decoded.pixels.length) {
                        break;
                    }
                    
                    // Skip if beyond tile bounds (clipped at image edges)
                    if (col < maxCol && row < maxRow) {
                        int outX = tileX + col;
                        int outY = tileY + row;
                        int outIdx = (outY * sensor.inputWidth + outX) * bytesPerPixel;
                        
                        if (outIdx + bytesPerPixel <= output.length) {
                            int v = (decoded.pixels[valueIndex] & 0xFFFF) << shift;
                            v = Math.min(65535, Math.max(0, v));
                            
                            output[outIdx] = (byte) (v & 0xFF);
                            output[outIdx + 1] = (byte) ((v >> 8) & 0xFF);
                        }
                    }
                    
                    valueIndex++;
                    
                } else {
                    // Linear Raw mode: read components per pixel
                    // COMMENTED OUT: Special 2-component handling
                    // boolean twoComponentsTwoPixels = (decoded.components == 2 && jpegWidth * 2 == tileWidth);
                    // 
                    // if (twoComponentsTwoPixels) {
                    //     // Special case: 2 components, each component value = one output pixel
                    //     // Data layout: [C0_0, C1_0, C0_1, C1_1, ...]
                    //     // Each component value maps to a separate output pixel:
                    //     // C0_0 → pixel 0, C1_0 → pixel 1, C0_1 → pixel 2, C1_1 → pixel 3, etc.
                    //     if (valueIndex >= decoded.pixels.length) {
                    //         break;
                    //     }
                    //     
                    //     if (col < maxCol && row < maxRow) {
                    //         int outX = tileX + col;
                    //         int outY = tileY + row;
                    //         int outIdx = (outY * sensor.inputWidth + outX) * bytesPerPixel;
                    //         
                    //         if (outIdx + bytesPerPixel <= output.length) {
                    //             int v = (decoded.pixels[valueIndex] & 0xFFFF) << shift;
                    //             v = Math.min(65535, Math.max(0, v));
                    //             
                    //             // Each component value represents one output pixel
                    //             // The 2 components alternate: C0, C1, C0, C1, ...
                    //             // Try: Look at neighboring pixel to get the other component
                    //             // Since each component = one pixel, we need to look at adjacent pixels
                    //             int componentType = valueIndex % 2;  // 0 or 1
                    //             int r, g, b;
                    //             
                    //             // Try to get the other component from the adjacent pixel
                    //             // If current pixel is at col, the other component is at col+1 or col-1
                    //             int otherIdx;
                    //             if (componentType == 0) {
                    //                 // Component 0: get Component 1 from next pixel
                    //                 otherIdx = valueIndex + 1;
                    //             } else {
                    //                 // Component 1: get Component 0 from previous pixel
                    //                 otherIdx = valueIndex - 1;
                    //             }
                    //             
                    //             if (otherIdx >= 0 && otherIdx < decoded.pixels.length && 
                    //                 (otherIdx % 2) != (valueIndex % 2)) {
                    //                 int otherValue = (decoded.pixels[otherIdx] & 0xFFFF) << shift;
                    //                 otherValue = Math.min(65535, Math.max(0, otherValue));
                    //                 
                    //                 // Try: C0 = R, C1 = G, B = (R+G)/2
                    //                 // This is a common 2-component format
                    //                 if (componentType == 0) {
                    //                     // Component 0: treat as Red
                    //                     r = v;
                    //                     g = otherValue;  // Component 1 = Green
                    //                     b = (r + g) / 2;  // Derive B
                    //                 } else {
                    //                     // Component 1: treat as Green
                    //                     r = otherValue;  // Component 0 = Red
                    //                     g = v;
                    //                     b = (r + g) / 2;  // Derive B
                    //                 }
                    //             } else {
                    //                 // Fallback: use component value as grayscale
                    //                 r = g = b = v;
                    //             }
                    //             
                    //             output[outIdx] = (byte) (r & 0xFF);
                    //             output[outIdx + 1] = (byte) ((r >> 8) & 0xFF);
                    //             output[outIdx + 2] = (byte) (g & 0xFF);
                    //             output[outIdx + 3] = (byte) ((g >> 8) & 0xFF);
                    //             output[outIdx + 4] = (byte) (b & 0xFF);
                    //             output[outIdx + 5] = (byte) ((b >> 8) & 0xFF);
                    //         }
                    //     }
                    //     
                    //     // Advance after each component value (each value = one pixel)
                    //     if (++col >= tileWidth) {
                    //         row++;
                    //         col = 0;
                    //     }
                    //     valueIndex++;
                    //     
                    // } else {
                        // Standard Linear Raw: components per pixel
                        // valueIndex is the component index into decoded.pixels array
                        // For 3-component: pixels are [R0, G0, B0, R1, G1, B1, ...]
                        // For 2-component: pixels are [C0_0, C1_0, C0_1, C1_1, ...]
                        int componentInPixel = valueIndex % decoded.components;
                        
                        // Only process when we have all components for this pixel
                        if (componentInPixel == decoded.components - 1) {
                            int srcIdx = valueIndex - (decoded.components - 1);  // Start of pixel's components
                            
                            if (srcIdx + decoded.components > decoded.pixels.length) {
                                break;
                            }
                            
                            if (col < maxCol && row < maxRow) {
                                int outX = tileX + col;
                                int outY = tileY + row;
                                int outIdx = (outY * sensor.inputWidth + outX) * bytesPerPixel;
                                
                                if (outIdx + bytesPerPixel <= output.length) {
                                    int r, g, b;
                                    if (decoded.components >= 3) {
                                        // Full RGB: 3 components
                                        r = (decoded.pixels[srcIdx] & 0xFFFF) << shift;
                                        g = (decoded.pixels[srcIdx + 1] & 0xFFFF) << shift;
                                        b = (decoded.pixels[srcIdx + 2] & 0xFFFF) << shift;
                                    } else if (decoded.components == 2) {
                                        // 2-component format: treat as R and G, derive B
                                        r = (decoded.pixels[srcIdx] & 0xFFFF) << shift;
                                        g = (decoded.pixels[srcIdx + 1] & 0xFFFF) << shift;
                                        b = ((r + g) / 2);
                                    } else {
                                        // Single component: use as grayscale
                                        int v = (decoded.pixels[srcIdx] & 0xFFFF) << shift;
                                        r = g = b = v;
                                    }

                                    r = Math.min(65535, Math.max(0, r));
                                    g = Math.min(65535, Math.max(0, g));
                                    b = Math.min(65535, Math.max(0, b));

                                    output[outIdx] = (byte) (r & 0xFF);
                                    output[outIdx + 1] = (byte) ((r >> 8) & 0xFF);
                                    output[outIdx + 2] = (byte) (g & 0xFF);
                                    output[outIdx + 3] = (byte) ((g >> 8) & 0xFF);
                                    output[outIdx + 4] = (byte) (b & 0xFF);
                                    output[outIdx + 5] = (byte) ((b >> 8) & 0xFF);
                                }
                            }
                            // Advance output position after processing all components of this pixel
                            if (++col >= tileWidth) {
                                row++;
                                col = 0;
                            }
                        }
                        valueIndex++;
                    // }
                }
                
                // For CFA mode, advance output position after each value (dcraw-style)
                if (isSingleChannel) {
                    if (++col >= tileWidth) {
                        row++;
                        col = 0;
                    }
                }
            }
        }

        if (tileX == 0 && tileY == 0) {
            Log.d(TAG, "Processed " + valueIndex + " values, final position: row=" + row + ", col=" + col);
        }

        return true;
    }

    /**
     * Decode a tile using Android's BitmapFactory (for standard 8-bit JPEG).
     * 
     * Supports both:
     * - Single-channel Bayer CFA data (bytesPerPixel = 2) - uses grayscale from RGB
     * - 3-channel RGB Linear Raw data (bytesPerPixel = 6)
     */
    private boolean decodeStandardJpegTile(byte[] compressedTile, byte[] output,
            int tileX, int tileY, int tileWidth, int tileLength,
            SensorParams sensor, int bytesPerPixel) {

        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap tileBitmap = BitmapFactory.decodeByteArray(compressedTile, 0, compressedTile.length, options);

            if (tileBitmap == null) {
                return false;
            }

            int actualTileWidth = Math.min(tileWidth, sensor.inputWidth - tileX);
            int actualTileHeight = Math.min(tileLength, sensor.inputHeight - tileY);
            actualTileWidth = Math.min(actualTileWidth, tileBitmap.getWidth());
            actualTileHeight = Math.min(actualTileHeight, tileBitmap.getHeight());

            int[] pixels = new int[actualTileWidth * actualTileHeight];
            tileBitmap.getPixels(pixels, 0, actualTileWidth, 0, 0, actualTileWidth, actualTileHeight);

            boolean isSingleChannel = (bytesPerPixel == 2);

            for (int py = 0; py < actualTileHeight; py++) {
                for (int px = 0; px < actualTileWidth; px++) {
                    int pixel = pixels[py * actualTileWidth + px];
                    int outX = tileX + px;
                    int outY = tileY + py;
                    int outIdx = (outY * sensor.inputWidth + outX) * bytesPerPixel;

                    if (isSingleChannel) {
                        // Single-channel Bayer CFA - use luminance approximation
                        // Note: Standard JPEG tiles for Bayer data is unusual, but handle it
                        int r8 = (pixel >> 16) & 0xFF;
                        int g8 = (pixel >> 8) & 0xFF;
                        int b8 = pixel & 0xFF;
                        // Use green channel as it's typically the most representative
                        // Or use simple average for grayscale
                        int v = g8 * 257;  // Scale 8-bit to 16-bit
                        
                        output[outIdx] = (byte) (v & 0xFF);
                        output[outIdx + 1] = (byte) ((v >> 8) & 0xFF);
                    } else {
                        // 3-channel RGB - extract and scale from 8-bit to 16-bit
                        int r = ((pixel >> 16) & 0xFF) * 257;
                        int g = ((pixel >> 8) & 0xFF) * 257;
                        int b = (pixel & 0xFF) * 257;

                        // Write as little-endian 16-bit values
                        output[outIdx] = (byte) (r & 0xFF);
                        output[outIdx + 1] = (byte) ((r >> 8) & 0xFF);
                        output[outIdx + 2] = (byte) (g & 0xFF);
                        output[outIdx + 3] = (byte) ((g >> 8) & 0xFF);
                        output[outIdx + 4] = (byte) (b & 0xFF);
                        output[outIdx + 5] = (byte) ((b >> 8) & 0xFF);
                    }
                }
            }

            tileBitmap.recycle();
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error decoding standard JPEG tile: " + e.getMessage());
            return false;
        }
    }

    /**
     * Decode uncompressed tile-based DNG data.
     * Handles both Bayer CFA (samplesPerPixel=1) and Linear Raw RGB (samplesPerPixel=3).
     */
    private byte[] decodeUncompressedTiles(ByteBuffer wrap, SparseArray<TIFFTag> tags,
            SensorParams sensor, int bitsPerSample) throws ParseException {
        TIFFTag tileWidthTag = tags.get(TIFF.TAG_TileWidth);
        TIFFTag tileLengthTag = tags.get(TIFF.TAG_TileLength);
        TIFFTag tileOffsetsTag = tags.get(TIFF.TAG_TileOffsets);
        TIFFTag tileByteCountsTag = tags.get(TIFF.TAG_TileByteCounts);

        if (tileWidthTag == null || tileLengthTag == null ||
                tileOffsetsTag == null || tileByteCountsTag == null) {
            throw new ParseException("Missing tile tags for uncompressed tile decoding");
        }

        int tileWidth = tileWidthTag.getInt();
        int tileLength = tileLengthTag.getInt();
        long[] tileOffsets = tileOffsetsTag.getLongArray();
        long[] tileByteCounts = tileByteCountsTag.getLongArray();

        int tilesAcross = (sensor.inputWidth + tileWidth - 1) / tileWidth;
        int tilesDown = (sensor.inputHeight + tileLength - 1) / tileLength;

        // Output: always 16-bit per sample
        int bytesPerPixel = sensor.samplesPerPixel * 2;
        byte[] output = new byte[sensor.inputWidth * sensor.inputHeight * bytesPerPixel];
        sensor.inputStride = sensor.inputWidth * bytesPerPixel;

        Log.d(TAG, "Uncompressed tiles: " + tileWidth + "x" + tileLength +
                ", " + tilesAcross + " across x " + tilesDown + " down" +
                ", bitsPerSample=" + bitsPerSample);

        for (int tileIdx = 0; tileIdx < tileOffsets.length; tileIdx++) {
            int tileX = (tileIdx % tilesAcross) * tileWidth;
            int tileY = (tileIdx / tilesAcross) * tileLength;

            int actualTileWidth = Math.min(tileWidth, sensor.inputWidth - tileX);
            int actualTileHeight = Math.min(tileLength, sensor.inputHeight - tileY);

            byte[] tileData = new byte[(int) tileByteCounts[tileIdx]];
            ((ByteBuffer) wrap.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    .position((int) tileOffsets[tileIdx])).get(tileData);

            if (bitsPerSample == 16) {
                // Direct copy of 16-bit data
                copyTile16Bit(tileData, output, tileX, tileY,
                        tileWidth, actualTileWidth, actualTileHeight,
                        sensor.inputWidth, sensor.samplesPerPixel);
            } else if (bitsPerSample >= 10 && bitsPerSample <= 14) {
                // Unpack packed bits to 16-bit
                unpackTile(tileData, output, tileX, tileY,
                        tileWidth, actualTileWidth, actualTileHeight,
                        sensor.inputWidth, sensor.samplesPerPixel, bitsPerSample);
            } else {
                throw new ParseException("Unsupported bits per sample in tiles: " + bitsPerSample);
            }
        }

        return output;
    }

    /**
     * Copy 16-bit tile data directly to output buffer.
     */
    private void copyTile16Bit(byte[] tileData, byte[] output, int tileX, int tileY,
            int tileStride, int actualWidth, int actualHeight,
            int outputWidth, int samplesPerPixel) {
        int bytesPerPixel = samplesPerPixel * 2;
        int tileBytesPerRow = tileStride * bytesPerPixel;
        int outputBytesPerRow = outputWidth * bytesPerPixel;

        for (int row = 0; row < actualHeight; row++) {
            int srcOffset = row * tileBytesPerRow;
            int dstOffset = (tileY + row) * outputBytesPerRow + tileX * bytesPerPixel;
            int copyLength = actualWidth * bytesPerPixel;

            if (srcOffset + copyLength <= tileData.length && dstOffset + copyLength <= output.length) {
                System.arraycopy(tileData, srcOffset, output, dstOffset, copyLength);
            }
        }
    }

    /**
     * Unpack packed bit data (10/12/14-bit) from a tile to 16-bit output.
     */
    private void unpackTile(byte[] tileData, byte[] output, int tileX, int tileY,
            int tileStride, int actualWidth, int actualHeight,
            int outputWidth, int samplesPerPixel, int bitsPerSample) {
        int bytesPerPixel = samplesPerPixel * 2;
        int outputBytesPerRow = outputWidth * bytesPerPixel;
        int samplesPerRow = tileStride * samplesPerPixel;
        
        // Calculate shift to convert from source precision to 16-bit
        int shift = 16 - bitsPerSample;

        int bitBuffer = 0;
        int bitsInBuffer = 0;
        int bytePos = 0;

        for (int row = 0; row < actualHeight; row++) {
            // Reset bit buffer at start of each row (typical for DNG)
            bitBuffer = 0;
            bitsInBuffer = 0;
            bytePos = row * ((samplesPerRow * bitsPerSample + 7) / 8);

            for (int col = 0; col < actualWidth; col++) {
                int dstOffset = (tileY + row) * outputBytesPerRow + (tileX + col) * bytesPerPixel;

                for (int s = 0; s < samplesPerPixel; s++) {
                    // Extract one sample value
                    while (bitsInBuffer < bitsPerSample && bytePos < tileData.length) {
                        bitBuffer = (bitBuffer << 8) | (tileData[bytePos++] & 0xFF);
                        bitsInBuffer += 8;
                    }

                    int value = (bitBuffer >> (bitsInBuffer - bitsPerSample)) & ((1 << bitsPerSample) - 1);
                    bitsInBuffer -= bitsPerSample;

                    // Scale to 16-bit
                    value <<= shift;

                    // Write as little-endian 16-bit
                    int outIdx = dstOffset + s * 2;
                    if (outIdx + 1 < output.length) {
                        output[outIdx] = (byte) (value & 0xFF);
                        output[outIdx + 1] = (byte) ((value >> 8) & 0xFF);
                    }
                }
            }
        }
    }

    /**
     * Unpack packed bit data (10/12/14-bit) from strips to 16-bit output.
     * Similar to dcraw's packed_dng_load_raw().
     */
    private byte[] unpackBits(ByteBuffer wrap, int[] stripOffsets, int[] stripByteCounts,
            int width, int height, int samplesPerPixel, int bitsPerSample) {
        int bytesPerPixel = samplesPerPixel * 2;
        byte[] output = new byte[width * height * bytesPerPixel];
        int shift = 16 - bitsPerSample;
        
        int outRow = 0;
        for (int stripIdx = 0; stripIdx < stripOffsets.length && outRow < height; stripIdx++) {
            byte[] stripData = new byte[stripByteCounts[stripIdx]];
            ((ByteBuffer) wrap.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    .position(stripOffsets[stripIdx])).get(stripData);

            int bitBuffer = 0;
            int bitsInBuffer = 0;
            int bytePos = 0;

            // Calculate rows in this strip
            int samplesPerRow = width * samplesPerPixel;
            int bitsPerRow = samplesPerRow * bitsPerSample;
            int bytesPerRow = (bitsPerRow + 7) / 8;
            int rowsInStrip = stripByteCounts[stripIdx] / bytesPerRow;
            if (rowsInStrip == 0) rowsInStrip = 1;

            for (int row = 0; row < rowsInStrip && outRow < height; row++, outRow++) {
                // Reset bit buffer at start of each row
                bitBuffer = 0;
                bitsInBuffer = 0;
                bytePos = row * bytesPerRow;

                for (int col = 0; col < width; col++) {
                    int dstOffset = outRow * width * bytesPerPixel + col * bytesPerPixel;

                    for (int s = 0; s < samplesPerPixel; s++) {
                        // Extract one sample value
                        while (bitsInBuffer < bitsPerSample && bytePos < stripData.length) {
                            bitBuffer = (bitBuffer << 8) | (stripData[bytePos++] & 0xFF);
                            bitsInBuffer += 8;
                        }

                        int value = (bitBuffer >> (bitsInBuffer - bitsPerSample)) & ((1 << bitsPerSample) - 1);
                        bitsInBuffer -= bitsPerSample;

                        // Scale to 16-bit
                        value <<= shift;

                        // Write as little-endian 16-bit
                        int outIdx = dstOffset + s * 2;
                        if (outIdx + 1 < output.length) {
                            output[outIdx] = (byte) (value & 0xFF);
                            output[outIdx + 1] = (byte) ((value >> 8) & 0xFF);
                        }
                    }
                }
            }
        }

        return output;
    }

    /**
     * Copies EXIF data (including orientation) to an output JPEG file.
     * This method is used for both standard and HDR JPEGs to ensure orientation and metadata are preserved.
     * 
     * @param filePath Path to the output JPEG file
     * @param reader ByteReader.ReaderWithExif containing DNG EXIF data
     * @param tags TIFF tags from the DNG
     * @param pref Preferences instance
     */
    private void copyExifDataToFile(String filePath, ByteReader.ReaderWithExif reader, 
                                     SparseArray<TIFFTag> tags, Preferences pref, int[] steps) {
        NotifHandler.progress(mContext, steps[0] + ADD_STEPS, steps[0] + STEP_META);
        try {
            ExifInterface newExif = new ExifInterface(filePath);
            
            // Match behavior of convert_uraw_v5.1.sh: if original JPEG exists, copy all EXIF from it first
            // This ensures we get all metadata (GPS, camera settings, etc.) that might only be in the JPEG
            String jpegPath = Path.getOriginalJpegPath(pref.jpegLocation.get(), mFile);
            ExifInterface jpegExif = null;
            String jpegOrientation = null;
            if (jpegPath != null) {
                File jpegFile = new File(jpegPath);
                if (jpegFile.exists()) {
                    try (FileInputStream jpegStream = new FileInputStream(jpegFile)) {
                        jpegExif = new ExifInterface(jpegStream);
                        Log.d(TAG, "Copying all EXIF from original JPEG: " + jpegPath);
                        copyAllExifAttributes(jpegExif, newExif);
                        // Preserve orientation from JPEG - it's already correctly oriented by the camera
                        jpegOrientation = jpegExif.getAttribute(ExifInterface.TAG_ORIENTATION);
                        if (jpegOrientation != null && !jpegOrientation.isEmpty()) {
                            Log.d(TAG, "Preserving orientation from JPEG: " + jpegOrientation);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to read EXIF from original JPEG: " + jpegPath, e);
                    }
                }
            }
            
            // Copy EXIF from DNG only if original JPEG was not found
            // If JPEG exists, it's the preferred source (has GPS, orientation, etc.)
            // If JPEG doesn't exist, fall back to DNG EXIF
            if (jpegExif == null && reader.exif != null) {
                Log.d(TAG, "Original JPEG not found, copying EXIF from DNG");
                copyAllExifAttributes(reader.exif, newExif);
            }
            
            // Copy TIFF tags to EXIF attributes only if original JPEG was not found
            // TIFF tags from DNG are authoritative for camera settings, but JPEG takes precedence if available
            if (jpegExif == null) {
                Log.d(TAG, "Original JPEG not found, copying TIFF tags to EXIF");
                copyTiffTagsToExif(tags, newExif, null);
            }

            newExif.saveAttributes();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save EXIF attributes to " + filePath, e);
        }
    }

    /**
     * Copy all EXIF attributes from source to destination.
     * Uses reflection to get all TAG_ constants from ExifInterface and copies them.
     * This ensures all metadata (including orientation and GPS location) is preserved.
     * 
     * Matches behavior of convert_uraw_v5.1.sh which uses exiftool -TagsFromFile
     * to copy all tags including orientation.
     * 
     * GPS tags copied include:
     * - TAG_GPS_LATITUDE, TAG_GPS_LATITUDE_REF
     * - TAG_GPS_LONGITUDE, TAG_GPS_LONGITUDE_REF
     * - TAG_GPS_ALTITUDE, TAG_GPS_ALTITUDE_REF
     * - TAG_GPS_TIMESTAMP, TAG_GPS_DATESTAMP
     * - TAG_GPS_PROCESSING_METHOD
     * - And all other GPS-related tags
     */
    @SuppressWarnings("deprecation")
    private static void copyAllExifAttributes(ExifInterface oldExif, ExifInterface newExif) {
        copyAllExifAttributes(oldExif, newExif, null);
    }

    @SuppressWarnings("deprecation")
    private static void copyAllExifAttributes(ExifInterface oldExif, ExifInterface newExif, String preserveOrientation) {
        if (oldExif == null || newExif == null) {
            return;
        }

        try {
            // Get all TAG_ constants from ExifInterface using reflection
            Field[] fields = ExifInterface.class.getDeclaredFields();
            int copiedCount = 0;
            int gpsCount = 0;
            
            for (Field field : fields) {
                String fieldName = field.getName();
                // Only process TAG_ constants (they're all public static final String)
                if (fieldName.startsWith("TAG_") && field.getType() == String.class) {
                    try {
                        String tagName = (String) field.get(null);
                        
                        // Preserve orientation from JPEG if specified (JPEG is already correctly oriented)
                        if (ExifInterface.TAG_ORIENTATION.equals(tagName) && preserveOrientation != null && !preserveOrientation.isEmpty()) {
                            continue; // Skip copying orientation, preserve the JPEG orientation
                        }
                        
                        String value = oldExif.getAttribute(tagName);
                        if (value != null && !value.isEmpty()) {
                            newExif.setAttribute(tagName, value);
                            copiedCount++;
                            // Track GPS tags for logging
                            if (fieldName.startsWith("TAG_GPS_")) {
                                gpsCount++;
                            }
                        }
                    } catch (IllegalAccessException | IllegalArgumentException e) {
                        // Skip fields we can't access (shouldn't happen for public static fields)
                        Log.w(TAG, "Could not access EXIF tag field: " + fieldName, e);
                    }
                }
            }
            
            Log.d(TAG, "Copied " + copiedCount + " EXIF attributes from source" + 
                    (gpsCount > 0 ? " (including " + gpsCount + " GPS tags)" : "") +
                    (preserveOrientation != null ? " (preserved orientation: " + preserveOrientation + ")" : ""));
        } catch (Exception e) {
            Log.e(TAG, "Error copying EXIF attributes using reflection, falling back to manual copy", e);
            // Fallback to manual copy of essential tags
            copyEssentialAttributes(oldExif, newExif);
        }
    }

    /**
     * Fallback method to copy essential EXIF attributes manually.
     * Used if reflection fails.
     */
    @SuppressWarnings("deprecation")
    private static void copyEssentialAttributes(ExifInterface oldExif, ExifInterface newExif) {
        String[] essentialTags = {
                ExifInterface.TAG_ORIENTATION,  // Copy orientation from source
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_SOFTWARE,
                ExifInterface.TAG_X_RESOLUTION,
                ExifInterface.TAG_Y_RESOLUTION,
                ExifInterface.TAG_RESOLUTION_UNIT,
                ExifInterface.TAG_ARTIST,
                ExifInterface.TAG_COPYRIGHT,
                ExifInterface.TAG_IMAGE_DESCRIPTION,
                ExifInterface.TAG_USER_COMMENT,
                // GPS tags
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
                // Camera settings (may be overridden by TIFF tags)
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_APERTURE_VALUE,
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_ISO_SPEED_RATINGS,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                ExifInterface.TAG_EXPOSURE_MODE,
                ExifInterface.TAG_EXPOSURE_PROGRAM,
                ExifInterface.TAG_WHITE_BALANCE,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_METERING_MODE,
                ExifInterface.TAG_SCENE_TYPE,
        };

        for (String tag : essentialTags) {
            try {
                String value = oldExif.getAttribute(tag);
                if (value != null && !value.isEmpty()) {
                    newExif.setAttribute(tag, value);
                }
            } catch (Exception e) {
                // Skip tags that cause errors
                Log.w(TAG, "Error copying EXIF tag " + tag, e);
            }
        }
    }

    /**
     * Copy TIFF tags to EXIF attributes where applicable.
     * TIFF tags from the DNG take precedence as they're the authoritative source.
     * This matches the behavior of exiftool -TagsFromFile which copies all tags.
     * 
     * @param preserveOrientation If not null, preserve this orientation value (from JPEG) instead of copying from TIFF
     */
    private static void copyTiffTagsToExif(SparseArray<TIFFTag> tags, ExifInterface newExif, String preserveOrientation) {
        if (tags == null || newExif == null) {
            return;
        }

        try {
            // Orientation (TIFF tag 274) - preserve from JPEG if specified, otherwise use TIFF if not set
            // The JPEG orientation is already correctly oriented by the camera, so we preserve it
            TIFFTag orientationTag = tags.get(TIFF.TAG_Orientation);
            if (orientationTag != null) {
                try {
                    String currentOrientation = newExif.getAttribute(ExifInterface.TAG_ORIENTATION);
                    
                    // If we have JPEG orientation, preserve it (don't use TIFF)
                    if (preserveOrientation != null && !preserveOrientation.isEmpty()) {
                        newExif.setAttribute(ExifInterface.TAG_ORIENTATION, preserveOrientation);
                        Log.d(TAG, "Preserved orientation from JPEG: " + preserveOrientation + " (ignoring TIFF)");
                    } else if (currentOrientation == null || currentOrientation.isEmpty()) {
                        // No JPEG orientation and not set from EXIF, use TIFF
                        int orientation = orientationTag.getInt();
                        newExif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientation));
                        Log.d(TAG, "Set orientation from TIFF: " + orientation);
                    } else {
                        Log.d(TAG, "Orientation already set from EXIF: " + currentOrientation + ", skipping TIFF");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not set orientation from TIFF tag", e);
                }
            } else if (preserveOrientation != null && !preserveOrientation.isEmpty()) {
                // No TIFF orientation tag, but we have JPEG orientation - set it
                try {
                    newExif.setAttribute(ExifInterface.TAG_ORIENTATION, preserveOrientation);
                    Log.d(TAG, "Set orientation from JPEG: " + preserveOrientation);
                } catch (Exception e) {
                    Log.w(TAG, "Could not set orientation from JPEG", e);
                }
            }

            // Make (TIFF tag 271)
            TIFFTag makeTag = tags.get(TIFF.TAG_Make);
            if (makeTag != null) {
                try {
                    String make = makeTag.getString();
                    if (make != null && !make.isEmpty()) {
                        newExif.setAttribute(ExifInterface.TAG_MAKE, make);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not set make from TIFF tag", e);
                }
            }

            // Model (TIFF tag 272)
            TIFFTag modelTag = tags.get(TIFF.TAG_Model);
            if (modelTag != null) {
                try {
                    String model = modelTag.getString();
                    if (model != null && !model.isEmpty()) {
                        newExif.setAttribute(ExifInterface.TAG_MODEL, model);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not set model from TIFF tag", e);
                }
            }

            // Software (TIFF tag 305)
            TIFFTag softwareTag = tags.get(TIFF.TAG_Software);
            if (softwareTag != null) {
                try {
                    String software = softwareTag.getString();
                    if (software != null && !software.isEmpty()) {
                        newExif.setAttribute(ExifInterface.TAG_SOFTWARE, software);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not set software from TIFF tag", e);
                }
            }

            // Copyright (TIFF tag 33432)
            TIFFTag copyrightTag = tags.get(TIFF.TAG_Copyright);
            if (copyrightTag != null) {
                try {
                    String copyright = copyrightTag.getString();
                    if (copyright != null && !copyright.isEmpty()) {
                        newExif.setAttribute(ExifInterface.TAG_COPYRIGHT, copyright);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not set copyright from TIFF tag", e);
                }
            }

            // ImageDescription (TIFF tag 270)
            TIFFTag imageDescTag = tags.get(TIFF.TAG_ImageDescription);
            if (imageDescTag != null) {
                try {
                    String imageDesc = imageDescTag.getString();
                    if (imageDesc != null && !imageDesc.isEmpty()) {
                        newExif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, imageDesc);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not set image description from TIFF tag", e);
                }
            }

            // DateTimeOriginal (TIFF tag 36867)
            TIFFTag dateTimeTag = tags.get(TIFF.TAG_DateTimeOriginal);
            if (dateTimeTag != null) {
                try {
                    String dateTime = dateTimeTag.getString();
                    if (dateTime != null && !dateTime.isEmpty()) {
                        newExif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not set datetime from TIFF tag", e);
                }
            }

            // XResolution (TIFF tag 282)
            TIFFTag xResTag = tags.get(TIFF.TAG_XResolution);
            if (xResTag != null) {
                try {
                    Rational xRes = xResTag.getRational();
                    if (xRes != null) {
                        newExif.setAttribute(ExifInterface.TAG_X_RESOLUTION, xRes.toString());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not set X resolution from TIFF tag", e);
                }
            }

            // YResolution (TIFF tag 283)
            TIFFTag yResTag = tags.get(TIFF.TAG_YResolution);
            if (yResTag != null) {
                try {
                    Rational yRes = yResTag.getRational();
                    if (yRes != null) {
                        newExif.setAttribute(ExifInterface.TAG_Y_RESOLUTION, yRes.toString());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not set Y resolution from TIFF tag", e);
                }
            }

            // ResolutionUnit (TIFF tag 296)
            TIFFTag resUnitTag = tags.get(TIFF.TAG_ResolutionUnit);
            if (resUnitTag != null) {
                try {
                    int resUnit = resUnitTag.getInt();
                    newExif.setAttribute(ExifInterface.TAG_RESOLUTION_UNIT, String.valueOf(resUnit));
                } catch (Exception e) {
                    Log.w(TAG, "Could not set resolution unit from TIFF tag", e);
                }
            }

            // Exposure parameters - these are critical and should come from TIFF
            if (tags.get(TIFF.TAG_FocalLength) != null) {
                try {
                    Rational focalLength = tags.get(TIFF.TAG_FocalLength).getRational();
                    if (focalLength != null) {
                        newExif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, focalLength.toString());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not set focal length from TIFF tag", e);
                }
            }

            if (tags.get(TIFF.TAG_FNumber) != null) {
                try {
                    Rational fNumber = tags.get(TIFF.TAG_FNumber).getRational();
                    if (fNumber != null) {
                        newExif.setAttribute(ExifInterface.TAG_APERTURE_VALUE, fNumber.toString());
                        newExif.setAttribute(ExifInterface.TAG_F_NUMBER, fNumber.toString());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not set f-number from TIFF tag", e);
                }
            }

            if (tags.get(TIFF.TAG_ExposureTime) != null) {
                try {
                    Rational exposureTime = tags.get(TIFF.TAG_ExposureTime).getRational();
                    if (exposureTime != null) {
                        newExif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exposureTime.toString());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not set exposure time from TIFF tag", e);
                }
            }

            if (tags.get(TIFF.TAG_ISOSpeedRatings) != null) {
                try {
                    int iso = tags.get(TIFF.TAG_ISOSpeedRatings).getInt();
                    newExif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, String.valueOf(iso));
                    newExif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, String.valueOf(iso));
                } catch (Exception e) {
                    Log.w(TAG, "Could not set ISO from TIFF tag", e);
                }
            }

            Log.d(TAG, "Copied TIFF tags to EXIF attributes");
        } catch (Exception e) {
            Log.e(TAG, "Error copying TIFF tags to EXIF", e);
        }
    }

    /**
     * Check if GPS data exists in the EXIF.
     * Returns true if at least latitude and longitude are present.
     */
    @SuppressWarnings("deprecation")
    private static boolean hasGpsData(ExifInterface exif) {
        if (exif == null) {
            return false;
        }
        String lat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE);
        String latRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF);
        String lon = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE);
        String lonRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF);
        
        // GPS data exists if we have both latitude and longitude with their refs
        return (lat != null && !lat.isEmpty() && latRef != null && !latRef.isEmpty() &&
                lon != null && !lon.isEmpty() && lonRef != null && !lonRef.isEmpty());
    }

    /**
     * Copy GPS attributes from source EXIF to destination EXIF.
     * This is used when GPS data is missing from the DNG and needs to be copied from the original JPEG.
     * Matches the behavior of convert_uraw_v5.1.sh which copies EXIF from stock JPEG if available.
     */
    @SuppressWarnings("deprecation")
    private static void copyGpsAttributes(ExifInterface sourceExif, ExifInterface destExif) {
        if (sourceExif == null || destExif == null) {
            return;
        }

        String[] gpsTags = {
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
        };

        int copiedCount = 0;
        for (String tag : gpsTags) {
            try {
                String value = sourceExif.getAttribute(tag);
                if (value != null && !value.isEmpty()) {
                    destExif.setAttribute(tag, value);
                    copiedCount++;
                }
            } catch (Exception e) {
                Log.w(TAG, "Error copying GPS tag " + tag, e);
            }
        }

        if (copiedCount > 0) {
            Log.d(TAG, "Copied " + copiedCount + " GPS attributes from original JPEG");
        }
    }

    private class ParseException extends RuntimeException {
        private ParseException(String s) {
            super(s);
        }
    }

    private static String bytesToHex(byte[] bytes, int maxLen) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, maxLen); i++) {
            sb.append(String.format("%02X ", bytes[i] & 0xFF));
        }
        if (bytes.length > maxLen) {
            sb.append("...");
        }
        return sb.toString();
    }
}
