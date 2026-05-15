package amirz.dngprocessor.gl;

import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.util.Log;
import android.util.Pair;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static android.opengl.EGL14.*;

/**
 * OpenGL ES context for DNG processing.
 * 
 * This class manages the EGL display, context, and surface for GPU-accelerated
 * image processing. Each instance creates its own EGL context, making it safe
 * to use from any thread (as long as each thread has its own instance).
 * 
 * Usage:
 *   try (GLContext glContext = new GLContext()) {
 *       glContext.setDimens(width, height);
 *       // ... use GL for rendering ...
 *   } // automatically cleaned up
 */
public class GLContext implements AutoCloseable {
    private static final String TAG = "GLContext";

    private final EGLDisplay mDisplay;
    private final EGLConfig mConfig;
    private final Map<Class<?>, GLResource> mComponents = new HashMap<>();
    private final long mThreadId;
    
    private EGLContext mContext;
    private EGLSurface mSurface;
    private Pair<Integer, Integer> mDimens;
    private boolean mClosed = false;

    /**
     * Create a new GL context on the current thread.
     */
    public GLContext() {
        mThreadId = Thread.currentThread().getId();
        Log.d(TAG, "Creating GLContext on thread " + mThreadId);
        
        mDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (mDisplay == EGL_NO_DISPLAY) {
            throw new RuntimeException("Failed to get EGL display");
        }

        int[] major = new int[2];
        int[] minor = new int[2];
        if (!eglInitialize(mDisplay, major, 0, minor, 0)) {
            throw new RuntimeException("Failed to initialize EGL");
        }

        int[] attribList = {
                EGL_DEPTH_SIZE, 0,
                EGL_STENCIL_SIZE, 0,
                EGL_RED_SIZE, 8,
                EGL_GREEN_SIZE, 8,
                EGL_BLUE_SIZE, 8,
                EGL_ALPHA_SIZE, 8,
                EGL_BIND_TO_TEXTURE_RGBA, EGL_TRUE,
                EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL_NONE
        };

        int[] numConfig = new int[1];
        if (!eglChooseConfig(mDisplay, attribList, 0, null, 0, 0, numConfig, 0)
                || numConfig[0] == 0) {
            throw new RuntimeException("No compatible EGL config found");
        }

        int configSize = numConfig[0];
        EGLConfig[] configs = new EGLConfig[configSize];
        if (!eglChooseConfig(mDisplay, attribList, 0, configs, 0, configSize, numConfig, 0)) {
            throw new RuntimeException("Failed to get EGL configs");
        }

        mConfig = configs[0];
        if (mConfig == null) {
            throw new RuntimeException("EGL config is null");
        }
    }

    /**
     * Set the rendering surface dimensions and make the context current.
     * Must be called before any GL operations.
     */
    public void setDimens(int width, int height) {
        checkNotClosed();
        checkThread();
        
        // Check if dimensions already match
        if (mDimens != null && mDimens.first == width && mDimens.second == height
                && mContext != null && mSurface != null) {
            // Just ensure context is current
            if (eglMakeCurrent(mDisplay, mSurface, mSurface, mContext)) {
                return;
            }
            // Context became invalid, recreate
            Log.w(TAG, "Context became invalid, recreating...");
            destroyContextAndSurface();
        }
        
        // Destroy old resources if dimensions changed
        if (mContext != null || mSurface != null) {
            destroyContextAndSurface();
        }

        mDimens = new Pair<>(width, height);
        
        // Create new surface
        Log.d(TAG, "Creating Pbuffer Surface " + width + "x" + height);
        mSurface = eglCreatePbufferSurface(mDisplay, mConfig, new int[] {
                EGL_WIDTH, width,
                EGL_HEIGHT, height,
                EGL_NONE
        }, 0);
        
        if (mSurface == EGL_NO_SURFACE) {
            throw new RuntimeException("Failed to create PBuffer surface, error: " + eglGetError());
        }

        // Create context
        mContext = eglCreateContext(mDisplay, mConfig, EGL_NO_CONTEXT, new int[] {
                EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL_NONE
        }, 0);
        
        if (mContext == EGL_NO_CONTEXT) {
            eglDestroySurface(mDisplay, mSurface);
            mSurface = null;
            throw new RuntimeException("Failed to create EGL context, error: " + eglGetError());
        }

        // Make current
        if (!eglMakeCurrent(mDisplay, mSurface, mSurface, mContext)) {
            destroyContextAndSurface();
            throw new RuntimeException("Failed to make context current, error: " + eglGetError());
        }
        
        Log.d(TAG, "GL context ready: " + width + "x" + height);
    }

    /**
     * Get a cached component (shader program cache, texture pool, etc.)
     */
    @SuppressWarnings("unchecked")
    public <T extends GLResource> T getComponent(Class<T> cls, Supplier<T> constructor) {
        checkNotClosed();
        return (T) mComponents.computeIfAbsent(cls, x -> constructor.get());
    }

    private void destroyContextAndSurface() {
        // Release cached components first
        for (GLResource resource : mComponents.values()) {
            try {
                resource.release();
            } catch (Exception e) {
                Log.w(TAG, "Failed to release component: " + e.getMessage());
            }
        }
        mComponents.clear();
        
        // Unbind context
        eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        
        // Destroy context
        if (mContext != null && mContext != EGL_NO_CONTEXT) {
            eglDestroyContext(mDisplay, mContext);
            mContext = null;
        }
        
        // Destroy surface
        if (mSurface != null && mSurface != EGL_NO_SURFACE) {
            eglDestroySurface(mDisplay, mSurface);
            mSurface = null;
        }
        
        mDimens = null;
    }

    private void checkNotClosed() {
        if (mClosed) {
            throw new IllegalStateException("GLContext has been closed");
        }
    }
    
    private void checkThread() {
        long currentThread = Thread.currentThread().getId();
        if (currentThread != mThreadId) {
            throw new IllegalStateException(
                    "GLContext created on thread " + mThreadId + 
                    " but used on thread " + currentThread);
        }
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        
        Log.d(TAG, "Closing GLContext on thread " + Thread.currentThread().getId());
        
        // Only destroy if we're on the right thread
        if (Thread.currentThread().getId() == mThreadId) {
            destroyContextAndSurface();
            // Balance eglInitialize with eglTerminate - this decrements the reference count
            // and allows EGL to clean up when no longer in use
            eglTerminate(mDisplay);
        } else {
            Log.w(TAG, "GLContext closed from different thread - resources may leak");
        }
    }
}

