package amirz.dngprocessor.util;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parser for 3D LUT files in .cube format.
 * 
 * The .cube format is a standard format for 3D color lookup tables used in
 * color grading applications. It supports:
 * - 3D LUTs with configurable dimensions (typically 17x17x17 or 33x33x33)
 * - RGB color data in the range [0.0, 1.0] or [0, 255]
 * - Optional metadata (TITLE, DOMAIN_MIN, DOMAIN_MAX)
 * 
 * Format specification:
 * - Header lines starting with keywords (LUT_3D_SIZE, TITLE, etc.)
 * - RGB triplets, one per line, in the format: R G B
 * - Data is ordered: for each blue, for each green, for each red
 */
public class LutParser {
    private static final String TAG = "LutParser";
    
    private static final Pattern LUT_SIZE_PATTERN = Pattern.compile("LUT_3D_SIZE\\s+(\\d+)");
    private static final Pattern TITLE_PATTERN = Pattern.compile("TITLE\\s+\"([^\"]+)\"");
    private static final Pattern DOMAIN_MIN_PATTERN = Pattern.compile("DOMAIN_MIN\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)");
    private static final Pattern DOMAIN_MAX_PATTERN = Pattern.compile("DOMAIN_MAX\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)");
    
    /**
     * Parse a .cube LUT file from a URI or file path.
     * 
     * @param context Android context for accessing ContentResolver
     * @param pathOrUri File path (String) or URI (String) to the .cube file
     * @return ParsedLut object containing dimensions and RGB data, or null on error
     */
    public static ParsedLut parseCubeFile(Context context, String pathOrUri) {
        if (pathOrUri == null || pathOrUri.isEmpty()) {
            Log.e(TAG, "LUT path/URI is null or empty");
            return null;
        }
        
        // Check if it's a URI (starts with content:// or file://)
        if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
            return parseCubeFileFromUri(context, Uri.parse(pathOrUri));
        } else {
            // Treat as file path
            return parseCubeFileFromPath(new File(pathOrUri));
        }
    }
    
    /**
     * Parse a .cube LUT file from a URI using ContentResolver.
     */
    private static ParsedLut parseCubeFileFromUri(Context context, Uri uri) {
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            
            return parseCubeData(reader, uri.toString());
            
        } catch (IOException e) {
            Log.e(TAG, "Error reading LUT file from URI: " + uri, e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing LUT file from URI: " + uri, e);
            return null;
        }
    }
    
    /**
     * Parse a .cube LUT file from a file path.
     */
    private static ParsedLut parseCubeFileFromPath(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            Log.e(TAG, "LUT file does not exist: " + (file != null ? file.getPath() : "null"));
            return null;
        }
        
        if (!file.getName().toLowerCase().endsWith(".cube")) {
            Log.w(TAG, "File does not have .cube extension: " + file.getName());
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return parseCubeData(reader, file.getPath());
            
        } catch (IOException e) {
            Log.e(TAG, "Error reading LUT file: " + file.getPath(), e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing LUT file: " + file.getPath(), e);
            return null;
        }
    }
    
    /**
     * Parse LUT data from a BufferedReader.
     */
    private static ParsedLut parseCubeData(BufferedReader reader, String sourceName) throws IOException {
            int lutSize = -1;
            String title = null;
            float[] domainMin = null;
            float[] domainMax = null;
            List<float[]> rgbData = new ArrayList<>();
            
            String line;
            boolean inDataSection = false;
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                // Parse header keywords
                java.util.regex.Matcher matcher;
                
                matcher = LUT_SIZE_PATTERN.matcher(line);
                if (matcher.matches()) {
                    lutSize = Integer.parseInt(matcher.group(1));
                    Log.d(TAG, "LUT size: " + lutSize);
                    continue;
                }
                
                matcher = TITLE_PATTERN.matcher(line);
                if (matcher.matches()) {
                    title = matcher.group(1);
                    Log.d(TAG, "LUT title: " + title);
                    continue;
                }
                
                matcher = DOMAIN_MIN_PATTERN.matcher(line);
                if (matcher.matches()) {
                    domainMin = new float[] {
                        Float.parseFloat(matcher.group(1)),
                        Float.parseFloat(matcher.group(2)),
                        Float.parseFloat(matcher.group(3))
                    };
                    continue;
                }
                
                matcher = DOMAIN_MAX_PATTERN.matcher(line);
                if (matcher.matches()) {
                    domainMax = new float[] {
                        Float.parseFloat(matcher.group(1)),
                        Float.parseFloat(matcher.group(2)),
                        Float.parseFloat(matcher.group(3))
                    };
                    continue;
                }
                
                // Parse RGB data lines
                // Format: R G B (space-separated, can be 0.0-1.0 or 0-255)
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    try {
                        float r = Float.parseFloat(parts[0]);
                        float g = Float.parseFloat(parts[1]);
                        float b = Float.parseFloat(parts[2]);
                        
                        // Normalize from [0, 255] to [0, 1] if needed
                        // If any value > 1.0, assume it's in [0, 255] range
                        if (r > 1.0f || g > 1.0f || b > 1.0f) {
                            r /= 255.0f;
                            g /= 255.0f;
                            b /= 255.0f;
                        }
                        
                        // Clamp to [0, 1]
                        r = Math.max(0.0f, Math.min(1.0f, r));
                        g = Math.max(0.0f, Math.min(1.0f, g));
                        b = Math.max(0.0f, Math.min(1.0f, b));
                        
                        rgbData.add(new float[] { r, g, b });
                        inDataSection = true;
                    } catch (NumberFormatException e) {
                        // Skip invalid lines
                        Log.w(TAG, "Skipping invalid RGB line: " + line);
                    }
                }
            }
            
        // Validate LUT size
        if (lutSize <= 0) {
            Log.e(TAG, "Invalid or missing LUT_3D_SIZE in file: " + sourceName);
            return null;
        }
        
        int expectedEntries = lutSize * lutSize * lutSize;
        if (rgbData.size() != expectedEntries) {
            Log.e(TAG, "LUT data size mismatch: expected " + expectedEntries + 
                  " entries, found " + rgbData.size() + " in file: " + sourceName);
            return null;
        }
        
        // Convert List<float[]> to flat float array
        float[] flatData = new float[rgbData.size() * 3];
        for (int i = 0; i < rgbData.size(); i++) {
            float[] rgb = rgbData.get(i);
            flatData[i * 3 + 0] = rgb[0];
            flatData[i * 3 + 1] = rgb[1];
            flatData[i * 3 + 2] = rgb[2];
        }
        
        // Extract filename from source name for logging
        String fileName = sourceName;
        if (sourceName.contains("/")) {
            fileName = sourceName.substring(sourceName.lastIndexOf('/') + 1);
        }
        
        Log.i(TAG, "Successfully parsed LUT file: " + fileName + 
              " (size: " + lutSize + "x" + lutSize + "x" + lutSize + 
              ", title: " + (title != null ? title : "none") + ")");
        
        return new ParsedLut(lutSize, lutSize, lutSize, flatData, title, domainMin, domainMax);
    }
    
    /**
     * Container class for parsed LUT data.
     */
    public static class ParsedLut {
        public final int depth;  // Blue axis
        public final int rows;   // Green axis
        public final int cols;   // Red axis
        public final float[] data;  // Flat array of RGB triplets: [R0, G0, B0, R1, G1, B1, ...]
        public final String title;
        public final float[] domainMin;
        public final float[] domainMax;
        
        public ParsedLut(int depth, int rows, int cols, float[] data, String title, 
                         float[] domainMin, float[] domainMax) {
            this.depth = depth;
            this.rows = rows;
            this.cols = cols;
            this.data = data;
            this.title = title;
            this.domainMin = domainMin;
            this.domainMax = domainMax;
        }
    }
}

