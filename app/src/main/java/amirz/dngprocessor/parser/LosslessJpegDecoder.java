package amirz.dngprocessor.parser;

import android.util.Log;

/**
 * Decoder for lossless JPEG (SOF3) used in DNG files with 12-bit or 16-bit precision.
 * This handles the JPEG compression used by Xiaomi UltraRAW and similar formats.
 */
public class LosslessJpegDecoder {
    private static final String TAG = "LosslessJpegDecoder";

    // JPEG markers
    private static final int MARKER_SOI = 0xFFD8;  // Start of Image
    private static final int MARKER_EOI = 0xFFD9;  // End of Image
    private static final int MARKER_SOF0 = 0xFFC0; // Baseline DCT
    private static final int MARKER_SOF1 = 0xFFC1; // Extended sequential DCT
    private static final int MARKER_SOF2 = 0xFFC2; // Progressive DCT
    private static final int MARKER_SOF3 = 0xFFC3; // Lossless (sequential)
    private static final int MARKER_DHT = 0xFFC4;  // Define Huffman Table
    private static final int MARKER_SOF11 = 0xFFCB; // Lossless (arithmetic)
    private static final int MARKER_SOS = 0xFFDA;  // Start of Scan
    private static final int MARKER_DQT = 0xFFDB;  // Define Quantization Table
    private static final int MARKER_DRI = 0xFFDD;  // Define Restart Interval
    private static final int MARKER_APP0 = 0xFFE0; // Application marker

    private int width;
    private int height;
    private int precision;
    private int components;
    private int predictor;
    private int restartInterval;

    // Huffman tables (up to 4 DC tables for lossless)
    private final int[][] huffmanMinCode = new int[4][17];
    private final int[][] huffmanMaxCode = new int[4][17];
    private final int[][] huffmanValPtr = new int[4][17];
    private final int[][] huffmanValues = new int[4][256];
    private final int[] huffmanBits = new int[4];

    // Component info
    private final int[] componentHuffTable = new int[4];

    // Bit reader state
    private byte[] data;
    private int dataPos;
    private int bitBuffer;
    private int bitsInBuffer;

    public static class DecodedTile {
        public short[] pixels;
        public int width;
        public int height;
        public int components;
        public int precision;
    }

    /**
     * Decode a lossless JPEG tile.
     * @param jpegData The raw JPEG data
     * @return Decoded pixel data as 16-bit values, or null if decoding fails
     */
    public DecodedTile decode(byte[] jpegData) {
        if (jpegData == null || jpegData.length < 4) {
            Log.e(TAG, "Invalid JPEG data");
            return null;
        }

        data = jpegData;
        dataPos = 0;
        bitBuffer = 0;
        bitsInBuffer = 0;

        try {
            // Check for JPEG magic
            int marker = readMarker();
            if (marker != MARKER_SOI) {
                Log.e(TAG, "Not a JPEG file (no SOI marker)");
                return null;
            }

            // Parse markers until we hit SOS
            while (true) {
                marker = readMarker();
                
                if (marker == MARKER_EOI) {
                    Log.e(TAG, "Unexpected EOI before SOS");
                    return null;
                }

                if ((marker & 0xFF00) != 0xFF00) {
                    Log.e(TAG, "Invalid marker: " + Integer.toHexString(marker));
                    return null;
                }

                if (marker == MARKER_SOS) {
                    break;
                }

                int length = readUInt16() - 2;

                if (marker == MARKER_SOF3 || marker == MARKER_SOF0 || 
                    marker == MARKER_SOF1 || marker == MARKER_SOF2 || marker == MARKER_SOF11) {
                    parseSOF(marker, length);
                } else if (marker == MARKER_DHT) {
                    parseDHT(length);
                } else if (marker == MARKER_DRI) {
                    restartInterval = readUInt16();
                } else {
                    // Skip unknown marker
                    dataPos += length;
                }
            }

            // Parse SOS header
            parseSOS();

            // Check if this is actually lossless JPEG
            if (!isLosslessFormat) {
                Log.w(TAG, "JPEG is DCT format (not lossless). Precision: " + precision + 
                          ". This decoder only handles lossless JPEG (SOF3).");
                // For DCT JPEG, we can't decode with lossless algorithm
                // Return null to trigger fallback
                return null;
            }
            
            Log.d(TAG, "Decoding lossless JPEG: " + width + "x" + height + 
                      ", " + precision + "-bit, " + components + " components");

            // Decode the image data
            short[] pixels = decodeImageData();

            if (pixels != null) {
                DecodedTile result = new DecodedTile();
                result.pixels = pixels;
                result.width = width;
                result.height = height;
                // Return actual decoded components (from scan, not SOF)
                result.components = scanComponents;
                result.precision = precision;
                Log.d(TAG, "Decode complete: " + width + "x" + height + 
                           ", " + scanComponents + " components, " + precision + "-bit");
                return result;
            }

            return null;

        } catch (Exception e) {
            Log.e(TAG, "Error decoding JPEG: " + e.getMessage());
            return null;
        }
    }

