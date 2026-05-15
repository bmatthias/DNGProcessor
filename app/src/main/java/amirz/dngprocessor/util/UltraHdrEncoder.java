package amirz.dngprocessor.util;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.media.codecs.ultrahdr.UltraHDREncoder;
import com.google.media.codecs.ultrahdr.UltraHDRCommon;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Utility class for encoding Ultra HDR JPEG files using libultrahdr.
 * 
 * This properly embeds the gain map in the Ultra HDR format, making it
 * compatible with Android's HdrImageReader and other Ultra HDR decoders.
 * 
 * The HDR bitmap is expected to be log-encoded using the formula:
 *   encoded = log2(1 + linearValue) / log2(17)
 * 
 * This encoder decodes the log values back to linear half-float format for
 * accurate HDR encoding without color shifts.
 */
public class UltraHdrEncoder {
    private static final String TAG = "UltraHdrEncoder";
    
    // Log encoding constants (must match shader)
    // log2(17) ≈ 4.087 - maps [0, 16] to [0, 1]
    private static final float LOG2_17 = 4.087462841f;
    
    // Pre-computed LUT for log-to-linear-half-float conversion (256 entries for 8-bit input)
    // Each entry is the half-float representation of the linear value
    private static final short[] LOG_TO_HALF_LUT = new short[256];
    
    // Pre-computed LUT for alpha to half-float conversion (linear 0-255 to 0.0-1.0)
    private static final short[] ALPHA_TO_HALF_LUT = new short[256];
    
    static {
        // Build log-to-half-float LUT
        for (int i = 0; i < 256; i++) {
            float encoded = i / 255.0f;  // [0, 1]
            // Decode: 2^(encoded * log2(17)) - 1 = 17^encoded - 1
            float linear = (float) Math.pow(2.0, encoded * LOG2_17) - 1.0f;
            LOG_TO_HALF_LUT[i] = floatToHalf(linear);
        }
        
        // Build alpha to half-float LUT (simple linear mapping)
        for (int i = 0; i < 256; i++) {
            ALPHA_TO_HALF_LUT[i] = floatToHalf(i / 255.0f);
        }
    }
    
