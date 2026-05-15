vec3[25] load5x5(ivec2 xy, int n, sampler2D buf) {
    vec3 outputArray[25];
    for (int i = 0; i < 25; i++) {
        vec4 texData = texelFetch(buf, xy + n * ivec2((i % 5) - 2, (i / 5) - 2), 0);
        float invScale = max(texData.w, 0.001);
        outputArray[i] = vec3(texData.x, texData.y, texData.z / invScale);
    }
    return outputArray;
}