    private int readMarker() {
        int b1 = data[dataPos++] & 0xFF;
        int b2 = data[dataPos++] & 0xFF;
        return (b1 << 8) | b2;
    }

    private int readUInt16() {
        int b1 = data[dataPos++] & 0xFF;
        int b2 = data[dataPos++] & 0xFF;
        return (b1 << 8) | b2;
    }

    private int readUInt8() {
        return data[dataPos++] & 0xFF;
    }

    private boolean isLosslessFormat = false;
    
    private void parseSOF(int marker, int length) {
        precision = readUInt8();
        height = readUInt16();
        width = readUInt16();
        components = readUInt8();

        // SOF3 (0xC3) and SOF11 (0xCB) are lossless formats
        isLosslessFormat = (marker == MARKER_SOF3 || marker == MARKER_SOF11);
        
        String sofType;
        switch (marker) {
            case MARKER_SOF0: sofType = "Baseline DCT"; break;
            case MARKER_SOF1: sofType = "Extended sequential DCT"; break;
            case MARKER_SOF2: sofType = "Progressive DCT"; break;
            case MARKER_SOF3: sofType = "Lossless (sequential)"; break;
            default: sofType = "SOF" + (marker & 0xF);
        }

        Log.d(TAG, sofType + ": " + width + "x" + height + 
                   ", " + precision + "-bit, " + components + " components" +
                   (isLosslessFormat ? " [LOSSLESS]" : " [DCT]"));

        for (int i = 0; i < components; i++) {
            int componentId = readUInt8();
            int samplingFactors = readUInt8();
            int quantTable = readUInt8();
            Log.d(TAG, "  Component " + componentId + ": sampling=" + 
                       ((samplingFactors >> 4) & 0xF) + "x" + (samplingFactors & 0xF) +
                       ", quantTable=" + quantTable);
        }
    }

    private void parseDHT(int length) {
        int endPos = dataPos + length;

        while (dataPos < endPos) {
            int info = readUInt8();
            int tableClass = (info >> 4) & 0xF;  // 0 = DC, 1 = AC (not used for lossless)
            int tableId = info & 0xF;

            // Read bit counts
            int[] bits = new int[17];
            int totalCodes = 0;
            for (int i = 1; i <= 16; i++) {
                bits[i] = readUInt8();
                totalCodes += bits[i];
            }

            // Read values
            int[] values = new int[totalCodes];
            for (int i = 0; i < totalCodes; i++) {
                values[i] = readUInt8();
            }

            // Build Huffman table
            buildHuffmanTable(tableId, bits, values);
        }
    }

    private void buildHuffmanTable(int tableId, int[] bits, int[] values) {
        int code = 0;
        int valueIdx = 0;

        for (int length = 1; length <= 16; length++) {
            huffmanMinCode[tableId][length] = code;
            huffmanValPtr[tableId][length] = valueIdx;

            for (int i = 0; i < bits[length]; i++) {
                if (valueIdx < values.length) {
                    huffmanValues[tableId][valueIdx] = values[valueIdx];
                }
                valueIdx++;
                code++;
            }

            huffmanMaxCode[tableId][length] = code - 1;
            code <<= 1;
        }

        huffmanBits[tableId] = 1; // Mark table as valid
    }

    private int scanComponents = 0;  // Number of components in current scan
    private int pointTransform = 0;  // Point transform (bit shift)
    