    /**
     * Encodes an Ultra HDR JPEG from SDR and HDR bitmaps.
     * 
     * The encoder automatically generates the gain map from the SDR and HDR images.
     * 
     * @param sdrBitmap The SDR (base) bitmap in sRGB color space
     * @param hdrBitmap The HDR bitmap in linear color space (values can exceed 1.0)
     * @param outputFile Output file to write the Ultra HDR JPEG to
     * @param quality JPEG quality for base image (0-100)
     * @param gainMapQuality JPEG quality for gain map (0-100, typically lower than base quality)
     * @param baselineExposure The DNG baseline exposure value (EV) used to configure gain map thresholds
     * @return true if successful, false otherwise
     */
    public static boolean encodeUltraHdr(Bitmap sdrBitmap, Bitmap hdrBitmap, 
                                         File outputFile, int quality, int gainMapQuality,
                                         float baselineExposure) {
        if (sdrBitmap == null || hdrBitmap == null || outputFile == null) {
            Log.e(TAG, "Input parameters cannot be null");
            return false;
        }
        
        if (sdrBitmap.getWidth() != hdrBitmap.getWidth() || 
            sdrBitmap.getHeight() != hdrBitmap.getHeight()) {
            Log.e(TAG, "SDR and HDR bitmaps must have the same dimensions");
            return false;
        }
        
        if (sdrBitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            Log.e(TAG, "SDR bitmap must be ARGB_8888 format");
            return false;
        }
        
        if (hdrBitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            Log.e(TAG, "HDR bitmap must be ARGB_8888 format");
            return false;
        }
        
        // Clamp quality values
        quality = Math.max(0, Math.min(100, quality));
        gainMapQuality = Math.max(0, Math.min(100, gainMapQuality));
        
        UltraHDREncoder encoder = null;
        try {
            encoder = new UltraHDREncoder();
            
            int width = sdrBitmap.getWidth();
            int height = sdrBitmap.getHeight();
            
            // MEMORY OPTIMIZATION: Compress SDR to JPEG first to reduce memory usage.
            // For a 50MP image, raw pixels = ~200MB, but JPEG = ~10-20MB.
            // This allows us to fit both SDR JPEG + HDR half-float in the 512MB heap limit.
            Log.d(TAG, "Compressing SDR bitmap to JPEG...");
            byte[] sdrJpegData;
            ByteArrayOutputStream sdrJpegStream = new ByteArrayOutputStream();
            try {
                if (!sdrBitmap.compress(Bitmap.CompressFormat.JPEG, quality, sdrJpegStream)) {
                    Log.e(TAG, "Failed to compress SDR bitmap to JPEG");
                    return false;
                }
                sdrJpegData = sdrJpegStream.toByteArray();
                Log.d(TAG, "SDR JPEG size: " + sdrJpegData.length + " bytes");
            } finally {
                try {
                    sdrJpegStream.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
            
            // Check memory requirements before attempting half-float conversion
            // For large images (e.g., 50MP), the half-float array requires ~400MB
            long pixelCount = (long) width * height;
            long requiredBytes = pixelCount * 8L; // 8 bytes per long
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long availableMemory = maxMemory - (totalMemory - freeMemory);
            
            // Check if we have enough memory (with 20% safety margin)
            if (requiredBytes > availableMemory * 0.8) {
                Log.w(TAG, String.format(
                    "Skipping Ultra HDR generation: insufficient memory. " +
                    "Image: %dx%d (%.1fMP), Required: %.1fMB, Available: %.1fMB, Max: %.1fMB",
                    width, height, pixelCount / 1_000_000.0,
                    requiredBytes / (1024.0 * 1024.0),
                    availableMemory / (1024.0 * 1024.0),
                    maxMemory / (1024.0 * 1024.0)));
                return false;
            }
            
            Log.d(TAG, String.format("Memory check: required=%.1fMB, available=%.1fMB, max=%.1fMB",
                    requiredBytes / (1024.0 * 1024.0),
                    availableMemory / (1024.0 * 1024.0),
                    maxMemory / (1024.0 * 1024.0)));
            
            // Convert log-encoded HDR values directly to linear half-float format
            // Read directly from bitmap in scanlines to minimize memory usage.
            // The shader encoded as: log2(1 + x) / log2(17)
            // Decode as: 2^(encoded * log2(17)) - 1
            Log.d(TAG, "Converting log-encoded HDR to linear half-float...");
            long decodeStart = System.currentTimeMillis();
            long[] hdrHalfFloat = convertLogEncodedToHalfFloat(hdrBitmap);
            Log.d(TAG, "Half-float conversion took " + (System.currentTimeMillis() - decodeStart) + "ms");
            
            Log.d(TAG, "Setting SDR image: " + width + "x" + height + " (compressed JPEG, " + sdrJpegData.length + " bytes)");
            // Set SDR image (base image) as compressed JPEG - much more memory efficient
            encoder.setCompressedImage(
                sdrJpegData, sdrJpegData.length,
                UltraHDRCommon.UHDR_CG_BT709,   // color gamut: BT.709 (sRGB)
                UltraHDRCommon.UHDR_CT_SRGB,    // color transfer: sRGB
                UltraHDRCommon.UHDR_CR_FULL_RANGE, // color range: full range
                UltraHDRCommon.UHDR_SDR_IMG      // intent: SDR image
            );
            
            Log.d(TAG, "Setting HDR image: " + width + "x" + height + " (64bpp half-float)");
            // HDR image in linear half-float format
            // Using 64bpp RGBA half-float with LINEAR transfer - this is the only valid
            // combination for LINEAR color transfer with HDR intent
            encoder.setRawImage(
                hdrHalfFloat, width, height, width,  // pixels, width, height, stride
                UltraHDRCommon.UHDR_CG_BT709,    // color gamut: BT.709
                UltraHDRCommon.UHDR_CT_LINEAR,   // color transfer: linear
                UltraHDRCommon.UHDR_CR_FULL_RANGE, // color range: full range
                UltraHDRCommon.UHDR_IMG_FMT_64bppRGBAHalfFloat, // format: RGBA half-float
                UltraHDRCommon.UHDR_HDR_IMG      // intent: HDR image
            );
            
            // Configure encoder settings
            // Note: setQualityFactor for BASE_IMG is ignored when using setCompressedImage
            // (the SDR JPEG quality was already set during compression above)
            Log.d(TAG, "Configuring encoder settings...");
            encoder.setQualityFactor(gainMapQuality, UltraHDRCommon.UHDR_GAIN_MAP_IMG);
            
            // Configure gain map thresholds based on baseline exposure to reduce blotchiness
            // For images with low/zero baseline EV, small HDR/SDR differences are likely noise,
            // not real HDR content. We use minContentBoost to threshold out these small gains.
            // Higher gamma compresses the lower gain values, further reducing noise visibility.
            float minContentBoost;
            float gamma;
            int scaleFactor;
            
            if (baselineExposure > 2.0f) {
                // Natural HDR with significant highlight headroom: preserve all detail
                minContentBoost = 1.0f;  // No threshold
                gamma = 1.0f;            // Linear
                scaleFactor = 4;         // Full resolution
            } else if (baselineExposure > 0.5f) {
                // Mild natural HDR: slight threshold
                minContentBoost = 1.05f; // Only encode gains > 5%
                gamma = 1.2f;            // Slight compression
                scaleFactor = 4;
            } else {
                // Baseline EV ~0: synthetic expansion, need stronger thresholding
                // Small variations between HDR and SDR are noise, not real highlight detail
                minContentBoost = 1.1f;  // Only encode gains > 10%
                gamma = 1.5f;            // Compress low gains more aggressively
                scaleFactor = 8;         // Coarser map for smoother appearance
            }
            
            Log.d(TAG, String.format("Gain map config for baselineExposure=%.2f EV: " +
                    "minContentBoost=%.2f, gamma=%.2f, scaleFactor=%d",
                    baselineExposure, minContentBoost, gamma, scaleFactor));
            
            // Max content boost is determined by HDR headroom (2^baselineExposure for synthetic,
            // or higher for natural HDR). Use a reasonable default that covers most cases.
            float maxContentBoost = 49.0f;  // ~5.6 EV of HDR headroom
            encoder.setMinMaxContentBoost(minContentBoost, maxContentBoost);
            encoder.setGainMapGamma(gamma);
            encoder.setGainMapScaleFactor(scaleFactor);
            
            encoder.setEncPreset(UltraHDREncoder.UHDR_USAGE_BEST_QUALITY);
            encoder.setOutputFormat(UltraHDREncoder.UHDR_CODEC_JPG);
            
            // Optional: Set target display peak brightness (default is 1000 nits for HLG, 10000 for PQ/Linear)
            // For linear HDR, we'll use the default 10000 nits
            
            // Encode the Ultra HDR image
            Log.d(TAG, "Encoding Ultra HDR image...");
            encoder.encode();
            
            // Get the encoded output
            byte[] encodedData = encoder.getOutput();
            if (encodedData == null || encodedData.length == 0) {
                Log.e(TAG, "Encoder returned empty output");
                return false;
            }
            
            // Write to file
            try (FileOutputStream out = new FileOutputStream(outputFile)) {
                out.write(encodedData);
            }
            
            Log.i(TAG, String.format(
                "Successfully encoded Ultra HDR JPEG: size=%dx%d, quality=%d, gainMapQuality=%d, outputSize=%d bytes",
                width, height, quality, gainMapQuality, encodedData.length));
            
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to encode Ultra HDR JPEG", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error encoding Ultra HDR JPEG", e);
            return false;
        } finally {
            if (encoder != null) {
                try {
                    encoder.close();
                } catch (Exception e) {
                    Log.w(TAG, "Error closing encoder", e);
                }
            }
        }
    }
    
    /**
     * Encodes an Ultra HDR JPEG from SDR and HDR bitmaps with default quality settings.
     * 
     * @param sdrBitmap The SDR (base) bitmap in sRGB color space
     * @param hdrBitmap The HDR bitmap in linear color space
     * @param outputFile Output file to write the Ultra HDR JPEG to
     * @param quality JPEG quality for base image (0-100)
     * @param baselineExposure The DNG baseline exposure value (EV) used to configure gain map thresholds
     * @return true if successful, false otherwise
     */
    public static boolean encodeUltraHdr(Bitmap sdrBitmap, Bitmap hdrBitmap, 
                                         File outputFile, int quality, float baselineExposure) {
        // Use slightly lower quality for gain map to reduce file size
        int gainMapQuality = Math.max(85, quality - 5);
        return encodeUltraHdr(sdrBitmap, hdrBitmap, outputFile, quality, gainMapQuality, baselineExposure);
    }
    
    /**
     * Converts log-encoded HDR bitmap to linear half-float format.
     * 
     * The shader encoded as: log2(1 + x) / log2(17)
     * This decodes as: 2^(encoded * log2(17)) - 1
     * 
     * The result is stored as 64bpp RGBA half-float, which is the only format
     * compatible with LINEAR color transfer for HDR intent in libultrahdr.
     * 
     * Using half-float preserves more precision than 8-bit linear and avoids
     * color shifts from intermediate quantization.
     * 
     * This method reads from the bitmap in scanlines to avoid allocating a full
     * int[] array alongside the long[] result, which would cause OOM for large images.
     * 
     * @param hdrBitmap Input bitmap (log-encoded 8-bit RGBA)
     * @return Half-float pixel array (each long contains 4 x 16-bit half-floats: R|G|B|A)
     */
    private static long[] convertLogEncodedToHalfFloat(Bitmap hdrBitmap) {
        int width = hdrBitmap.getWidth();
        int height = hdrBitmap.getHeight();
        long[] result = new long[width * height];
        
        // Process in scanlines to minimize memory usage
        // This avoids holding both a full int[] and full long[] in memory
        int[] scanline = new int[width];
        
        for (int y = 0; y < height; y++) {
            hdrBitmap.getPixels(scanline, 0, width, 0, y, width, 1);
            int baseIdx = y * width;
            
            for (int x = 0; x < width; x++) {
                int pixel = scanline[x];
                int a = (pixel >> 24) & 0xFF;
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                
                // Look up pre-computed half-float values from static LUTs
                short rh = LOG_TO_HALF_LUT[r];
                short gh = LOG_TO_HALF_LUT[g];
                short bh = LOG_TO_HALF_LUT[b];
                short ah = ALPHA_TO_HALF_LUT[a];
                
                // Pack into long in RGBA order to match libultrahdr's expected format
                // UHDR_IMG_FMT_64bppRGBAHalfFloat expects: R (bytes 0-1), G (bytes 2-3), B (bytes 4-5), A (bytes 6-7)
                // On little-endian: R in bits 0-15, G in bits 16-31, B in bits 32-47, A in bits 48-63
                result[baseIdx + x] = ((long)(rh & 0xFFFF)) |
                            (((long)(gh & 0xFFFF)) << 16) |
                            (((long)(bh & 0xFFFF)) << 32) |
                            (((long)(ah & 0xFFFF)) << 48);
            }
        }
        
        return result;
    }
    
    /**
     * Converts a float to IEEE 754 half-precision (binary16) format.
     * 
     * Half-float format:
     * - Sign: 1 bit
     * - Exponent: 5 bits (bias of 15)
     * - Mantissa: 10 bits
     * 
     * @param f Input float value
     * @return Half-float representation as a short
     */
    private static short floatToHalf(float f) {
        int bits = Float.floatToIntBits(f);
        int sign = (bits >> 31) & 0x1;
        int exp = (bits >> 23) & 0xFF;
        int mantissa = bits & 0x7FFFFF;
        
        // Handle special cases
        if (exp == 0) {
            // Zero or denormalized float -> zero half
            return (short)(sign << 15);
        } else if (exp == 255) {
            // Inf or NaN
            if (mantissa == 0) {
                // Infinity
                return (short)((sign << 15) | 0x7C00);
            } else {
                // NaN - preserve some mantissa bits
                return (short)((sign << 15) | 0x7C00 | (mantissa >> 13));
            }
        }
        
        // Adjust exponent from float bias (127) to half bias (15)
        int newExp = exp - 127 + 15;
        
        if (newExp >= 31) {
            // Overflow to infinity
            return (short)((sign << 15) | 0x7C00);
        } else if (newExp <= 0) {
            // Underflow - convert to denormalized half or zero
            if (newExp < -10) {
                // Too small, flush to zero
                return (short)(sign << 15);
            }
            // Denormalized half-float
            // Add implicit leading 1 to mantissa
            mantissa |= 0x800000;
            // Shift right to create denormalized value
            int shift = 14 - newExp;
            // Round to nearest
            int roundBit = 1 << (shift - 1);
            mantissa = (mantissa + roundBit) >> shift;
            return (short)((sign << 15) | (mantissa & 0x3FF));
        }
        
        // Normal case - round mantissa to 10 bits
        // Round to nearest
        int roundBit = 1 << 12;  // Bit 12 is the first bit that will be dropped
        mantissa += roundBit;
        
        // Check for mantissa overflow from rounding
        if ((mantissa & 0x800000) != 0) {
            mantissa = 0;
            newExp++;
            if (newExp >= 31) {
                return (short)((sign << 15) | 0x7C00);  // Overflow to infinity
            }
        }
        
        return (short)((sign << 15) | (newExp << 10) | ((mantissa >> 13) & 0x3FF));
    }
}

