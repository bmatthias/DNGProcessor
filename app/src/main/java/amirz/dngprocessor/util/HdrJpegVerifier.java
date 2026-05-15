package amirz.dngprocessor.util;

import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import androidx.exifinterface.media.ExifInterface;

/**
 * Utility class for verifying Ultra HDR JPEG files.
 * 
 * Checks for gain map metadata in EXIF tags (our custom format)
 * or uses Android's HdrImageReader API if available.
 */
public class HdrJpegVerifier {
    private static final String TAG = "HdrJpegVerifier";
    
    // Android HdrImageReader class (available in API 34+, but may be hidden)
    private static final String HDR_IMAGE_READER_CLASS = "android.graphics.HdrImageReader";
    
    // EXIF tag prefix for our gain map storage
    private static final String GAIN_MAP_PREFIX = "UltraHDR_GainMap:";
    
    /**
     * Extracts a metadata value from the user comment string.
     * Format: key=value,key2=value2,...
     */
    private static String extractMetadataValue(String metadata, String key) {
        String searchKey = key + "=";
        int startIndex = metadata.indexOf(searchKey);
        if (startIndex == -1) {
            return null;
        }
        startIndex += searchKey.length();
        int endIndex = metadata.indexOf(",", startIndex);
        if (endIndex == -1) {
            // Last value or value is before "data="
            int dataIndex = metadata.indexOf(",data=");
            if (dataIndex != -1 && startIndex < dataIndex) {
                endIndex = dataIndex;
            } else {
                endIndex = metadata.length();
            }
        }
        return metadata.substring(startIndex, endIndex);
    }
    
    /**
     * Verifies if a JPEG file is an Ultra HDR JPEG with embedded gain map.
     * First checks for our custom EXIF tags, then falls back to HdrImageReader if available.
     * 
     * @param filePath Path to the JPEG file
     * @return true if the file is a valid Ultra HDR JPEG, false otherwise
     */
    public static boolean verifyUltraHdrJpeg(String filePath) {
        // First, check for our custom EXIF tags (this works on all Android versions)
        try {
            ExifInterface exif = new ExifInterface(filePath);
            
            // Check for gain map in user comment
            String userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT);
            Log.d(TAG, "EXIF user comment: " + (userComment != null ? 
                  (userComment.length() > 100 ? userComment.substring(0, 100) + "..." : userComment) : "null"));
            
            if (userComment != null && userComment.startsWith(GAIN_MAP_PREFIX)) {
                // Parse metadata from user comment
                // Format: UltraHDR_GainMap:width=W,height=H,downW=DW,downH=DH,min=M,max=X,data=BASE64
                String metadataPart = userComment.substring(GAIN_MAP_PREFIX.length());
                
                // Extract metadata values
                String width = extractMetadataValue(metadataPart, "width");
                String height = extractMetadataValue(metadataPart, "height");
                String downsampledWidth = extractMetadataValue(metadataPart, "downW");
                String downsampledHeight = extractMetadataValue(metadataPart, "downH");
                String minGain = extractMetadataValue(metadataPart, "min");
                String maxGain = extractMetadataValue(metadataPart, "max");
                
                Log.d(TAG, "EXIF gain map metadata - width: " + width + ", height: " + height +
                      ", downsampled: " + downsampledWidth + "x" + downsampledHeight +
                      ", min: " + minGain + ", max: " + maxGain);
                
                if (width != null && height != null) {
                    Log.i(TAG, "Verified Ultra HDR JPEG (EXIF): gain map size = " + 
                          width + "x" + height);
                    return true;
                } else {
                    Log.w(TAG, "User comment contains gain map prefix but metadata parsing failed");
                }
            } else {
                Log.d(TAG, "User comment does not contain gain map prefix or is null");
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to read EXIF from file: " + filePath, e);
        }
        
        // Fall back to HdrImageReader if available (may be hidden API)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false;
        }
        
