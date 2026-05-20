package amirz.dngprocessor.pipeline.burst;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import amirz.dngprocessor.R;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.gl.TexturePool;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * Wronski Alg. 5: per-frame steerable kernel covariance Ω.
 */
final class BurstWronskiKernels {

    private static Texture sIsoCov;

    private BurstWronskiKernels() {}

    /** Iso Ω placeholder when {@link BurstWronskiBisect#ENABLE_PER_FRAME_COV} is off. */
    static Texture isoCovPlaceholder() {
        if (sIsoCov == null) {
            FloatBuffer px = ByteBuffer.allocateDirect(4 * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            px.put(new float[]{2f, 0f, 2f, 0f}).position(0);
            sIsoCov = new Texture(1, 1, 4, Texture.Format.Float16, px, GL_NEAREST, GL_CLAMP_TO_EDGE);
        }
        return sIsoCov;
    }

    static Texture estimate(GLPrograms gl, Texture bayer, int srcW, int srcH) {
        int gw = srcW / 2;
        int gh = srcH / 2;
        Texture cov = TexturePool.get(gw, gh, 4, Texture.Format.Float16);
        gl.useProgram(R.raw.burst_wronski_estimate_kernels);
        gl.setTexture("bayerTex", bayer);
        gl.seti("inWidth", srcW);
        gl.seti("inHeight", srcH);
        gl.setf("kDetail", BurstWronskiTuning.K_DETAIL);
        gl.setf("kDenoise", BurstWronskiTuning.K_DENOISE);
        gl.setf("dTh", BurstWronskiTuning.D_TH);
        gl.setf("dTr", BurstWronskiTuning.D_TR);
        gl.setf("kStretch", BurstWronskiTuning.K_STRETCH);
        gl.setf("kShrink", BurstWronskiTuning.K_SHRINK);
        gl.drawBlocks(cov, false);
        return cov;
    }
}
