package amirz.dngprocessor.util;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;

import amirz.dngprocessor.Preferences;

public class Path {
    private static final String TAG = "Path";

    public static final String EXT_RAW = ".dng";
    public static final String EXT_JPG = ".jpg";

    public static final String MIME_RAW = "image/x-adobe-dng";
    public static final String MIME_RAW_ALT = "image/dng";
    public static final String MIME_JPG = "image/jpeg";

    public static final String ROOT = Environment.getExternalStorageDirectory().toString();

    public static boolean isRaw(ContentResolver contentResolver, Uri uri, String file) {
        // Check file extension first (most reliable)
        if (file != null && file.toLowerCase().endsWith(EXT_RAW)) {
            return true;
        }
        // Fall back to MIME type check
        String mime = contentResolver.getType(uri);
        return MIME_RAW.equals(mime) || MIME_RAW_ALT.equals(mime);
    }

    public static String processedPath(String dir, String name) {
        dir = ROOT + File.separator + dir;
        File folder = new File(dir);
        if (!folder.exists() && !folder.mkdir()) {
            throw new RuntimeException("Cannot create " + dir);
        }
        name = name.replace(EXT_RAW, EXT_JPG);
        if (Preferences.global().suffix.get() && name.startsWith("IMG")) {
            String replacement = Preferences.global().replacePrefixText.get();
            if (replacement != null && !replacement.isEmpty()) {
                name = replacement + name.substring(3);
            }
        }
        if (Preferences.global().addSuffix.get()) {
            String suffixText = Preferences.global().addSuffixText.get();
            if (suffixText != null && !suffixText.isEmpty()) {
                int dotIndex = name.lastIndexOf('.');
                if (dotIndex > 0) {
                    name = name.substring(0, dotIndex) + "_" + suffixText + name.substring(dotIndex);
                } else {
                    name = name + "_" + suffixText;
                }
            }
        }
        return dir + File.separator + name;
    }

    /**
     * Get the path to the original JPEG file based on DNG filename and location preference
     * @param jpegLocation Location directory (e.g., "DCIM/Camera")
     * @param dngFileName Original DNG filename (e.g., "IMG_1234.dng")
     * @return Full path to the JPEG file, or null if not found
     */
    public static String getOriginalJpegPath(String jpegLocation, String dngFileName) {
        // Replace .dng extension with .jpg
        String jpegFileName = dngFileName.replace(EXT_RAW, EXT_JPG);
        // Build full path
        String jpegPath = ROOT + File.separator + jpegLocation + File.separator + jpegFileName;
        File jpegFile = new File(jpegPath);
        if (jpegFile.exists()) {
            return jpegPath;
        }
        return null;
    }

    public static String getFileFromUri(Context context, Uri uri) {
        String fileName = getColumn(context, uri, OpenableColumns.DISPLAY_NAME);

        /* document/raw:PATH */
        if (fileName == null) {
            String result = getPathFromUri(context, uri);
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                fileName = result.substring(cut + 1);
            }
        }

        Log.d(TAG, "Resolved " + uri.toString() + " to name " + fileName);
        return fileName;
    }

    public static String getPathFromUri(Context context, Uri uri) {
        String filePath = getColumn(context, uri, MediaStore.Images.Media.DATA);

        /* document/raw:PATH */
        if (filePath == null) {
            // Try Downloads provider with _data column
            if (DocumentsContract.isDocumentUri(context, uri)) {
                String id = DocumentsContract.getDocumentId(uri);
                String p = uri.getPath();
                if (p != null && p.startsWith("/document")) {
                    try {
                        long l = Long.parseLong(id.contains(":") ? id.split(":")[1] : id);
                        // Try querying Downloads provider directly for _data column
                        filePath = query(context.getContentResolver(), 
                                ContentUris.withAppendedId(
                                        Uri.parse("content://downloads/public_downloads"), l),
                                "_data");
                        if (filePath == null) {
                            // Try with all_downloads
                            filePath = query(context.getContentResolver(),
                                    ContentUris.withAppendedId(
                                            Uri.parse("content://downloads/all_downloads"), l),
                                    "_data");
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            
            if (filePath == null) {
                filePath = uri.getPath();
                if (filePath != null && filePath.contains(":")) {
                    String[] split = filePath.split(":");
                    filePath = split[split.length - 1];
                }
            }
        }

        Log.d(TAG, "Resolved " + uri.toString() + " to path " + filePath);
        return filePath;
    }

    private static String getColumn(Context context, Uri uri, String column) {
        ContentResolver cr = context.getContentResolver();
        String result = null;
        if (DocumentsContract.isDocumentUri(context, uri)) {
            String id = DocumentsContract.getDocumentId(uri);
            if (id.contains(":")) {
                id = id.split(":")[1];
            }

            String p = uri.getPath();
            if (p != null) {
                if (p.startsWith("/document/image")) {
                    /* document/image:NUM */
                    result = query(cr,
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            column,
                            MediaStore.Images.Media._ID + "=?",
                            new String[] { id });
                } else if (p.startsWith("/document")) {
                    /* document/NUM */
                    try {
                        long l = Long.parseLong(id);
                        result = query(cr, ContentUris.withAppendedId(
                                Uri.parse("content://downloads/public_downloads"), l),
                                column);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        /* media/external/images/media/NUM */
        if (result == null) {
            result = query(cr, uri, column);
        }

        return result;
    }

    private static String query(ContentResolver cr, Uri uri, String column) {
        return query(cr, uri, column, null, null);
    }

    private static String query(ContentResolver cr, Uri uri, String column, String selection, String[] selectionArgs) {
        try (Cursor cursor = cr.query(
                uri, new String[] { column }, selection, selectionArgs, null)) {
            if (cursor != null) {
                int columnIndex = cursor.getColumnIndex(column);
                if (cursor.moveToFirst()) {
                    return cursor.getString(columnIndex);
                }
            }
        }
        return null;
    }
}