    private void parseSOS() {
        int length = readUInt16();
        scanComponents = readUInt8();
        if (scanComponents == 0) scanComponents = components;  // Fallback

        Log.d(TAG, "SOS: " + scanComponents + " component(s) in scan" +
                   (scanComponents < components ? " [NON-INTERLEAVED]" : " [INTERLEAVED]"));

        for (int i = 0; i < scanComponents; i++) {
            int componentId = readUInt8();
            int huffTables = readUInt8();
            int dcTable = (huffTables >> 4) & 0xF;
            int acTable = huffTables & 0xF;  // Not used for lossless
            componentHuffTable[i] = dcTable;
            Log.d(TAG, "  Scan component " + componentId + ": dcTable=" + dcTable);
        }

        predictor = readUInt8();  // Predictor selection (Ss)
        int endSpectral = readUInt8();  // Se (not used for lossless, should be 0)
        int approxBits = readUInt8();  // Ah (upper) and Al (lower) - point transform
        pointTransform = approxBits & 0xF;  // Al = point transform

        Log.d(TAG, "SOS: predictor=" + predictor + ", pointTransform=" + pointTransform);
    }

    private short[] decodeImageData() {
        short[] pixels = new short[width * height * components];
        
        // Reset bit reading state
        bitReadingFinished = false;
        restartMarkerPending = false;
        
        // For non-interleaved scans, we only decode scanComponents per pixel
        // For interleaved scans, scanComponents == components
        int componentsPerPixel = scanComponents;
        
        // Validate precision
        if (precision < 1 || precision > 16) {
            Log.e(TAG, "Invalid precision: " + precision + ", defaulting to 16");
            precision = 16;
        }
        
        // Mask for the precision (e.g., 0xFFFF for 16-bit)
        final int precisionMask = (1 << precision) - 1;
        
        // Two-row buffer system like dcraw uses:
        // - currRow: the row we're currently writing to
        // - prevRowBuf: the previous row for reading Rb and Rc
        // We alternate between two buffers to avoid copying
        int[][][] rowBuffers = new int[2][componentsPerPixel][width];
        int currRowIdx = 0;
        
        // vpred tracks the cumulative prediction for the first column (like dcraw)
        int[] vpred = new int[componentsPerPixel];
        for (int c = 0; c < componentsPerPixel; c++) {
            vpred[c] = 1 << (precision - 1);  // Initialize to half the max value
        }
        
        Log.d(TAG, "Decoding: " + width + "x" + height + " pixels, " + 
                   componentsPerPixel + " components per pixel, pointTransform=" + pointTransform +
                   ", precision=" + precision + ", predictor=" + predictor);

        int restartCount = 0;
        int pixelIndex = 0;
        boolean justRestarted = false;  // Track if we just processed a restart
        
        for (int y = 0; y < height; y++) {
            // Get current and previous row buffers (alternating)
            int[][] currRow = rowBuffers[currRowIdx];
            int[][] prevRowBuf = rowBuffers[1 - currRowIdx];
            
            for (int x = 0; x < width; x++) {
                // Check for restart marker - either by count OR by detecting it in the bitstream
                // The bitstream-based detection (restartMarkerPending) is more reliable when
                // the restart interval doesn't align with image width
                boolean needRestart = restartMarkerPending || 
                                     (restartInterval > 0 && restartCount == restartInterval);
                if (needRestart) {
                    alignToByte();
                    skipRestartMarker();
                    restartCount = 0;
                    // Reset bit reading state after restart marker
                    bitReadingFinished = false;
                    restartMarkerPending = false;
                    justRestarted = true;  // Next pixel uses neutral prediction
                    // Reset vpred (like dcraw: FORC(6) jh->vpred[c] = 1 << (jh->bits - 1))
                    for (int c = 0; c < componentsPerPixel; c++) {
                        vpred[c] = 1 << (precision - 1);
                    }
                }

                for (int c = 0; c < componentsPerPixel; c++) {
                    int tableId = componentHuffTable[c];
                    
                    // Decode Huffman code to get number of additional bits (like dcraw ljpeg_diff)
                    int ssss = decodeHuffman(tableId);
                    
                    // Read additional bits and convert to signed difference value
                    // This follows dcraw's ljpeg_diff() implementation exactly
                    int diff;
                    if (ssss == 0) {
                        diff = 0;
                    } else if (ssss == 16) {
                        // Special case: ssss=16 means -32768 (max negative for 16-bit)
                        // dcraw: if (len == 16 && (!dng_version || dng_version >= 0x1010000)) return -32768;
                        // Don't read additional bits - just return -32768
                        diff = -32768;
                    } else {
                        diff = readBits(ssss);
                        // Convert to signed: if MSB is 0, value is negative
                        // dcraw: if ((diff & (1 << (len-1))) == 0) diff -= (1 << len) - 1;
                        if ((diff & (1 << (ssss - 1))) == 0) {
                            diff -= (1 << ssss) - 1;
                        }
                    }

                    // Calculate prediction following dcraw's approach
                    int pred;
                    if (justRestarted && c == 0) {
                        // First sample after restart: use neutral prediction
                        // This is like the first pixel of the image
                        pred = 1 << (precision - 1);
                    } else if (x == 0 && y == 0) {
                        // First pixel: use half the max value
                        pred = 1 << (precision - 1);
                    } else if (justRestarted) {
                        // Subsequent components of first pixel after restart
                        // Use vpred which was just reset
                        pred = vpred[c];
                    } else if (x == 0) {
                        // First column: use vpred (cumulative from previous rows)
                        // dcraw does: pred = (jh->vpred[c] += diff) - diff
                        // which effectively uses the old vpred value
                        pred = vpred[c];
                    } else if (y == 0) {
                        // First row: use left pixel only
                        pred = currRow[c][x - 1];
                    } else {
                        // Interior pixels: apply predictor
                        int Ra = currRow[c][x - 1];      // Left (from current row, already written)
                        int Rb = prevRowBuf[c][x];       // Above (from previous row buffer)
                        int Rc = prevRowBuf[c][x - 1];   // Upper-left (from previous row buffer)
                        
                        switch (predictor) {
                            case 0: pred = 0; break;
                            case 1: pred = Ra; break;
                            case 2: pred = Rb; break;
                            case 3: pred = Rc; break;
                            case 4: pred = Ra + Rb - Rc; break;
                            case 5: pred = Ra + ((Rb - Rc) >> 1); break;
                            case 6: pred = Rb + ((Ra - Rc) >> 1); break;
                            case 7: pred = (Ra + Rb) >> 1; break;
                            default: pred = Ra; break;
                        }
                    }
                    
                    // Add difference to prediction
                    int rawValue = pred + diff;
                    
                    // Apply modular arithmetic to keep value in valid range
                    rawValue = rawValue & precisionMask;
                    
                    // Store value in current row buffer
                    currRow[c][x] = rawValue;
                    
                    // Update vpred for first column tracking
                    if (x == 0) {
                        vpred[c] = rawValue;
                    }
                    
                    // Apply point transform (left shift) for output
                    int value = rawValue << pointTransform;
                    
                    // Clamp to valid range
                    if (value > precisionMask) value = precisionMask;

                    pixels[pixelIndex++] = (short) value;
                }
                
                restartCount++;
                justRestarted = false;  // Clear after first pixel post-restart
            }
            
            // Swap row buffers for next row
            currRowIdx = 1 - currRowIdx;
        }
        
        Log.d(TAG, "Decoded " + pixelIndex + " values (expected " + (width * height * componentsPerPixel) + ")");

        return pixels;
    }

