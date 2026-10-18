package com.fentai.arcmenu.editor;

/** Maps the server menu plane into the current Minecraft camera aperture in GUI units. */
public final class EffectProjection {
    private EffectProjection() {}

    public static EditorLayout.ScreenRect calculate(EditorLayout.Rect viewport, double canvasWidth, double canvasHeight,
                                                     double pixelsPerBlock, double distance, double offsetX, double offsetY,
                                                     double verticalFov) {
        return calculate(new EditorLayout.ScreenRect(viewport.x(), viewport.y(), viewport.width(), viewport.height()),
                canvasWidth, canvasHeight, pixelsPerBlock, distance, offsetX, offsetY, verticalFov);
    }

    public static EditorLayout.ScreenRect calculate(EditorLayout.ScreenRect viewport, double canvasWidth, double canvasHeight,
                                                     double pixelsPerBlock, double distance, double offsetX, double offsetY,
                                                     double verticalFov) {
        if (!positive(viewport.width()) || !positive(viewport.height()) || !positive(canvasWidth) || !positive(canvasHeight)
                || !positive(pixelsPerBlock) || !positive(distance) || !Double.isFinite(offsetX) || !Double.isFinite(offsetY)
                || !Double.isFinite(verticalFov) || verticalFov <= 0 || verticalFov >= 180) {
            throw new IllegalArgumentException("Invalid Effect projection geometry");
        }
        double visibleHeight = 2 * distance * Math.tan(Math.toRadians(verticalFov) / 2) * pixelsPerBlock;
        // The camera aperture is 16:9 in physical pixels. GUI ceil scaling can differ slightly between axes.
        double scaleX = viewport.width() / (visibleHeight * 16.0 / 9), scaleY = viewport.height() / visibleHeight;
        double width = canvasWidth * scaleX, height = canvasHeight * scaleY;
        double centerX = viewport.x() + viewport.width() / 2.0 + offsetX * scaleX;
        double centerY = viewport.y() + viewport.height() / 2.0 - offsetY * scaleY;
        return new EditorLayout.ScreenRect(centerX - width / 2, centerY - height / 2, width, height);
    }

    /** Converts the composited bottom-origin framebuffer aperture into the actual GUI rectangle. */
    public static EditorLayout.ScreenRect guiAperture(EditorLayout.Rect framebuffer, int framebufferWidth, int framebufferHeight,
                                                       int guiWidth, int guiHeight) {
        double scaleX = guiWidth / (double) framebufferWidth, scaleY = guiHeight / (double) framebufferHeight;
        return new EditorLayout.ScreenRect(framebuffer.x() * scaleX,
                (framebufferHeight - framebuffer.y() - framebuffer.height()) * scaleY,
                framebuffer.width() * scaleX, framebuffer.height() * scaleY);
    }

    /** Returns a bottom-origin crop of the real world frame; unrendered pixels are never synthesized. */
    public static EditorLayout.Rect fitCrop(int sourceWidth, int sourceHeight, double cameraAspect,
                                            double canvasWidth, double canvasHeight, double pixelsPerBlock,
                                            double distance, double offsetX, double offsetY, double verticalFov) {
        double planeHeight = 2 * distance * Math.tan(Math.toRadians(verticalFov) / 2) * pixelsPerBlock;
        double planeWidth = planeHeight * cameraAspect;
        double minimum = Math.max(Math.min(1, 16.0 / sourceWidth), Math.min(1, 9.0 / sourceHeight));
        double fraction = Math.max(minimum, Math.min(1, Math.max(canvasWidth / planeWidth, canvasHeight / planeHeight)));
        int width = Math.max(1, Math.min(sourceWidth, (int) Math.round(sourceWidth * fraction)));
        int height = Math.max(1, Math.min(sourceHeight, (int) Math.round(sourceHeight * fraction)));
        int x = (int) Math.round(sourceWidth * (.5 + offsetX / planeWidth) - width / 2.0);
        int y = (int) Math.round(sourceHeight * (.5 + offsetY / planeHeight) - height / 2.0);
        return new EditorLayout.Rect(Math.max(0, Math.min(sourceWidth - width, x)),
                Math.max(0, Math.min(sourceHeight - height, y)), width, height);
    }

    /** Applies the same framebuffer crop to GUI-space element geometry. */
    public static EditorLayout.ScreenRect cropped(EditorLayout.ScreenRect raw, EditorLayout.Rect aperture,
                                                  EditorLayout.Rect source, int sourceWidth, int sourceHeight) {
        return cropped(raw, new EditorLayout.ScreenRect(aperture.x(), aperture.y(), aperture.width(), aperture.height()),
                source, sourceWidth, sourceHeight);
    }

    public static EditorLayout.ScreenRect cropped(EditorLayout.ScreenRect raw, EditorLayout.ScreenRect aperture,
                                                  EditorLayout.Rect source, int sourceWidth, int sourceHeight) {
        double scaleX = sourceWidth / (double) source.width(), scaleY = sourceHeight / (double) source.height();
        double sourceTop = (sourceHeight - source.y() - source.height()) / (double) sourceHeight;
        return new EditorLayout.ScreenRect(
                aperture.x() + (raw.x() - aperture.x() - source.x() / (double) sourceWidth * aperture.width()) * scaleX,
                aperture.y() + (raw.y() - aperture.y() - sourceTop * aperture.height()) * scaleY,
                raw.width() * scaleX, raw.height() * scaleY);
    }

    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
}
