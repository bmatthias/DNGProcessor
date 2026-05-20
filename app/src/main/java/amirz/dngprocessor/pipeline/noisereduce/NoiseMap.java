package amirz.dngprocessor.pipeline.noisereduce;

import android.util.Log;

import amirz.dngprocessor.R;
import amirz.dngprocessor.colorspace.ColorspaceConverter;
import amirz.dngprocessor.gl.GLPrograms;
import amirz.dngprocessor.gl.Texture;
import amirz.dngprocessor.params.SensorParams;
import amirz.dngprocessor.pipeline.Stage;
import amirz.dngprocessor.pipeline.StagePipeline;

public class NoiseMap extends Stage {
    private static final String TAG = "NoiseMap";
    private Texture[] mNoiseTex;

    public Texture[] getNoiseTex() {
        return mNoiseTex;
    }

    @Override
    public void execute(StagePipeline.StageMap previousStages) {
        GLPrograms converter = getConverter();
        Texture[] layers = previousStages.getStage(Decompose.class).getLayers();
        SensorParams sensor = getSensorParams();

        // Check if we have a valid NoiseProfile to use for signal-dependent noise estimation
        boolean hasValidNoiseProfile = hasValidNoiseProfile(sensor.noiseProfile);
        
        // Transform noise profile from sensor space to xyY (Y/luminance) space
        // We need sensorToXYZ to do the transformation
        float noiseModelA = 0f;
        float noiseModelB = 0f;
        boolean useNoiseModel = false;
        
        if (hasValidNoiseProfile) {
            // Compute sensorToXYZ matrix for transformation
            ColorspaceConverter colorspace = new ColorspaceConverter(sensor);
            float[] sensorToXYZ = colorspace.sensorToXYZ_D50;
            
            // Transform noise profile to Y (luminance) component in xyY space
            // Y in XYZ = sensorToXYZ[3]*R + sensorToXYZ[4]*G + sensorToXYZ[5]*B
            // For variance: Var(Y) = sensorToXYZ[3]^2*Var(R) + sensorToXYZ[4]^2*Var(G) + sensorToXYZ[5]^2*Var(B)
            // Where Var(R) = a_R*signal_R + b_R, etc.
            
            // Transform the noise model parameters for Y component
            // For a linear combination Y = a*R + b*G + c*B with independent R,G,B:
            // Var(Y) = a^2*Var(R) + b^2*Var(G) + c^2*Var(B)
            float yR = sensorToXYZ[3];  // Y component from R
            float yG = sensorToXYZ[4];  // Y component from G
            float yB = sensorToXYZ[5];  // Y component from B
            
            // Transform shot noise coefficient (a): scales with signal
            // For Y = yR*R + yG*G + yB*B, if R=G=B=s (neutral gray):
            // Var(Y) = (yR^2*a_R + yG^2*a_G + yB^2*a_B) * s + (yR^2*b_R + yG^2*b_G + yB^2*b_B)
            noiseModelA = yR * yR * sensor.noiseProfile[0] +  // a_R
                         yG * yG * sensor.noiseProfile[2] +  // a_G
                         yB * yB * sensor.noiseProfile[4];   // a_B
            
            // Transform read noise coefficient (b): signal-independent
            noiseModelB = yR * yR * sensor.noiseProfile[1] +  // b_R
                         yG * yG * sensor.noiseProfile[3] +  // b_G
                         yB * yB * sensor.noiseProfile[5];   // b_B
            
            // The signal level in xyY Y is different from sensor space
            // For neutral gray: if sensor RGB = [0.5, 0.5, 0.5], then Y_xyz = yR*0.5 + yG*0.5 + yB*0.5
            // We need to account for this scaling when using the noise model
            float yScale = Math.abs(yR + yG + yB);  // Typical value ~1.0 for neutral gray
            if (yScale > 0.1f) {
                // Normalize so that signal level 0.5 in sensor space maps to reasonable Y value
                // The noise model will be used with Y values in xyY space
                noiseModelA /= (yScale * yScale);  // Account for signal scaling
                noiseModelB /= (yScale * yScale);
            }
            
            useNoiseModel = true;
            Log.d(TAG, String.format("NoiseProfile transformed to xyY Y: a=%.6f, b=%.6f (yScale=%.3f)",
                    noiseModelA, noiseModelB, yScale));
        } else if (sensor.baselineNoise != 1.0f) {
            // Use BaselineNoise to create synthetic noise coefficients
            // Higher baselineNoise = noisier camera
            // Use typical values similar to darktable's generic profile
            noiseModelA = 0.0001f * sensor.baselineNoise * sensor.baselineNoise;
            noiseModelB = 0.00001f * sensor.baselineNoise * sensor.baselineNoise;
            useNoiseModel = true;
            Log.d(TAG, String.format("Using BaselineNoise=%.2f: synthetic a=%.6f, b=%.6f", 
                    sensor.baselineNoise, noiseModelA, noiseModelB));
        }

        mNoiseTex = new Texture[layers.length];
        for (int i = 0; i < layers.length; i++) {
            converter.setTexture("intermediate", layers[i]);
            converter.seti("bufSize", layers[i].getWidth(), layers[i].getHeight());
            converter.seti("radius", 1 << (i * 2));
            
            // Pass noise model parameters to shader for signal-dependent noise estimation
            // When useNoiseModel=1, shader blends model-based noise with gradient-based noise
            // Values are now properly transformed from sensor space to xyY Y space
            converter.setf("noiseModelA", noiseModelA);
            converter.setf("noiseModelB", noiseModelB);
            converter.seti("useNoiseModel", useNoiseModel ? 1 : 0);
            
            // Note: Must use 4 channels (RGBA16F) because RGB16F is not color-renderable in GLES 3.0
            mNoiseTex[i] = new Texture(layers[i].getWidth() / 4 + 1,
                    layers[i].getHeight() / 4 + 1, 4,
                    Texture.Format.Float16, null);
            converter.drawBlocks(mNoiseTex[i]);
        }

        converter.useProgram(R.raw.stage2_1_noise_level_blur_fs);

        for (int i = 0; i < layers.length; i++) {
            Texture layer = mNoiseTex[i];
            converter.seti("minxy", 0, 0);
            converter.seti("maxxy", layer.getWidth() - 1, layer.getHeight() - 1);
            try (Texture tmp = new Texture(layer.getWidth(), layer.getHeight(), 4,
                    Texture.Format.Float16, null)) {

                // First render to the tmp buffer.
                converter.setTexture("buf", mNoiseTex[i]);
                converter.setf("sigma", 1.5f * (1 << i));
                converter.seti("radius", 3 << i, 1);
                converter.seti("dir", 0, 1); // Vertical
                converter.drawBlocks(tmp, false);

                // Now render from tmp to the real buffer.
                converter.setTexture("buf", tmp);
                converter.seti("dir", 1, 0); // Horizontal
                converter.drawBlocks(mNoiseTex[i]);
            }
        }
    }
    
    /**
     * Check if NoiseProfile is valid (exists and has non-zero values)
     */
    private boolean hasValidNoiseProfile(float[] noiseProfile) {
        if (noiseProfile == null || noiseProfile.length != 6) {
            return false;
        }
        for (float v : noiseProfile) {
            if (v > 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getShader() {
        return R.raw.stage2_1_noise_level_fs;
    }

    @Override
    public boolean isEnabled() {
        return getProcessParams().noiseReduce;
    }
}
