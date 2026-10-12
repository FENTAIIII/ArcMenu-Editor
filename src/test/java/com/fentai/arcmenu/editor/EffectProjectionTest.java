package com.fentai.arcmenu.editor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class EffectProjectionTest {
    @Test void activeMenuShaderKeepsEffectGeometryStableAcrossCameraFovs() {
        var aperture = new EditorLayout.Rect(100, 50, 320, 180);
        var reference = EffectProjection.calculate(aperture, 160, 90, 30, 3, 30, 15,
                EffectProjection.MENU_VERTICAL_FOV);
        var referenceCrop = EffectProjection.fitCrop(1280, 800, 16.0 / 9, 160, 90, 30, 3, 30, 15,
                EffectProjection.MENU_VERTICAL_FOV);
        for (double cameraFov : new double[] {30, 70, 110}) {
            double menuFov = EffectProjection.verticalFov(cameraFov, true, true);
            assertEquals(reference, EffectProjection.calculate(aperture, 160, 90, 30, 3, 30, 15, menuFov));
            assertEquals(referenceCrop, EffectProjection.fitCrop(1280, 800, 16.0 / 9, 160, 90, 30, 3, 30, 15, menuFov));
        }
    }

    @Test void missingMenuShaderRetainsTheActualCameraProjection() {
        for (double cameraFov : new double[] {30, 70, 110}) {
            assertEquals(cameraFov, EffectProjection.verticalFov(cameraFov, false, true));
        }
    }

    @Test void manuallyInstalledShaderWithoutServerPackRetainsCameraProjection() {
        for (double cameraFov : new double[] {30, 70, 110}) {
            assertEquals(cameraFov, EffectProjection.verticalFov(cameraFov, true, false));
        }
    }

    @Test void pixelDensityStillControlsPlaybackSizeWithFixedMenuProjection() {
        var aperture = new EditorLayout.Rect(100, 50, 320, 180);
        double menuFov = EffectProjection.verticalFov(110, true, true);
        var playback = EffectProjection.calculate(aperture, 160, 90, 30, 3, 0, 0, menuFov);
        var denser = EffectProjection.calculate(aperture, 160, 90, 60, 3, 0, 0, menuFov);
        assertEquals(playback.width() / 2, denser.width(), 1e-9);
        assertEquals(playback.height() / 2, denser.height(), 1e-9);
    }

    @Test void ninetyDegreeCameraFitsAPlaneSixBlocksHigh() {
        var result = EffectProjection.calculate(new EditorLayout.Rect(100, 50, 320, 180), 320, 180, 30, 3, 0, 0, 90);
        assertEquals(100, result.x(), 1e-9);
        assertEquals(50, result.y(), 1e-9);
        assertEquals(320, result.width(), 1e-9);
        assertEquals(180, result.height(), 1e-9);
    }
    @Test void serverOffsetsUseRightAndUpLogicalPixels() {
        var result = EffectProjection.calculate(new EditorLayout.Rect(100, 50, 320, 180), 320, 180, 30, 3, 12, -6, 90);
        assertEquals(112, result.x(), 1e-9);
        assertEquals(56, result.y(), 1e-9);
    }
    @Test void guiScalingPreservesThePlaneRelativeToItsAperture() {
        var result = EffectProjection.calculate(new EditorLayout.Rect(200, 100, 640, 360), 320, 180, 30, 3, 12, -6, 90);
        assertEquals(224, result.x(), 1e-9);
        assertEquals(112, result.y(), 1e-9);
        assertEquals(640, result.width(), 1e-9);
        assertEquals(360, result.height(), 1e-9);
    }
    @Test void doublingCameraDistanceHalvesTheCenteredPlane() {
        var result = EffectProjection.calculate(new EditorLayout.Rect(100, 50, 320, 180), 320, 180, 30, 6, 0, 0, 90);
        assertEquals(180, result.x(), 1e-9);
        assertEquals(95, result.y(), 1e-9);
        assertEquals(160, result.width(), 1e-9);
        assertEquals(90, result.height(), 1e-9);
    }
    @Test void fitUsesTheRenderedPlaneAndRecentersServerOffsets() {
        var aperture = new EditorLayout.Rect(100, 50, 320, 180);
        var raw = EffectProjection.calculate(aperture, 160, 90, 30, 3, 30, 15, 90);
        var crop = EffectProjection.fitCrop(1280, 800, 16.0 / 9, 160, 90, 30, 3, 30, 15, 90);
        var result = EffectProjection.cropped(raw, aperture, crop, 1280, 800);
        assertEquals(440, crop.x()); assertEquals(267, crop.y());
        assertEquals(640, crop.width()); assertEquals(400, crop.height());
        assertEquals(100, result.x(), .5); assertEquals(50, result.y(), .5);
        assertEquals(320, result.width(), 1e-9); assertEquals(180, result.height(), 1e-9);
    }
    @Test void fitNeverRequestsPixelsOutsideTheCompletedFramebuffer() {
        var crop = EffectProjection.fitCrop(1280, 800, 16.0 / 9, 160, 90, 30, 3, 1000, -1000, 90);
        assertEquals(640, crop.x()); assertEquals(0, crop.y());
        assertEquals(1280, crop.right()); assertEquals(400, crop.bottom());
    }
    @Test void aCanvasLargerThanTheRenderedWorldUsesTheWholeFrame() {
        var crop = EffectProjection.fitCrop(1280, 800, 16.0 / 9, 640, 360, 30, 3, 0, 0, 90);
        assertEquals(new EditorLayout.Rect(0, 0, 1280, 800), crop);
    }
    @Test void doublingPixelDensityHalvesPlaybackScaleWhileFitKeepsTheCanvasSize() {
        var aperture = new EditorLayout.Rect(100, 50, 320, 180);
        var playback = EffectProjection.calculate(aperture, 160, 90, 30, 3, 0, 0, 90);
        var denserPlayback = EffectProjection.calculate(aperture, 160, 90, 60, 3, 0, 0, 90);
        assertEquals(playback.width() / 2, denserPlayback.width(), 1e-9);
        assertEquals(playback.height() / 2, denserPlayback.height(), 1e-9);
        var fit = EffectProjection.cropped(playback, aperture,
                EffectProjection.fitCrop(1280, 800, 16.0 / 9, 160, 90, 30, 3, 0, 0, 90), 1280, 800);
        var denserFit = EffectProjection.cropped(denserPlayback, aperture,
                EffectProjection.fitCrop(1280, 800, 16.0 / 9, 160, 90, 60, 3, 0, 0, 90), 1280, 800);
        assertEquals(fit.width(), denserFit.width(), 1e-9);
        assertEquals(fit.height(), denserFit.height(), 1e-9);
        assertEquals(fit.x(), denserFit.x(), 1e-9);
        assertEquals(fit.y(), denserFit.y(), 1e-9);
    }
    @Test void guiScaleThreeOverlaysUseTheActualCompositedAperture() {
        var physical = new EditorLayout.Rect(308, 342, 272, 153);
        var aperture = EffectProjection.guiAperture(physical, 1280, 800, 427, 267);
        var plane = EffectProjection.calculate(aperture, 320, 180, 30, 3, 0, 0, 90);
        assertEquals(physical.width(), plane.width() * 1280 / 427, 1e-9);
        assertEquals(physical.height(), plane.height() * 800 / 267, 1e-9);
        assertEquals(physical.x(), plane.x() * 1280 / 427, 1e-9);
        assertEquals(800 - physical.y() - physical.height(), plane.y() * 800 / 267, 1e-9);
        assertTrue(plane.width() < 96);
        assertTrue(plane.height() < 54);
    }

}