        try (FileInputStream fis = new FileInputStream(filePath)) {
            // Use reflection to access HdrImageReader
            Class<?> hdrReaderClass = Class.forName(HDR_IMAGE_READER_CLASS);
            
            // Create HdrImageReader instance
            // Constructor: HdrImageReader(InputStream)
            Constructor<?> constructor = hdrReaderClass.getConstructor(
                java.io.InputStream.class);
            Object hdrReader = constructor.newInstance(fis);
            
            // Check if gain map exists
            // Method: hasGainMap() -> boolean
            Method hasGainMapMethod = hdrReaderClass.getMethod("hasGainMap");
            boolean hasGainMap = (Boolean) hasGainMapMethod.invoke(hdrReader);
            
            if (hasGainMap) {
                // Get gain map if available
                // Method: getGainMap() -> Bitmap
                Method getGainMapMethod = hdrReaderClass.getMethod("getGainMap");
                Bitmap gainMap = (Bitmap) getGainMapMethod.invoke(hdrReader);
                
                if (gainMap != null) {
                    Log.i(TAG, "Verified Ultra HDR JPEG (HdrImageReader): gain map size = " + 
                          gainMap.getWidth() + "x" + gainMap.getHeight());
                    gainMap.recycle();
                    return true;
                }
            }
            
            Log.d(TAG, "JPEG file does not contain Ultra HDR gain map");
            return false;
            
        } catch (ClassNotFoundException e) {
            // HdrImageReader not available - this is expected, not an error
            Log.d(TAG, "HdrImageReader not available (hidden API), using EXIF verification only");
            return false;
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "HdrImageReader method not found - API may have changed", e);
            return false;
        } catch (Exception e) {
            Log.d(TAG, "HdrImageReader verification failed (expected if API is hidden)", e);
            return false;
        }
    }
    
    /**
     * Alternative: Check for Ultra HDR metadata in EXIF.
     * Ultra HDR JPEGs may have specific EXIF tags indicating gain map presence.
     * 
     * @param filePath Path to the JPEG file
     * @return true if EXIF indicates Ultra HDR, false otherwise
     */
    public static boolean verifyUltraHdrByExif(String filePath) {
        return verifyUltraHdrJpeg(filePath); // Now uses EXIF first
    }
    
    /**
     * Get file size for comparison (UHDR files are typically larger).
     * 
     * @param filePath Path to the JPEG file
     * @return File size in bytes, or -1 if file doesn't exist
     */
    public static long getFileSize(String filePath) {
        try {
            java.io.File file = new java.io.File(filePath);
            if (file.exists()) {
                return file.length();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get file size", e);
        }
        return -1;
    }
    
    /**
     * Extracts gain map from embedded JPEG using EXIF metadata.
     * 
     * @param filePath Path to the JPEG file
     * @return Bitmap containing the gain map, or null if no gain map is present or extraction fails
     */
    public static Bitmap extractGainMapFromJpeg(String filePath) {
        try {
            ExifInterface exif = new ExifInterface(filePath);
            
            // Check for gain map in user comment
            String userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT);
            
            // Log user comment content for debugging (truncate if too long)
            if (userComment != null) {
                String preview = userComment.length() > 200 ? 
                    userComment.substring(0, 200) + "..." : userComment;
                Log.d(TAG, "EXIF user comment from embedded JPEG (" + userComment.length() + " chars): " + preview);
            } else {
                Log.d(TAG, "EXIF user comment is null - no gain map data in EXIF");
            }
            
            if (userComment != null && userComment.startsWith(GAIN_MAP_PREFIX)) {
                // Extract base64-encoded gain map JPEG from user comment
                // Format: UltraHDR_GainMap:width=W,height=H,...,data=BASE64
                String metadataPart = userComment.substring(GAIN_MAP_PREFIX.length());
                int dataIndex = metadataPart.indexOf(",data=");
                if (dataIndex == -1) {
                    Log.w(TAG, "Gain map data not found in user comment");
                } else {
                    String gainMapBase64 = metadataPart.substring(dataIndex + 6); // Skip ",data="
                    byte[] gainMapData = android.util.Base64.decode(gainMapBase64, android.util.Base64.NO_WRAP);
                    
                    // Decode gain map JPEG to bitmap
                    Bitmap downsampledGainMap = android.graphics.BitmapFactory.decodeByteArray(gainMapData, 0, gainMapData.length);
                    
                    if (downsampledGainMap != null) {
                        // Get metadata from user comment to determine if we need to upscale
                        // metadataPart was already extracted above
                        String originalWidthStr = extractMetadataValue(metadataPart, "width");
                        String originalHeightStr = extractMetadataValue(metadataPart, "height");
                        String downsampledWidthStr = extractMetadataValue(metadataPart, "downW");
                        String downsampledHeightStr = extractMetadataValue(metadataPart, "downH");
                        
                        // If we have original dimensions and they differ from downsampled, scale up
                        if (originalWidthStr != null && originalHeightStr != null) {
                            try {
                                int originalWidth = Integer.parseInt(originalWidthStr);
                                int originalHeight = Integer.parseInt(originalHeightStr);
                                
                                int downsampledWidth = downsampledGainMap.getWidth();
                                int downsampledHeight = downsampledGainMap.getHeight();
                                if (originalWidth != downsampledWidth || 
                                    originalHeight != downsampledHeight) {
                                    // Scale up to original size
                                    Bitmap fullSizeGainMap = Bitmap.createScaledBitmap(
                                        downsampledGainMap, originalWidth, originalHeight, true);
                                    downsampledGainMap.recycle();
                                    Log.i(TAG, "Extracted and upscaled gain map from EXIF: " + 
                                          originalWidth + "x" + originalHeight + 
                                          " (stored as " + downsampledWidth + "x" + downsampledHeight + ")");
                                    return fullSizeGainMap;
                                }
                            } catch (NumberFormatException e) {
                                Log.w(TAG, "Failed to parse gain map dimensions", e);
                            }
                        }
                        
                        Log.i(TAG, "Extracted gain map from EXIF: " + 
                              downsampledGainMap.getWidth() + "x" + downsampledGainMap.getHeight());
                        return downsampledGainMap;
                    }
                }
            } else {
                Log.d(TAG, "No gain map found in EXIF (user comment doesn't start with '" + GAIN_MAP_PREFIX + "')");
            }
            
            // EXIF extraction failed or no custom format found, try HdrImageReader and libultrahdr
            // Read the file as byte array for fallback extraction
            try {
                java.io.FileInputStream fis = new java.io.FileInputStream(filePath);
                byte[] jpegData = new byte[(int) new java.io.File(filePath).length()];
                fis.read(jpegData);
                fis.close();
                
                // Use the fallback extraction (HdrImageReader/libultrahdr) directly
                return extractGainMapFromJpegDataFallback(jpegData);
            } catch (Exception e) {
                Log.w(TAG, "Failed to read JPEG file for fallback extraction", e);
                return null;
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract gain map from EXIF", e);
            // Try fallback methods even if EXIF read failed
            try {
                java.io.FileInputStream fis = new java.io.FileInputStream(filePath);
                byte[] jpegData = new byte[(int) new java.io.File(filePath).length()];
                fis.read(jpegData);
                fis.close();
                return extractGainMapFromJpegDataFallback(jpegData);
            } catch (Exception ex) {
                Log.w(TAG, "Failed to read JPEG file for fallback extraction", ex);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error extracting gain map", e);
            return null;
        }
    }
    
    /**
     * Fallback extraction using HdrImageReader and libultrahdr (without EXIF check).
     * This is used to avoid circular calls between extractGainMapFromJpeg and extractGainMapFromJpegData.
     * 
     * @param jpegData Byte array containing JPEG data
     * @return Bitmap containing the gain map, or null if extraction fails
     */
    private static Bitmap extractGainMapFromJpegDataFallback(byte[] jpegData) {
        if (jpegData == null || jpegData.length == 0) {
            return null;
        }
        
        // Basic JPEG validation - check for JPEG header
        if (jpegData.length < 2 || 
            (jpegData[0] & 0xFF) != 0xFF || (jpegData[1] & 0xFF) != 0xD8) {
            Log.w(TAG, "JPEG data does not start with JPEG header (0xFF 0xD8)");
            return null;
        }
        
        Log.d(TAG, "Attempting to extract gain map from JPEG data (" + jpegData.length + " bytes)");
        
        // Fall back to HdrImageReader if available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                // Use reflection to access HdrImageReader
                Class<?> hdrReaderClass = Class.forName(HDR_IMAGE_READER_CLASS);
                
                // Create HdrImageReader instance from byte array
                // Constructor: HdrImageReader(InputStream)
                Constructor<?> constructor = hdrReaderClass.getConstructor(
                    java.io.InputStream.class);
                java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(jpegData);
                Object hdrReader = constructor.newInstance(bais);
                
                // Check if gain map exists
                Method hasGainMapMethod = hdrReaderClass.getMethod("hasGainMap");
                boolean hasGainMap = (Boolean) hasGainMapMethod.invoke(hdrReader);
                
                Log.d(TAG, "HdrImageReader.hasGainMap() = " + hasGainMap);
                
                if (hasGainMap) {
                    // Get gain map if available
                    Method getGainMapMethod = hdrReaderClass.getMethod("getGainMap");
                    Bitmap gainMap = (Bitmap) getGainMapMethod.invoke(hdrReader);
                    
                    if (gainMap != null) {
                        // Create a copy since the original may be tied to the reader
                        Bitmap gainMapCopy = gainMap.copy(gainMap.getConfig(), true);
                        Log.i(TAG, "Extracted gain map from HdrImageReader: " + 
                              gainMapCopy.getWidth() + "x" + gainMapCopy.getHeight());
                        return gainMapCopy;
                    } else {
                        Log.w(TAG, "HdrImageReader.hasGainMap() returned true but getGainMap() returned null");
                    }
                } else {
                    Log.d(TAG, "Embedded JPEG does not contain Ultra HDR gain map (hasGainMap() = false)");
                }
            } catch (ClassNotFoundException e) {
                // HdrImageReader not available - try libultrahdr as fallback
                Log.d(TAG, "HdrImageReader not available (hidden API), trying libultrahdr fallback");
            } catch (Exception e) {
                // Check if this is the "No image meets the size requirements" error
                String errorMsg = e.getMessage();
                if (errorMsg != null && errorMsg.contains("size requirements")) {
                    Log.w(TAG, "Embedded JPEG does not meet size requirements for Ultra HDR " +
                          "(likely not a thumbnail/preview or not an Ultra HDR JPEG): " + errorMsg);
                } else {
                    Log.d(TAG, "HdrImageReader extraction failed (expected if API is hidden or image is not Ultra HDR)", e);
                }
            }
        } else {
            Log.d(TAG, "HdrImageReader not available on this Android version (requires API 34+)");
        }
        
        // Try libultrahdr native library as fallback
        if (UltraHdrNative.isAvailable()) {
            try {
                Log.d(TAG, "Attempting to extract gain map using libultrahdr...");
                Bitmap gainMap = UltraHdrNative.extractGainMap(jpegData);
                if (gainMap != null) {
                    Log.i(TAG, "Successfully extracted gain map using libultrahdr: " + 
                          gainMap.getWidth() + "x" + gainMap.getHeight());
                    return gainMap;
                } else {
                    Log.d(TAG, "libultrahdr extraction returned null");
                }
            } catch (Exception ex) {
                Log.w(TAG, "libultrahdr extraction failed", ex);
            }
        } else {
            Log.d(TAG, "libultrahdr is not available.");
        }
        
        return null;
    }
    
    /**
     * Extracts gain map from embedded JPEG data (byte array).
     * Uses EXIF metadata first, then falls back to HdrImageReader if available.
     * 
     * @param jpegData Byte array containing JPEG data (potentially with Ultra HDR gain map)
     * @return Bitmap containing the gain map, or null if no gain map is present or extraction fails
     */
    public static Bitmap extractGainMapFromJpegData(byte[] jpegData) {
        if (jpegData == null || jpegData.length == 0) {
            Log.w(TAG, "JPEG data is null or empty");
            return null;
        }
        
        // Try EXIF first (works on all Android versions)
        // This checks for our custom format (UltraHDR_GainMap:...) in EXIF user comment
        try {
            // Write to temp file to use ExifInterface
            File tempFile = File.createTempFile("jpeg_verify_", ".jpg");
            try {
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                    fos.write(jpegData);
                }
                // Read EXIF directly to avoid circular call
                ExifInterface exif = new ExifInterface(tempFile.getAbsolutePath());
                String userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT);
                
                if (userComment != null && userComment.startsWith(GAIN_MAP_PREFIX)) {
                    // Extract base64-encoded gain map JPEG from user comment
                    String metadataPart = userComment.substring(GAIN_MAP_PREFIX.length());
                    int dataIndex = metadataPart.indexOf(",data=");
                    if (dataIndex != -1) {
                        String gainMapBase64 = metadataPart.substring(dataIndex + 6); // Skip ",data="
                        byte[] gainMapData = android.util.Base64.decode(gainMapBase64, android.util.Base64.NO_WRAP);
                        
                        // Decode gain map JPEG to bitmap
                        Bitmap downsampledGainMap = android.graphics.BitmapFactory.decodeByteArray(gainMapData, 0, gainMapData.length);
                        
                        if (downsampledGainMap != null) {
                            // Get metadata from user comment to determine if we need to upscale
                            String originalWidthStr = extractMetadataValue(metadataPart, "width");
                            String originalHeightStr = extractMetadataValue(metadataPart, "height");
                            
                            // If we have original dimensions and they differ from downsampled, scale up
                            if (originalWidthStr != null && originalHeightStr != null) {
                                try {
                                    int originalWidth = Integer.parseInt(originalWidthStr);
                                    int originalHeight = Integer.parseInt(originalHeightStr);
                                    
                                    int downsampledWidth = downsampledGainMap.getWidth();
                                    int downsampledHeight = downsampledGainMap.getHeight();
                                    if (originalWidth != downsampledWidth || 
                                        originalHeight != downsampledHeight) {
                                        // Scale up to original size
                                        Bitmap fullSizeGainMap = Bitmap.createScaledBitmap(
                                            downsampledGainMap, originalWidth, originalHeight, true);
                                        downsampledGainMap.recycle();
                                        Log.i(TAG, "Successfully extracted gain map from EXIF (custom format)");
                                        return fullSizeGainMap;
                                    }
                                } catch (NumberFormatException ex) {
                                    Log.w(TAG, "Failed to parse gain map dimensions", ex);
                                }
                            }
                            
                            Log.i(TAG, "Successfully extracted gain map from EXIF (custom format)");
                            return downsampledGainMap;
                        }
                    }
                }
            } finally {
                tempFile.delete();
            }
        } catch (Exception e) {
            Log.d(TAG, "EXIF extraction failed, trying HdrImageReader", e);
        }
        
        // Fall back to HdrImageReader and libultrahdr
        return extractGainMapFromJpegDataFallback(jpegData);
    }
}
