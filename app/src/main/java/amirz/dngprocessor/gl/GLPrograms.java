package amirz.dngprocessor.gl;

import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import amirz.dngprocessor.R;
import amirz.dngprocessor.math.BlockDivider;
import amirz.dngprocessor.util.ShaderLoader;

import static amirz.dngprocessor.util.Constants.BLOCK_HEIGHT;
import static android.opengl.GLES20.*;
import static android.opengl.GLES30.*;

public class GLPrograms extends GLResource {
    private static final String TAG = "GLPrograms";
    private static final int NO_VERTEX_SHADER = -1;

    /**
     * Get GLPrograms instance for the given context.
     * Each context has its own cached instance.
     */
    public static GLPrograms getInstance(GLContext glContext, ShaderLoader shaderLoader) {
        return glContext.getComponent(GLPrograms.class,
                () -> new GLPrograms(shaderLoader));
    }

    private final ByteBuffer mFlushBuffer = ByteBuffer.allocateDirect(32);
    private final ShaderLoader mShaderLoader;
    private final SquareModel mSquare = new SquareModel();
    private final Map<Integer, Integer> mPrograms = new HashMap<>();
    private final Map<String, Integer> mTextureBinds = new HashMap<>();
    private int mVertexShader = NO_VERTEX_SHADER;
    private int mNewTextureId;
    private int mProgramActive;

    // Cached GPU viewport limits. -1 = not yet queried. Populated lazily on
    // first drawBlocks() so we don't query before a GL context is current.
    private int mMaxViewportW = -1;
    private int mMaxViewportH = -1;

    private GLPrograms(ShaderLoader shaderLoader) {
        mShaderLoader = shaderLoader;
    }

    public void useProgram(int fragmentRes) {
        if (mVertexShader == NO_VERTEX_SHADER) {
            mVertexShader = loadShader(GL_VERTEX_SHADER,
                    mShaderLoader.readRaw(R.raw.passthrough_vs));
        }
        int program = mPrograms.computeIfAbsent(fragmentRes, x -> createProgram(
                mVertexShader, mShaderLoader.readRaw(x)));

        glLinkProgram(program);
        
        // Check link status
        int[] linkStatus = new int[1];
        glGetProgramiv(program, GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            throw new RuntimeException("Program link error: " + log);
        }
        
        glUseProgram(program);
        mProgramActive = program;

        mTextureBinds.clear();
        mNewTextureId = 0;
    }

    private int createProgram(int vertex, String fragmentId) {
        int fragment;
        try {
            fragment = loadShader(GL_FRAGMENT_SHADER, fragmentId);
        } catch (RuntimeException e) {
            throw new RuntimeException("Error initializing fragment shader:\n" + fragmentId, e);
        }

        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        return program;
    }

    public void draw() {
        mSquare.draw(vPosition());
        glFlush();
    }

    public void drawBlocks(Texture tex) {
        drawBlocks(tex, true);
    }

    public void drawBlocks(Texture tex, boolean forceFlush) {
        tex.setFrameBuffer();
        drawBlocks(tex.getWidth(), tex.getHeight(), forceFlush ? tex.getFormatInt() : -1, tex.getType());
    }

    private void drawBlocks(int w, int h, int format, int type) {
        // For some reason, Android cannot read all formats.
        if (format == GL_RED || format == GL_RGB) {
            format = GL_RGBA;
        }

        // GPU viewport width is limited to GL_MAX_VIEWPORT_DIMS[0]. On many
        // mobile GPUs this is 4096 even when GL_MAX_TEXTURE_SIZE is 8192 or
        // 16384 — so an 8192-wide FBO can be ALLOCATED but glViewport with
        // w > MAX_VIEWPORT_DIMS[0] is silently clamped, leaving the right
        // half of every block unwritten. We tile in X here whenever the
        // requested width exceeds the viewport limit.
        if (mMaxViewportW < 0) {
            int[] dims = new int[2];
            glGetIntegerv(GL_MAX_VIEWPORT_DIMS, dims, 0);
            mMaxViewportW = dims[0];
            mMaxViewportH = dims[1];
            Log.i(TAG, "Cached GL_MAX_VIEWPORT_DIMS=[" + mMaxViewportW
                    + "," + mMaxViewportH + "]");
        }
        final int tileW = Math.min(w, mMaxViewportW);
        final int tileH = Math.min(BLOCK_HEIGHT, mMaxViewportH);
        final boolean wideTexture = w > mMaxViewportW;
        if (wideTexture) {
            Log.d(TAG, "drawBlocks X-tiling: w=" + w + " > MAX_VIEWPORT_DIMS[0]="
                    + mMaxViewportW + ", tiles=" + ((w + tileW - 1) / tileW));
        }

        BlockDivider divider = new BlockDivider(h, tileH);
        int[] row = new int[2];
        while (divider.nextBlock(row)) {
            for (int x = 0; x < w; x += tileW) {
                int bw = Math.min(tileW, w - x);
                glViewport(x, row[0], bw, row[1]);
                draw();

                if (format != -1) {
                    mFlushBuffer.position(0);
                    glReadPixels(x, row[0], 1, 1, format, type, mFlushBuffer);
                    int glError = glGetError();
                    if (glError != 0) {
                        Log.d("GLPrograms", "GLError: " + glError);
                        throw new RuntimeException("GLError " + glError);
                    }
                }
            }
        }
    }