    private int decodeHuffman(int tableId) {
        int code = 0;

        for (int length = 1; length <= 16; length++) {
            code = (code << 1) | readBit();

            if (code <= huffmanMaxCode[tableId][length]) {
                int index = huffmanValPtr[tableId][length] + code - huffmanMinCode[tableId][length];
                return huffmanValues[tableId][index];
            }
        }

        Log.e(TAG, "Invalid Huffman code");
        return 0;
    }

    private int readBit() {
        if (bitsInBuffer == 0) {
            fillBitBuffer();
        }
        bitsInBuffer--;
        return (bitBuffer >> bitsInBuffer) & 1;
    }

    private int readBits(int count) {
        int value = 0;
        for (int i = 0; i < count; i++) {
            value = (value << 1) | readBit();
        }
        return value;
    }

    // Flag to track if we've hit a marker (end of data segment)
    private boolean bitReadingFinished = false;
    // Flag to track if we hit a restart marker and need to handle it
    private boolean restartMarkerPending = false;
    
    private void fillBitBuffer() {
        if (dataPos >= data.length || bitReadingFinished) {
            // Return zeros when no more data
            bitBuffer = 0;
            bitsInBuffer = 8;
            return;
        }

        int nextByte = data[dataPos++] & 0xFF;
        
        // Handle byte stuffing like LibRaw/dcraw
        if (nextByte != 0xFF) {
            // Normal byte - just use it
            bitBuffer = nextByte;
            bitsInBuffer = 8;
        } else if (dataPos < data.length) {
            int following = data[dataPos] & 0xFF;
            if (following == 0x00) {
                // Byte stuffing: 0xFF 0x00 -> 0xFF
                dataPos++;  // Skip the 0x00
                bitBuffer = 0xFF;
                bitsInBuffer = 8;
            } else if (following >= 0xD0 && following <= 0xD7) {
                // Restart marker found in bitstream!
                // DON'T add 0xFF to buffer - it's part of the marker, not data
                // Set flag so decoder knows to handle restart after current symbol
                restartMarkerPending = true;
                // Return zeros to complete any pending symbol (JPEG pads with 1s, but
                // we're at a symbol boundary so this shouldn't matter much)
                bitBuffer = 0;
                bitsInBuffer = 8;
                // Don't consume the marker bytes yet - skipRestartMarker will do that
                dataPos--;  // Back up so we're pointing at 0xFF again
            } else {
                // Other marker (like EOI) - stop reading, set finished flag
                // This matches LibRaw's behavior where it sets finished = true
                bitReadingFinished = true;
                bitBuffer = 0;
                bitsInBuffer = 8;
            }
        } else {
            // 0xFF at end of data
            bitBuffer = 0xFF;
            bitsInBuffer = 8;
        }
    }

