package com.fentai.arcmenu.editor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspacePreviewGeometryTest {
    @Test void highResolutionProjectsDownsampleUniformlyInto720p(){var crop=WorkspacePreviewGeometry.crop(2560,1440,new EditorLayout.ScreenRect(0,0,2560,1440));assertEquals(1280,crop.outputWidth());assertEquals(720,crop.outputHeight());assertEquals(0,crop.offsetX());}
    @Test void smallNativeProjectsArePaddedWithoutUpscaling(){var crop=WorkspacePreviewGeometry.crop(1920,1080,new EditorLayout.ScreenRect(500,300,500,281));assertEquals(500,crop.outputWidth());assertEquals(281,crop.outputHeight());assertEquals(390,crop.offsetX());assertEquals(219,crop.offsetY());}
    @Test void partiallyVisiblePlanesClipTheirRightAndBottomCorrectly(){var crop=WorkspacePreviewGeometry.crop(1920,1080,new EditorLayout.ScreenRect(-100,-50,500,300));assertEquals(0,crop.x());assertEquals(0,crop.y());assertEquals(400,crop.width());assertEquals(250,crop.height());}
    @Test void insufficientOrOffscreenNativeFramesRejectInsteadOfGeneratingFakeResolution(){assertThrows(IllegalArgumentException.class,()->WorkspacePreviewGeometry.crop(1000,600,new EditorLayout.ScreenRect(0,0,400,300)));assertThrows(IllegalArgumentException.class,()->WorkspacePreviewGeometry.crop(1920,1080,new EditorLayout.ScreenRect(3000,2000,400,300)));}
}
