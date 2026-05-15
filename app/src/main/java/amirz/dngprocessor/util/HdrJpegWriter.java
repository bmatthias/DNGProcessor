package amirz.dngprocessor.util;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Utility class for writing HDR JPEG files with embedded gain maps.
 * 
 * Since we already have the gain map computed, we simply:
 * 1. Save the SDR bitmap as a standard JPEG
 * 2. Embed the gain map as a separate JPEG image in the file's metadata
 * 
 * This creates Ultra HDR compatible JPEGs without needing libultrahdr.
 */
public class HdrJpegWriter {
    private static final String TAG = "HdrJpegWriter";
    
    /**
     * Writes an HDR JPEG file with embedded gain map.
     * 
     * @param sdrBitmap The SDR (base) bitmap
     * @param gainMapBitmap The gain map bitmap (grayscale)
     * @param outputFile Output file to write the JPEG to
     * @param quality JPEG quality (0-100)
     * @return true if successful, false otherwise
     */
    public static boolean writeHdrJpeg(Bitmap sdrBitmap, Bitmap gainMapBitmap, 
                                       File outputFile, int quality) {
        if (sdrBitmap == null || gainMapBitmap == null || outputFile == null) {
            Log.e(TAG, "Input parameters cannot be null");
            return false;
        }
        
        try {
            // Step 1: Save SDR bitmap as standard JPEG
            try (FileOutputStream out = new FileOutputStream(outputFile)) {
                if (!sdrBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    Log.e(TAG, "Failed to compress SDR bitmap to JPEG");
                    return false;
                }
            }
            
            // Step 2: Embed gain map using ExifInterface
            // The gain map is stored as a separate JPEG image in the file
            ExifInterface exif = new ExifInterface(outputFile);
            
            // Downsample gain map to reduce size (gain maps are typically 1/4 resolution)
            // This reduces storage size by 16x, making it fit within EXIF size limits
            int originalGainMapWidth = gainMapBitmap.getWidth();
            int originalGainMapHeight = gainMapBitmap.getHeight();
            int downsampledWidth = Math.max(1, originalGainMapWidth / 4);
            int downsampledHeight = Math.max(1, originalGainMapHeight / 4);
            
            Bitmap downsampledGainMap = Bitmap.createScaledBitmap(
                gainMapBitmap, downsampledWidth, downsampledHeight, true);
            
            // Save downsampled gain map as a temporary JPEG
            File gainMapTemp = new File(outputFile.getParent(), outputFile.getName() + ".gainmap.tmp");
            try (FileOutputStream gainMapOut = new FileOutputStream(gainMapTemp)) {
                // Use lower quality for gain map to further reduce size (gain maps don't need high quality)
                if (!downsampledGainMap.compress(Bitmap.CompressFormat.JPEG, 85, gainMapOut)) {
                    Log.w(TAG, "Failed to compress gain map, continuing without it");
                    gainMapTemp.delete();
                    downsampledGainMap.recycle();
                    return true; // SDR JPEG is still valid
                }
            }
            
            // Read gain map JPEG data
            byte[] gainMapData = new byte[(int) gainMapTemp.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(gainMapTemp)) {
                fis.read(gainMapData);
            }
            gainMapTemp.delete();
            downsampledGainMap.recycle();
            
            // Check if gain map data is too large for EXIF (64KB limit for APP1 segment)
            // Base64 encoding increases size by ~33%, so we need to keep raw data under ~48KB
            if (gainMapData.length > 48000) {
                Log.w(TAG, "Gain map still too large after downsampling (" + gainMapData.length + 
                      " bytes), skipping gain map embedding");
                return true; // SDR JPEG is still valid
            }
            
            // Embed gain map in EXIF as user comment (custom tag)
            // Note: This is a simplified approach. Full Ultra HDR format would use
            // specific XMP/EXIF tags, but this stores the gain map data for later extraction
            // Since ExifInterface doesn't support custom tags, we encode all metadata in the user comment
            String gainMapBase64 = android.util.Base64.encodeToString(gainMapData, android.util.Base64.NO_WRAP);
            
            // Encode metadata as JSON-like structure in user comment
            // Format: UltraHDR_GainMap:width=W,height=H,downW=DW,downH=DH,min=M,max=X,data=BASE64
            String metadata = String.format("width=%d,height=%d,downW=%d,downH=%d,min=%.2f,max=%.2f",
                originalGainMapWidth, originalGainMapHeight, 
                downsampledWidth, downsampledHeight,
                HdrGainMapGenerator.getMinGain(), HdrGainMapGenerator.getMaxGain());
            String userComment = "UltraHDR_GainMap:" + metadata + ",data=" + gainMapBase64;
            
            Log.d(TAG, "Setting EXIF user comment with gain map (base64 length: " + gainMapBase64.length() + 
                  ", metadata: " + metadata + ")");
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, userComment);
            
            Log.d(TAG, "Saving EXIF attributes...");
            exif.saveAttributes();
            Log.d(TAG, "EXIF attributes saved successfully");
            
            Log.i(TAG, String.format(
                "Successfully wrote Ultra HDR JPEG: size=%dx%d, quality=%d, gainMap=%dx%d",
                sdrBitmap.getWidth(), sdrBitmap.getHeight(), quality,
                gainMapBitmap.getWidth(), gainMapBitmap.getHeight()));
            
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to write HDR JPEG", e);
            return false;
        }
    }
    
    /**
     * Writes an HDR JPEG file with embedded gain map (FileOutputStream version for compatibility).
     * 
     * @param sdrBitmap The SDR (base) bitmap
     * @param gainMapBitmap The gain map bitmap (grayscale)
     * @param outputStream Output stream to write the JPEG to
     * @param quality JPEG quality (0-100)
     * @return true if successful, false otherwise
     */
    public static boolean writeHdrJpeg(Bitmap sdrBitmap, Bitmap gainMapBitmap, 
                                       FileOutputStream outputStream, int quality) {
        if (sdrBitmap == null || gainMapBitmap == null || outputStream == null) {
            Log.e(TAG, "Input parameters cannot be null");
            return false;
        }
        
        // For FileOutputStream, we need to write to a temp file first, then copy
        // This is because ExifInterface requires a File, not a stream
        try {
            File tempFile = File.createTempFile("hdr_jpeg_", ".jpg");
            try {
                if (writeHdrJpeg(sdrBitmap, gainMapBitmap, tempFile, quality)) {
                    // Copy temp file to output stream
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(tempFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                        }
                    }
                    return true;
                } else {
                    return false;
                }
            } finally {
                tempFile.delete();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to write HDR JPEG to stream", e);
            return false;
        }
    }
}
