package amirz.dngprocessor.pipeline.burst;

import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.params.SensorParams;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * Brightness-indexed std/diff curves for Wronski {@code apply_noise_model} (1000× brightness).
 */
final class BurstWronskiNoiseLut {

    final Texture stdCurveTex;
    final Texture diffCurveTex;

    private BurstWronskiNoiseLut(Texture std, Texture diff) {
        stdCurveTex = std;
        diffCurveTex = diff;
    }

    static BurstWronskiNoiseLut createDefault() {
        return fromLinearModel(1e-4f, 1e-3f, 0.02f);
    }

    static BurstWronskiNoiseLut fromSensor(SensorParams sensor) {
        float read = 1e-4f;
        float shot = 1e-3f;
        if (sensor.noiseProfile != null && sensor.noiseProfile.length >= 2) {
            read = Math.max(sensor.noiseProfile[0], 1e-6f);
            shot = Math.max(sensor.noiseProfile[1], 1e-6f);
        }
        read *= Math.max(sensor.baselineNoise, 0.25f);
        shot *= Math.max(sensor.baselineNoise, 0.25f);
        return fromLinearModel(read * read, shot, 0.02f);
    }

    static BurstWronskiNoiseLut fromAdaptive(BurstAdaptiveNoise model) {
        float a = 0f;
        float b = 0f;
        for (int ch = 0; ch < 4; ch++) {
            a += model.a[ch];
            b += model.b[ch];
        }
        a *= 0.25f;
        b *= 0.25f;
        return fromLinearModel(Math.max(a, 1e-6f), Math.max(b, 1e-6f), 0.02f);
    }

    private static BurstWronskiNoiseLut fromLinearModel(float sigmaA, float sigmaB, float diffFloor) {
        int n = BurstWronskiTuning.NOISE_LUT_SIZE;
        FloatBuffer stdBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        FloatBuffer diffBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (int i = 0; i < n; i++) {
            float brightness = i / 1000f;
            float sigma = (float) Math.sqrt(Math.max(sigmaA + sigmaB * brightness, 1e-8f));
            stdBuf.put(sigma);
            diffBuf.put(diffFloor);
        }
        stdBuf.position(0);
        diffBuf.position(0);
        Texture stdTex = new Texture(n, 1, 1, Texture.Format.Float16, stdBuf, GL_LINEAR, GL_CLAMP_TO_EDGE);
        Texture diffTex = new Texture(n, 1, 1, Texture.Format.Float16, diffBuf, GL_LINEAR, GL_CLAMP_TO_EDGE);
        return new BurstWronskiNoiseLut(stdTex, diffTex);
    }

    void close() {
        stdCurveTex.close();
        diffCurveTex.close();
    }
}
