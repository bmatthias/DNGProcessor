package amirz.dngprocessor.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.google.media.codecs.ultrahdr.UltraHDRCommon;
import com.google.media.codecs.ultrahdr.UltraHDRDecoder;

import java.io.IOException;

/**
 * Wrapper for libultrahdr to extract gain maps from Ultra HDR JPEGs.
 * This provides a fallback when HdrImageReader is not available.
 * Uses the existing UltraHDRDecoder class from libultrahdr.
 */
public class UltraHdrNative {
    private static final String TAG = "UltraHdrNative";
    private static boolean sLibraryLoaded = false;
    
    static {
        try {
            // Try to load the libultrahdr JNI library
            // The library name is "uhdrjni" as defined in UltraHDRDecoder
            System.loadLibrary("uhdrjni");
            sLibraryLoaded = true;
            Log.d(TAG, "Successfully loaded libultrahdr JNI library");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Failed to load libultrahdr JNI library", e);
            sLibraryLoaded = false;
        }
    }
    
    /**
     * Checks if the native library is loaded and available.
     * 
     * @return true if the library is loaded, false otherwise
     */
    public static boolean isAvailable() {
        return sLibraryLoaded;
    }
    
    /**
     * Extracts gain map from Ultra HDR JPEG data.
     * 
     * @param jpegData Byte array containing Ultra HDR JPEG data
     * @return Bitmap containing the gain map, or null if extraction fails
     */
    public static Bitmap extractGainMap(byte[] jpegData) {
        if (!sLibraryLoaded) {
            Log.w(TAG, "libultrahdr library not loaded");
            return null;
        }
        
        if (jpegData == null || jpegData.length == 0) {
            Log.w(TAG, "JPEG data is null or empty");
            return null;
        }
        
        try {
            // Try to check if it's a valid Ultra HDR image (but don't fail if check is too strict)
            boolean isUHDR = false;
            try {
                isUHDR = UltraHDRDecoder.isUHDRImage(jpegData, jpegData.length);
                if (!isUHDR) {
                    Log.d(TAG, "isUHDRImage() returned false, but will attempt probe anyway");
                }
            } catch (Exception e) {
                Log.d(TAG, "isUHDRImage() check failed, will attempt probe anyway", e);
            }
            
            // Create decoder instance and try to probe even if isUHDRImage() returned false
            // Some valid Ultra HDR images might not pass the strict check
            UltraHDRDecoder decoder = new UltraHDRDecoder();
            try {
                // Set compressed image
                decoder.setCompressedImage(jpegData, jpegData.length,
                    UltraHDRCommon.UHDR_CG_UNSPECIFIED,
                    UltraHDRCommon.UHDR_CT_UNSPECIFIED,
                    UltraHDRCommon.UHDR_CR_UNSPECIFIED);
                
                // Probe to parse the image - this will tell us if there's actually a gain map
                try {
                    decoder.probe();
                } catch (IOException e) {
                    // Check if this is the "no gainmap" error - handle gracefully
                    String errorMsg = e.getMessage();
                    if (errorMsg != null && errorMsg.contains("does not contain gainmap")) {
                        Log.i(TAG, "Image does not contain gain map");
                        return null;
                    }
                    // Re-throw other IOExceptions
                    throw e;
                }
                
                // Get gain map dimensions
                int gainMapWidth = decoder.getGainMapWidth();
                int gainMapHeight = decoder.getGainMapHeight();
                
                if (gainMapWidth <= 0 || gainMapHeight <= 0) {
                    Log.d(TAG, "No gain map found (dimensions: " + gainMapWidth + "x" + gainMapHeight + ")");
                    return null;
                }
                
                Log.d(TAG, "Found gain map: " + gainMapWidth + "x" + gainMapHeight);
                
                // Get compressed gain map image (JPEG)
                byte[] gainMapJpeg = decoder.getGainMapImage();
                if (gainMapJpeg == null || gainMapJpeg.length == 0) {
                    Log.w(TAG, "Failed to get gain map image data");
                    return null;
                }
                
                // Decode the gain map JPEG to Bitmap
                Bitmap gainMap = BitmapFactory.decodeByteArray(gainMapJpeg, 0, gainMapJpeg.length);
                if (gainMap != null) {
                    Log.i(TAG, "Successfully extracted gain map using libultrahdr: " + 
                          gainMap.getWidth() + "x" + gainMap.getHeight());
                    return gainMap;
                } else {
                    Log.w(TAG, "Failed to decode gain map JPEG to Bitmap");
                    return null;
                }
                
            } finally {
                decoder.close();
            }
            
        } catch (IOException e) {
            // Check if this is the "no gainmap" error - already handled in probe(), but catch here too
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("does not contain gainmap")) {
                Log.i(TAG, "Image does not contain gain map");
            } else {
                Log.d(TAG, "Failed to extract gain map using libultrahdr: " + e.getMessage());
            }
            return null;
        } catch (Exception e) {
            Log.d(TAG, "Unexpected error extracting gain map using libultrahdr: " + e.getMessage());
            return null;
        }
    }
}

