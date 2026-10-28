package com.fentai.arcmenu.editor;

import org.joml.Matrix3f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** Keep front-lit sprites bright while block-lit models retain vanilla inventory shading in the same pass. */
public final class VisionItemMath {
    // Lighting.ITEMS_3D applies this orthogonal transform to the ITEMS_FLAT light directions.
    // Its transpose on block normals gives the same dot products with the shared flat-light uniform.
    private static final Matrix3f BLOCK_NORMALS = new Matrix3f().scaling(1, -1, 1)
            .rotateYXZ(1.0821041f, 3.2375858f, 0).transpose();
    private VisionItemMath() {}

    public static double squash(Matrix4fc matrix) {
        double width = new Vector3f(matrix.m00(), matrix.m01(), matrix.m02()).length();
        double depth = new Vector3f(matrix.m20(), matrix.m21(), matrix.m22()).length();
        return width > 0 && depth > 0 ? width / depth : Double.NaN;
    }

    public static Matrix3f guiNormals(Matrix3f normal, double squash) {
        if (!(squash > 0) || !Double.isFinite(squash)) return normal;
        return new Matrix3f(BLOCK_NORMALS).scale(1, -1, (float) (1 / squash)).mul(normal);
    }
}
