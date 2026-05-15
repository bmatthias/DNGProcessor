package amirz.dngprocessor.gl;

import android.util.Log;

import java.util.HashSet;
import java.util.Set;

public class TexturePool extends GLResource {
    private static final String TAG = "TexturePool";
    
    // Thread-local for current context's texture pool (set during pipeline execution)
    private static final ThreadLocal<TexturePool> sCurrentPool = new ThreadLocal<>();

    /**
     * Get TexturePool instance for the given context.
     * Each context has its own pool.
     */
    public static TexturePool getInstance(GLContext glContext) {
        return glContext.getComponent(TexturePool.class, TexturePool::new);
    }
    
    /**
     * Set the current thread's texture pool. Called at start of pipeline execution.
     */
    public static void setCurrent(TexturePool pool) {
        sCurrentPool.set(pool);
    }
    
    /**
     * Clear the current thread's texture pool. Called at end of pipeline execution.
     */
    public static void clearCurrent() {
        sCurrentPool.remove();
    }

    /**
     * Get a texture from the current thread's pool.
     */
    public static Texture get(int width, int height, int channels, Texture.Format format) {
        TexturePool pool = sCurrentPool.get();
        if (pool == null) {
            throw new IllegalStateException("No TexturePool set for current thread");
        }
        return pool.getTex(width, height, channels, format);
    }

    public static Texture get(Texture texture) {
        return get(texture.getWidth(), texture.getHeight(), texture.getChannels(),
                texture.getFormat());
    }

    private final Set<Texture> mPool = new HashSet<>();
    private final Set<Texture> mGrants = new HashSet<>();

    private Texture getTex(int width, int height, int channels, Texture.Format format) {
        Texture texture = null;
        for (Texture tex : mPool) {
            if (tex.getWidth() == width && tex.getHeight() == height
                    && tex.getChannels() == channels && tex.getFormat() == format) {
                mPool.remove(tex);
                texture = tex;
                break;
            }
        }

        if (texture == null) {
            texture = new Texture(width, height, channels, format, null);
            Log.d(TAG, "Created " + texture + ": " + width + "x" + height + " (" + channels + " ch)");
        }

        Texture tex = texture;
        mGrants.add(tex);
        tex.setCloseOverride(() -> {
            mGrants.remove(tex);
            mPool.add(tex);
            tex.setCloseOverride(() -> {
                throw new RuntimeException("Attempting to close " + tex + " twice");
            });
        });

        return tex;
    }

    @Override
    public void release() {
        for (Texture texture : mPool) {
            texture.setCloseOverride(null);
            texture.close();
        }
        mPool.clear();
    }

    /**
     * Log any textures that weren't returned to the pool.
     */
    public void logLeaks() {
        for (Texture tex : mGrants) {
            Log.d(TAG, "Leaked texture: " + tex);
        }
    }
}