    private void alignToByte() {
        bitsInBuffer = 0;
    }

    private void skipRestartMarker() {
        // First, check if we're already pointing at a restart marker
        // (this happens when restartMarkerPending was set in fillBitBuffer)
        if (dataPos < data.length - 1 && 
            (data[dataPos] & 0xFF) == 0xFF) {
            int marker = data[dataPos + 1] & 0xFF;
            if (marker >= 0xD0 && marker <= 0xD7) {
                dataPos += 2;  // Skip 0xFF and marker byte
                bitsInBuffer = 0;
                return;
            }
        }
        
        // Search nearby (check a few bytes back in case of alignment issues)
        int startPos = Math.max(0, dataPos - 2);
        for (int pos = startPos; pos < data.length - 1 && pos < dataPos + 8; pos++) {
            if ((data[pos] & 0xFF) == 0xFF) {
                int marker = data[pos + 1] & 0xFF;
                if (marker >= 0xD0 && marker <= 0xD7) {
                    dataPos = pos + 2;  // Position after the marker
                    bitsInBuffer = 0;   // Clear bit buffer
                    return;
                }
            }
        }
        
        // If not found nearby, scan forward (fallback)
        while (dataPos < data.length - 1) {
            if ((data[dataPos] & 0xFF) == 0xFF) {
                int marker = data[dataPos + 1] & 0xFF;
                if (marker >= 0xD0 && marker <= 0xD7) {
                    dataPos += 2;
                    bitsInBuffer = 0;
                    return;
                }
            }
            dataPos++;
        }
        
        Log.w(TAG, "Restart marker not found at expected position, dataPos=" + dataPos);
    }

    /**
     * Check if the given JPEG data is lossless JPEG (SOF3) or high-bit-depth.
     */
    public static boolean isLosslessOrHighBitDepth(byte[] jpegData) {
        if (jpegData == null || jpegData.length < 20) {
            return false;
        }

        int pos = 0;
        
        // Check SOI
        if ((jpegData[pos] & 0xFF) != 0xFF || (jpegData[pos + 1] & 0xFF) != 0xD8) {
            return false;
        }
        pos += 2;

        // Scan for SOF marker
        while (pos < jpegData.length - 4) {
            if ((jpegData[pos] & 0xFF) == 0xFF) {
                int marker = jpegData[pos + 1] & 0xFF;
                
                // SOF3 (lossless) or SOF11 (lossless arithmetic)
                if (marker == 0xC3 || marker == 0xCB) {
                    return true;
                }
                
                // SOF0, SOF1, SOF2 - check precision
                if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                    // Length at pos+2, precision at pos+4
                    if (pos + 4 < jpegData.length) {
                        int precision = jpegData[pos + 4] & 0xFF;
                        return precision > 8;
                    }
                }
                
                // Skip marker segment
                if (marker >= 0xC0 && marker <= 0xFE && marker != 0xD8 && marker != 0xD9) {
                    if (pos + 3 < jpegData.length) {
                        int length = ((jpegData[pos + 2] & 0xFF) << 8) | (jpegData[pos + 3] & 0xFF);
                        pos += 2 + length;
                        continue;
                    }
                }
            }
            pos++;
        }

