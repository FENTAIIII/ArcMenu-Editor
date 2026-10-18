package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.VisionProtocol;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

import java.util.List;

/** One texture and depth buffer preserve ordering between every element of a menu. */
public record VisionSceneRenderState(VisionProtocol.Scene scene, List<EntityRenderState> entities,
                                     EditorLayout.Rect viewport) implements PictureInPictureRenderState {
    public VisionSceneRenderState { entities = List.copyOf(entities); }
    @Override public int x0() { return viewport.x(); }
    @Override public int y0() { return viewport.y(); }
    @Override public int x1() { return viewport.right(); }
    @Override public int y1() { return viewport.bottom(); }
    @Override public float scale() { return 1; }
    @Override public ScreenRectangle scissorArea() { return bounds(); }
    @Override public ScreenRectangle bounds() { return new ScreenRectangle(x0(), y0(), viewport.width(), viewport.height()); }
}
