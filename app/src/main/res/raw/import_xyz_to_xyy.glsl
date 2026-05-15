vec3 XYZtoxyY(vec3 XYZ) {
    /* Gamut clipping: clamp negative XYZ values to prevent black spots */
    /* Color matrices can produce negative values for saturated/clipped highlights */
    XYZ = max(XYZ, vec3(0.0));
    
    vec3 result = vec3(0.345703f, 0.358539f, XYZ.y);
    float sum = XYZ.x + XYZ.y + XYZ.z;
    if (sum > 0.0001f) {
        result.xy = XYZ.xy / sum;
    }
    /* NaN check: NaN != NaN */
    if (result.x != result.x) result.x = 0.345703f;
    if (result.y != result.y) result.y = 0.358539f;
    if (result.z != result.z) result.z = 0.0f;
    return result;
}