        return false;
    }

    /**
     * Get JPEG dimensions from SOF header.
     * @return [width, height] or null if not found
     */
    public static int[] getJpegDimensions(byte[] jpegData) {
        if (jpegData == null || jpegData.length < 20) {
            return null;
        }

        int pos = 0;
        
        // Check SOI
        if ((jpegData[pos] & 0xFF) != 0xFF || (jpegData[pos + 1] & 0xFF) != 0xD8) {
            return null;
        }
        pos += 2;

        // Scan for SOF marker
        while (pos < jpegData.length - 9) {
            if ((jpegData[pos] & 0xFF) == 0xFF) {
                int marker = jpegData[pos + 1] & 0xFF;
                
                // Check SOF markers
                if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    int height = ((jpegData[pos + 5] & 0xFF) << 8) | (jpegData[pos + 6] & 0xFF);
                    int width = ((jpegData[pos + 7] & 0xFF) << 8) | (jpegData[pos + 8] & 0xFF);
                    return new int[] { width, height };
                }
                
                // Skip to next marker
                if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD9)) {
                    pos += 2;
                } else if (pos + 3 < jpegData.length) {
                    int length = ((jpegData[pos + 2] & 0xFF) << 8) | (jpegData[pos + 3] & 0xFF);
                    pos += 2 + length;
                } else {
                    break;
                }
            } else {
                pos++;
            }
        }

        return null;
    }

    /**
     * Get info about a JPEG's format for logging purposes.
     */
    public static String getJpegInfo(byte[] jpegData) {
        if (jpegData == null || jpegData.length < 20) {
            return "Invalid or too short";
        }

        int pos = 0;
        
        // Check SOI
        if ((jpegData[pos] & 0xFF) != 0xFF || (jpegData[pos + 1] & 0xFF) != 0xD8) {
            return "Not JPEG (missing SOI)";
        }
        pos += 2;

        // Scan for SOF marker
        while (pos < jpegData.length - 4) {
            if ((jpegData[pos] & 0xFF) == 0xFF) {
                int marker = jpegData[pos + 1] & 0xFF;
                
                // Check SOF markers
                if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    if (pos + 7 < jpegData.length) {
                        int precision = jpegData[pos + 4] & 0xFF;
                        int height = ((jpegData[pos + 5] & 0xFF) << 8) | (jpegData[pos + 6] & 0xFF);
                        int width = ((jpegData[pos + 7] & 0xFF) << 8) | (jpegData[pos + 8] & 0xFF);
                        int components = jpegData[pos + 9] & 0xFF;
                        
                        String sofType;
                        switch (marker) {
                            case 0xC0: sofType = "Baseline DCT"; break;
                            case 0xC1: sofType = "Extended sequential DCT"; break;
                            case 0xC2: sofType = "Progressive DCT"; break;
                            case 0xC3: sofType = "Lossless (sequential)"; break;
                            case 0xC9: sofType = "Extended sequential DCT (arithmetic)"; break;
                            case 0xCA: sofType = "Progressive DCT (arithmetic)"; break;
                            case 0xCB: sofType = "Lossless (arithmetic)"; break;
                            default: sofType = "SOF" + (marker - 0xC0);
                        }
                        
                        return String.format("%s, %d-bit, %dx%d, %d components",
                                sofType, precision, width, height, components);
                    }
                }
                
                // Skip marker segment
                if (marker >= 0xC0 && marker <= 0xFE && marker != 0xD8 && marker != 0xD9) {
                    if (pos + 3 < jpegData.length) {
                        int length = ((jpegData[pos + 2] & 0xFF) << 8) | (jpegData[pos + 3] & 0xFF);
                        pos += 2 + length;
                        continue;
                    }
                }
            }
            pos++;
        }

        return "Unknown JPEG format";
    }
}

