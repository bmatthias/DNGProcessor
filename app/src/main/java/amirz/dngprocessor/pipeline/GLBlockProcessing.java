package amirz.dngprocessor.pipeline;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.math.BlockDivider;

import static amirz.dngprocessor.util.Constants.BLOCK_HEIGHT;
import static android.opengl.GLES20.*;

import amirz.dngprocessor.util.FastMath;

public class GLBlockProcessing {
    private final Bitmap mOut;
    private final int mOutWidth, mOutHeight;
    private final IntBuffer mBlockBuffer;
    private final IntBuffer mOutBuffer;

    public GLBlockProcessing(Bitmap out) {
        mOut = out;
        mOutWidth = out.getWidth();
        mOutHeight = out.getHeight();

        mBlockBuffer = IntBuffer.allocate(mOutWidth * BLOCK_HEIGHT);
        mOutBuffer = IntBuffer.allocate(mOutWidth * mOutHeight);
    }
    
    public int getOutWidth() {
        return mOutWidth;
    }
    
    public int getOutHeight() {
        return mOutHeight;
    }

    public void drawBlocksToOutput(GLPrograms gl) {
        BlockDivider divider = new BlockDivider(mOutHeight, BLOCK_HEIGHT);
        int[] row = new int[2];
        while (divider.nextBlock(row)) {
            int y = row[0];
            int height = row[1];

            glViewport(0, 0, mOutWidth, height);
            gl.seti("yOffset", y);
            gl.draw();

            mBlockBuffer.position(0);
            glReadPixels(0, 0, mOutWidth, height, GL_RGBA, GL_UNSIGNED_BYTE, mBlockBuffer);
            if (height < BLOCK_HEIGHT) {
                // This can only happen once
                int[] data = new int[mOutWidth * height];
                mBlockBuffer.get(data);
                mOutBuffer.put(data);
            } else {
                mOutBuffer.put(mBlockBuffer);
            }
        }

        mOutBuffer.position(0);
        mOut.copyPixelsFromBuffer(mOutBuffer);
    }

    /**
     * Capture HDR version (float values, may exceed 1.0) and convert to bitmap.
     * Values are stored in a normalized format where 1.0 = SDR white point.
     * Values > 1.0 represent HDR highlights.
     * 
     * @param gl GLPrograms instance
     * @return Bitmap with HDR values (gamma-encoded sRGB, may exceed 1.0, stored as 0-255 with scaling)
     */
    public Bitmap captureHdrOutput(GLPrograms gl) {
        android.util.Log.d("GLBlockProcessing", "Starting HDR capture: " + mOutWidth + "x" + mOutHeight);
        
        Bitmap hdrBitmap = Bitmap.createBitmap(mOutWidth, mOutHeight, Bitmap.Config.ARGB_8888);
        BlockDivider divider = new BlockDivider(mOutHeight, BLOCK_HEIGHT);
        int[] row = new int[2];
        
        // Read as float to preserve values > 1.0
        FloatBuffer floatBuffer = ByteBuffer.allocateDirect(mOutWidth * BLOCK_HEIGHT * 4 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        
        int[] pixels = new int[mOutWidth * mOutHeight];
        int pixelIndex = 0;
        
        // Check current framebuffer binding
        int[] currentFbo = new int[1];
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, currentFbo, 0);
        android.util.Log.d("GLBlockProcessing", "Current framebuffer: " + currentFbo[0]);
        
        while (divider.nextBlock(row)) {
            int y = row[0];
            int height = row[1];

            glViewport(0, 0, mOutWidth, height);
            gl.seti("yOffset", y);
            gl.draw();
            
            // Check for GL errors after draw
            int glError = glGetError();
            if (glError != GL_NO_ERROR) {
                android.util.Log.w("GLBlockProcessing", "GL error after draw: " + glError);
            }

            floatBuffer.position(0);
            glReadPixels(0, 0, mOutWidth, height, GL_RGBA, GL_FLOAT, floatBuffer);
            
            // Check for GL errors after read
            glError = glGetError();
            if (glError != GL_NO_ERROR) {
                android.util.Log.e("GLBlockProcessing", "GL error after glReadPixels: " + glError + 
                      " (block y=" + y + ", height=" + height + ")");
            }
            
            floatBuffer.rewind();
            for (int i = 0; i < mOutWidth * height; i++) {
                float r = floatBuffer.get(i * 4 + 0);
                float g = floatBuffer.get(i * 4 + 1);
                float b = floatBuffer.get(i * 4 + 2);
                
                // HDR values may exceed 1.0 - we need to preserve this information
                // Since bitmap only supports 0-255, we use a scaling approach:
                // Store gamma-encoded values where 255 = 1.0 (SDR white point)
                // Values > 1.0 are clamped to 255 for storage, but we'll handle this
                // in the gain map generator by comparing HDR vs SDR versions
                // 
                // Note: The HDR version is gamma-encoded sRGB (same as SDR)
                // The gain map generator will convert both to linear and compute the ratio
                
                // Clamp to [0, 1] for bitmap storage (values > 1.0 will be lost, but that's OK
                // because we'll compute gain from the ratio of HDR/SDR, and if HDR > 1.0 and SDR = 1.0,
                // the gain will be > 1.0, which is what we want)
                // Actually, we need to preserve > 1.0. Let's use a scale factor:
                // Store with scale factor 4.0: value * 4.0, so 1.0 → 255, 4.0 → 1020 (clamped to 255)
                // Then decode: value = stored / 4.0
                // But this loses precision. Better: use logarithmic encoding like Ultra HDR spec
                
                // Use fast logarithmic encoding to preserve HDR range: log2(1 + value) / log2(17)
                // This maps [0, 16] → [0, 1], matching Ultra HDR gain map encoding
                float maxHdrValue = 16.0f; // Maximum HDR value we can encode
                
                float rEncoded = FastMath.fastLog2Encode(Math.min(r, maxHdrValue));
                float gEncoded = FastMath.fastLog2Encode(Math.min(g, maxHdrValue));
                float bEncoded = FastMath.fastLog2Encode(Math.min(b, maxHdrValue));
                
                int rInt = Math.max(0, Math.min(255, (int)(rEncoded * 255.0f)));
                int gInt = Math.max(0, Math.min(255, (int)(gEncoded * 255.0f)));
                int bInt = Math.max(0, Math.min(255, (int)(bEncoded * 255.0f)));
                
                pixels[pixelIndex++] = Color.rgb(rInt, gInt, bInt);
            }
        }
        
        hdrBitmap.setPixels(pixels, 0, mOutWidth, 0, 0, mOutWidth, mOutHeight);
        return hdrBitmap;
    }
}
