package amirz.dngprocessor.gl;

import java.nio.Buffer;
import java.nio.ByteBuffer;

import static android.opengl.GLES20.*;
import static android.opengl.GLES30.*;
import static javax.microedition.khronos.opengles.GL10.GL_TEXTURE_2D;
import static javax.microedition.khronos.opengles.GL10.GL_TEXTURE_MAG_FILTER;
import static javax.microedition.khronos.opengles.GL10.GL_TEXTURE_MIN_FILTER;

public class Texture implements AutoCloseable {
    public enum Format {
        Float16,
        UInt16
    }

    public static class Config {
        public int w, h;
        public Buffer pixels;

        public int channels = 1;
        public Format format = Format.Float16;
        public int texFilter = GL_NEAREST;
        public int texWrap = GL_CLAMP_TO_EDGE;
    }

    private final int mWidth, mHeight;
    private final int mChannels;
    private final Format mFormat;
    private final int mTexId;
    private int mFrameBufferId = -1;  // Cached framebuffer (created on first use)
    private ByteBuffer mBuffer;
    private Runnable mCloseOverride;

    public Texture(Config config) {
        this(config.w, config.h, config.channels, config.format, config.pixels, config.texFilter,
                config.texWrap);
    }

    @SuppressWarnings("CopyConstructorMissesField")
    public Texture(Texture texture) {
        this(texture.getWidth(), texture.getHeight(), texture.mChannels, texture.mFormat, null);
    }

    public Texture(int w, int h, int channels, Format format, Buffer pixels) {
        this(w, h, channels, format, pixels, GL_NEAREST);
    }

    public Texture(int w, int h, int channels, Format format, Buffer pixels, int texFilter) {
        this(w, h, channels, format, pixels, texFilter, GL_CLAMP_TO_EDGE);
    }

