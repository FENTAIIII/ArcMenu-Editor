package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.VisionProtocol;
import com.fentai.arcmenu.editor.mixin.VisionGraphicsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** A server-scoped selection of native Display entities rendered only in the GUI pass. */
public final class VisionSceneClient {
    private static VisionProtocol.Scene scene;
    private static Set<Integer> entityIds = Set.of();
    private static VisionProtocol.Scene preview;
    private static Set<Integer> previewEntityIds = Set.of();

    private VisionSceneClient() {}


    public static boolean supported() { return true; }
    public static boolean ready() {
        return scene != null && VisionCursorClient.active() && scene.sessionId() == VisionCursorClient.sessionId();
    }

    public static void receive(VisionProtocol.Scene next) {
        scene = next;
        entityIds = Set.copyOf(new HashSet<>(next.entityIds()));
    }

    public static void clear() { scene = null; entityIds = Set.of(); VisionVisualClient.clear(); }

    public static void receivePreview(VisionProtocol.PreviewScene next) {
        if (!next.active()) {
            if (preview != null && preview.sessionId() == next.scene().sessionId()) clearPreview();
            return;
        }
        preview = next.scene();
        previewEntityIds = Set.copyOf(preview.entityIds());
    }

    public static boolean previewReady() { return preview != null; }
    public static void clearPreview() { preview = null; previewEntityIds = Set.of(); }

    public static boolean owns(Entity entity) {
        return entity instanceof Display && ((ready() && entityIds.contains(entity.getId()))
                || (previewReady() && previewEntityIds.contains(entity.getId())));
    }

    public static float verticalFov() { return ready() ? scene.verticalFov() : VisionProtocol.MENU_VERTICAL_FOV; }

    public static Matrix4f viewProjection(int width, int height) {
        var window = Minecraft.getInstance().getWindow();
        return ready() ? VisionSceneMath.viewProjection(scene.yaw(), scene.pitch(), scene.verticalFov(),
                window.getGuiScaledWidth(), window.getGuiScaledHeight()) : null;
    }

    public static void extract(GuiGraphicsExtractor graphics) {
        if (ready()) extractScene(graphics, scene, new EditorLayout.Rect(0, 0, graphics.guiWidth(), graphics.guiHeight()));
    }

    public static void extractPreview(GuiGraphicsExtractor graphics, EditorLayout.Rect viewport) {
        if (previewReady()) extractScene(graphics, preview, viewport);
    }

    private static void extractScene(GuiGraphicsExtractor graphics, VisionProtocol.Scene scene, EditorLayout.Rect viewport) {
        var client = Minecraft.getInstance();
        if (client.level == null || client.player == null || viewport.width() < 1 || viewport.height() < 1) return;
        var dispatcher = client.getEntityRenderDispatcher();
        var states = new ArrayList<EntityRenderState>(scene.entityIds().size());
        var guiItems = new HashSet<EntityRenderState>();
        boolean runtime = scene == VisionSceneClient.scene;
        if (runtime) VisionVisualClient.frame(scene);
        for (int id : scene.entityIds()) {
            Entity entity = client.level.getEntity(id);
            if (!(entity instanceof Display) || entity.isRemoved()) continue;
            float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(!client.level.tickRateManager().isEntityFrozen(entity));
            EntityRenderState state = dispatcher.extractEntity(entity, partialTick);
            state.lightCoords = 0xF000F0;
            state.shadowPieces.clear();
            state.nameTag = null;
            state.displayFireAnimation = false;
            state.outlineColor = EntityRenderState.NO_OUTLINE;
            if (state instanceof DisplayEntityRenderState displayState) {
                displayState.cameraYRot = scene.yaw();
                displayState.cameraXRot = scene.pitch();
                if (runtime && !VisionVisualClient.apply(id, displayState)) continue;
                if (entity instanceof Display.ItemDisplay item && item.itemRenderState() != null
                        && item.itemRenderState().itemTransform() == ItemDisplayContext.GUI
                        && state instanceof ItemDisplayEntityRenderState itemState && itemState.item.usesBlockLight()) {
                    VisionItemLighting.prepare(displayState);
                    guiItems.add(state);
                }
            }
            states.add(state);
        }
        ((VisionGraphicsAccessor) graphics).arcmenu$guiRenderState().addPicturesInPictureState(new VisionSceneRenderState(
                scene, states, viewport, guiItems));
        graphics.nextStratum();
    }
}
