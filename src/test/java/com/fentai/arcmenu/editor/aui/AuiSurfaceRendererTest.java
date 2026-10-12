package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.render.AABB;
import com.fentai.arcmenu.editor.aui.internal.render.Mask;
import com.fentai.arcmenu.editor.aui.internal.spi.AuiServices;
import com.fentai.arcmenu.editor.aui.internal.spi.MeshFormat;
import com.fentai.arcmenu.editor.aui.internal.spi.MeshMode;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;

class AuiSurfaceRendererTest {
    @Test void editingResetsTheCaretToTheVisiblePhaseAndIdleTimeContinuesBlinking() {
        var blink = new AuiCaret.Blink();
        blink.interact(750);
        assertEquals(0, blink.phase(750));
        assertEquals(0, blink.phase(1249));
        assertEquals(1, blink.phase(1250));
        assertEquals(0, blink.phase(1750));
        blink.interact(1300);
        assertEquals(0, blink.phase(1300));
        assertEquals(0, blink.phase(1799));
        assertEquals(1, blink.phase(1800));
    }

    @Test void insertionCaretRemainsOpaqueAtFractionalGlyphAdvancesAndViewportScales() {
        var font = new Font(Font.SANS_SERIF, Font.PLAIN, 16);
        var fontContext = new FontRenderContext(null, true, true);
        for (float scale : new float[]{0.25f, 0.5f, 0.75f, 1, 1.25f, 2, 3}) {
            for (String prefix : new String[]{"", "i", "il", "ill", "Wi", "iWi", "中i"}) {
                float x = 20.31f + (float) font.getStringBounds(prefix, fontContext).getWidth();
                var renderer = new AuiSurfaceRenderer();
                AuiServices.setRender(renderer.service());
                renderer.begin(256, 96, scale);
                var pose = new Matrix4f().translation(3.21f, 4.37f, 0).scale(scale, scale, 1);
                var mesh = renderer.service().beginMesh(MeshMode.TRIANGLES, MeshFormat.POSITION_COLOR);
                AuiCaret.addRect(mesh, pose, x, 8, 16, 0xff2846bd);
                mesh.submit();
                renderer.end();
                int expectedRight = Math.round(pose.m00() * x + pose.m30());
                int expectedWidth = Math.max(1, Math.round(0.7f * scale));
                int row = (int) Math.floor(pose.m11() * 16 + pose.m31());
                for (int column = expectedRight - expectedWidth; column < expectedRight; column++)
                    assertTrue((renderer.image().getRGB(column, row) >>> 24) >= 250,
                            "Caret must retain an opaque device pixel at scale " + scale + " after " + prefix);
                assertEquals(0, renderer.image().getRGB(expectedRight, row));
            }
        }
    }

    @Test void paintsActualAuiMeshAndPreservesPaintOrder() {
        var renderer=new AuiSurfaceRenderer();AuiServices.setRender(renderer.service());renderer.begin(64,64,1);
        var red=renderer.service().beginMesh(MeshMode.QUADS,MeshFormat.POSITION_COLOR);
        for(float[] p:new float[][]{{4,4},{4,48},{48,48},{48,4}})red.vertex(new Matrix4f(),p[0],p[1],0xffff0000);
        red.submit();
        var blue=renderer.service().beginMesh(MeshMode.QUADS,MeshFormat.POSITION_COLOR);
        for(float[] p:new float[][]{{20,20},{20,56},{56,56},{56,20}})blue.vertex(new Matrix4f(),p[0],p[1],0xff0000ff);
        blue.submit();renderer.end();
        assertEquals(0xffff0000,renderer.image().getRGB(10,10));assertEquals(0xff0000ff,renderer.image().getRGB(30,30));
        assertEquals(0,renderer.image().getRGB(60,60));
    }
    @Test void cssScissorKeepsOutsidePixelsUntouched() {
        var renderer=new AuiSurfaceRenderer();AuiServices.setRender(renderer.service());renderer.begin(64,64,2);
        renderer.clip(new AABB(8,8,8,8));
        var mesh=renderer.service().beginMesh(MeshMode.QUADS,MeshFormat.POSITION_COLOR);
        for(float[] p:new float[][]{{0,0},{0,64},{64,64},{64,0}})mesh.vertex(new Matrix4f(),p[0],p[1],0xff227744);
        mesh.submit();renderer.end();assertEquals(0xff227744,renderer.image().getRGB(20,20));
        assertEquals(0,renderer.image().getRGB(8,8));assertEquals(0,renderer.image().getRGB(40,40));
    }
    @Test void retainedSurfaceIsReusedAtTheSameDimensionsAndCleared() {
        var renderer=new AuiSurfaceRenderer();renderer.begin(32,24,1);var image=renderer.image();renderer.end();
        image.setRGB(4,4,0xffabcdef);renderer.begin(32,24,1);assertSame(image,renderer.image());assertEquals(0,image.getRGB(4,4));renderer.end();
        renderer.begin(48,24,1);assertNotSame(image,renderer.image());renderer.end();
    }
    @Test void documentPoseScaleIsNotAppliedTwiceToOverflowMasks() {
        var renderer=new AuiSurfaceRenderer();AuiServices.setRender(renderer.service());renderer.begin(128,128,2);
        var pose=new PoseStack();pose.scale(2,2,1);
        Mask.testScissorSink=renderer::clip;Mask.pushScissorScale(2,pose);
        try {
            Mask.resetDepth(64,64);Mask.pushMask(pose,8,8,16,16,new float[4]);
            var mesh=renderer.service().beginMesh(MeshMode.QUADS,MeshFormat.POSITION_COLOR);
            for(float[] p:new float[][]{{0,0},{0,64},{64,64},{64,0}})mesh.vertex(pose.last().pose(),p[0],p[1],0xff227744);
            mesh.submit();assertEquals(0xff227744,renderer.image().getRGB(20,20));
            assertEquals(0xff227744,renderer.image().getRGB(45,45));assertEquals(0,renderer.image().getRGB(50,50));
            Mask.popMask(pose,8,8,16,16,new float[4]);
        } finally {Mask.popScissorScale();Mask.testScissorSink=null;renderer.end();}
    }
    @Test void manyFontColorsKeepCacheBoundedWithoutMutatingDuringCompute() {
        var image=new BufferedImage(4,4,BufferedImage.TYPE_INT_ARGB);image.setRGB(2,1,0xffffffff);
        var texture=new AuiSurfaceRenderer.SurfaceTexture(image);
        for(int i=0;i<2200;i++)assertNotNull(texture.tinted(0xff000000|i));
        assertEquals(2048,texture.tinted.size());assertEquals(0xff123456,texture.tinted(0xff123456).getRGB(2,1));
        var cropped=texture.tintedRegion(0xff765432,2,1,1,1);assertEquals(1,cropped.getWidth());assertEquals(0xff765432,cropped.getRGB(0,0));
        assertSame(cropped,texture.tintedRegion(0xff765432,2,1,1,1));
    }
}