    @Override
    public void release() {
        // Clean everything up
        for (int program : mPrograms.values()) {
            glDeleteProgram(program);
        }
        mPrograms.clear();
        mVertexShader = NO_VERTEX_SHADER;
    }

    protected static int loadShader(int type, String shaderCode) {
        int shader = glCreateShader(type);
        glShaderSource(shader, shaderCode);
        glCompileShader(shader);

        int[] status = new int[1];
        glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0);
        if (status[0] == GL_FALSE) {
            throw new RuntimeException("Shader compile error: " + glGetShaderInfoLog(shader));
        }

        return shader;
    }

    private int vPosition() {
        return glGetAttribLocation(mProgramActive, "vPosition");
    }

    @SuppressWarnings("ConstantConditions")
    public void setTexture(String var, Texture tex) {
        int textureId;
        if (mTextureBinds.containsKey(var)) {
            textureId = mTextureBinds.get(var);
        } else {
            textureId = mNewTextureId;
            mTextureBinds.put(var, textureId);
            mNewTextureId += 2;
        }

        seti(var, textureId);
        tex.bind(GL_TEXTURE0 + textureId);
    }

    public void seti(String var, int... vals) {
        int loc = loc(var);
        if (loc == -1) {
            Log.w(TAG, "Uniform not found: " + var + " (value: " + Arrays.toString(vals) + ")");
            return;
        }
        switch (vals.length) {
            case 1: glUniform1i(loc, vals[0]); break;
            case 2: glUniform2i(loc, vals[0], vals[1]); break;
            case 3: glUniform3i(loc, vals[0], vals[1], vals[2]); break;
            case 4: glUniform4i(loc, vals[0], vals[1], vals[2], vals[3]); break;
            default: throw new RuntimeException("Cannot set " + var + " to " + Arrays.toString(vals));
        }
    }

    public void setui(String var, int... vals) {
        int loc = loc(var);
        switch (vals.length) {
            case 1: glUniform1ui(loc, vals[0]); break;
            case 2: glUniform2ui(loc, vals[0], vals[1]); break;
            case 3: glUniform3ui(loc, vals[0], vals[1], vals[2]); break;
            case 4: glUniform4ui(loc, vals[0], vals[1], vals[2], vals[3]); break;
            default: throw new RuntimeException("Cannot set " + var + " to " + Arrays.toString(vals));
        }
    }

    public void setf(String var, float... vals) {
        int loc = loc(var);
        if (loc == -1) {
            Log.w(TAG, "Uniform not found: " + var + " (value: " + Arrays.toString(vals) + ")");
            return;
        }
        switch (vals.length) {
            case 1: glUniform1f(loc, vals[0]); break;
            case 2: glUniform2f(loc, vals[0], vals[1]); break;
            case 3: glUniform3f(loc, vals[0], vals[1], vals[2]); break;
            case 4: glUniform4f(loc, vals[0], vals[1], vals[2], vals[3]); break;
            case 9: glUniformMatrix3fv(loc, 1, true, vals, 0); break;
            default: throw new RuntimeException("Cannot set " + var + " to " + Arrays.toString(vals));
        }
    }

    /**
     * Upload a float array to a uniform array variable (e.g. {@code uniform vec2 foo[N]}).
     *
     * @param var        GLSL uniform name
     * @param vals       flat float array; length must be {@code count * components}
     * @param components number of components per element (1, 2, 3, or 4 → glUniform*fv)
     */
    public void setfv(String var, float[] vals, int components) {
        int loc = loc(var);
        if (loc == -1) {
            Log.w(TAG, "Uniform not found: " + var);
            return;
        }
        int count = vals.length / components;
        switch (components) {
            case 1: glUniform1fv(loc, count, vals, 0); break;
            case 2: glUniform2fv(loc, count, vals, 0); break;
            case 3: glUniform3fv(loc, count, vals, 0); break;
            case 4: glUniform4fv(loc, count, vals, 0); break;
            default: throw new RuntimeException("setfv: unsupported component count " + components);
        }
    }

    private int loc(String var) {
        return glGetUniformLocation(mProgramActive, var);
    }
}