    public Texture(int w, int h, int channels, Format format, Buffer pixels, int texFilter, int texWrap) {
        mWidth = w;
        mHeight = h;
        mChannels = channels;
        mFormat = format;

        int[] texId = new int[1];
        glGenTextures(texId.length, texId, 0);
        mTexId = texId[0];

        // Use a high ID to load buffer.
        glActiveTexture(GL_TEXTURE16);
        glBindTexture(GL_TEXTURE_2D, mTexId);
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat(), w, h, 0, format(), type(), pixels);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, texFilter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, texFilter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, texWrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, texWrap);
    }

    public void setCloseOverride(Runnable closeOverride) {
        mCloseOverride = closeOverride;
    }

    public void setPixels(byte[] bytes) {
        // For large textures, upload in chunks to avoid allocating huge DirectByteBuffers
        // This prevents OutOfMemoryError when processing multiple files
        final int CHUNK_SIZE = 10 * 1024 * 1024; // 10MB chunks
        
        if (bytes.length <= CHUNK_SIZE) {
            // Small enough to upload in one go
            if (mBuffer == null || mBuffer.capacity() < bytes.length) {
                mBuffer = ByteBuffer.allocateDirect(bytes.length);
            } else {
                mBuffer.clear();
            }
            mBuffer.put(bytes);
            mBuffer.flip();
            setPixels(mBuffer);
        } else {
            // Large texture - upload in chunks to avoid OOM
            int bytesPerPixel = getBytesPerPixel();
            int rowStride = mWidth * bytesPerPixel;
            int rowsPerChunk = Math.max(1, CHUNK_SIZE / rowStride);
            int chunkHeight = rowsPerChunk;
            
            // Allocate reusable chunk buffer
            if (mBuffer == null || mBuffer.capacity() < CHUNK_SIZE) {
                mBuffer = ByteBuffer.allocateDirect(CHUNK_SIZE);
            }
            
            glActiveTexture(GL_TEXTURE16);
            glBindTexture(GL_TEXTURE_2D, mTexId);
            
            int offset = 0;
            int remainingHeight = mHeight;
            int y = 0;
            
            while (remainingHeight > 0) {
                int currentChunkHeight = Math.min(chunkHeight, remainingHeight);
                int chunkSize = currentChunkHeight * rowStride;
                
                // Clear and fill buffer with chunk data
                mBuffer.clear();
                mBuffer.put(bytes, offset, chunkSize);
                mBuffer.flip();
                
                // Upload this chunk
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, y, mWidth, currentChunkHeight, 
                               format(), type(), mBuffer);
                
                offset += chunkSize;
                y += currentChunkHeight;
                remainingHeight -= currentChunkHeight;
            }
        }
    }
    
    private int getBytesPerPixel() {
        switch (mFormat) {
            case UInt16:
                return mChannels * 2; // 2 bytes per channel
            case Float16:
                return mChannels * 2; // 16-bit float = 2 bytes
            default:
                return mChannels * 2;
        }
    }

    public void setPixels(Buffer buffer) {
        // Use a high ID to update buffer.
        glActiveTexture(GL_TEXTURE16);
        glBindTexture(GL_TEXTURE_2D, mTexId);
        // Note: glTexSubImage2D requires texture storage to already be allocated
        // (done in constructor via glTexImage2D)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, mWidth, mHeight, format(), type(), buffer);
    }

    public void setTexHandling(int texFilter, int texWrap) {
        // Use a high ID to update buffer.
        glActiveTexture(GL_TEXTURE16);
        glBindTexture(GL_TEXTURE_2D, mTexId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, texFilter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, texFilter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, texWrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, texWrap);
    }

    void bind(int slot) {
        glActiveTexture(slot);
        glBindTexture(GL_TEXTURE_2D, mTexId);
    }

    /*
    public void enableMipmaps() {
        glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
    }
    */

    public void setFrameBuffer() {
        // Create framebuffer on first use, then reuse
        if (mFrameBufferId == -1) {
            int[] frameBuffer = new int[1];
            glGenFramebuffers(1, frameBuffer, 0);
            mFrameBufferId = frameBuffer[0];
            glBindFramebuffer(GL_FRAMEBUFFER, mFrameBufferId);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mTexId, 0);
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, mFrameBufferId);
        }

        glViewport(0, 0, mWidth, mHeight);
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public int getType() {
        return type();
    }

    public Format getFormat() {
        return mFormat;
    }

    public int getFormatInt() {
        return format();
    }

    public int getChannels() {
        return mChannels;
    }

    @Override
    public void close() {
        if (mCloseOverride != null) {
            mCloseOverride.run();
            return;
        }
        // Delete framebuffer if we created one
        if (mFrameBufferId != -1) {
            glDeleteFramebuffers(1, new int[] { mFrameBufferId }, 0);
            mFrameBufferId = -1;
        }
        glDeleteTextures(1, new int[] { mTexId }, 0);
        // Clear DirectByteBuffer reference to help GC reclaim native memory
        // Note: DirectByteBuffers hold native memory that's freed when the buffer is GC'd
        // Explicitly clearing the reference helps the GC know it can be collected
        mBuffer = null;
    }

    private int internalFormat() {
        switch (mFormat) {
            case Float16:
                switch (mChannels) {
                    case 1: return GL_R16F;
                    case 2: return GL_RG16F;
                    case 3: return GL_RGB16F;
                    case 4: return GL_RGBA16F;
                }
            case UInt16:
                switch (mChannels) {
                    case 1: return GL_R16UI;
                    case 2: return GL_RG16UI;
                    case 3: return GL_RGB16UI;
                    case 4: return GL_RGBA16UI;
                }
        }
        return 0;
    }

    private int format() {
        switch (mFormat) {
            case Float16:
                switch (mChannels) {
                    case 1: return GL_RED;
                    case 2: return GL_RG;
                    case 3: return GL_RGB;
                    case 4: return GL_RGBA;
                }
            case UInt16:
                switch (mChannels) {
                    case 1: return GL_RED_INTEGER;
                    case 2: return GL_RG_INTEGER;
                    case 3: return GL_RGB_INTEGER;
                    case 4: return GL_RGBA_INTEGER;
                }
        }
        return 0;
    }

    private int type() {
        switch (mFormat) {
            case Float16: return GL_FLOAT;
            case UInt16: return GL_UNSIGNED_SHORT;
        }
        return 0;
    }
}
