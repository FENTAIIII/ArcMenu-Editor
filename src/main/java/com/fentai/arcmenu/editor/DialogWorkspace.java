package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import com.fentai.arcmenu.protocol.EditorProtocol.NodeSnapshot;
import com.fentai.arcmenu.protocol.EditorProtocol.PropertySnapshot;
import com.fentai.arcmenu.protocol.EditorProtocol.SnapshotPacket;

/**
 * Dialog drafts: the safe frame in which content keeps ArcMenu's position and size, and the properties a Dialog
 * cannot show.
 */
final class DialogWorkspace
{
    /** Outline of the safe frame. */
    static final int FRAME = 0xFFFF4D4D;
    /** Outline and tint of content outside the safe frame. */
    static final int OUTSIDE = 0xFFFF4D4D;
    static final int OUTSIDE_FILL = 0x40FF4D4D;
    /** Labels of properties a Dialog ignores. */
    static final int UNSUPPORTED = 0xFF5A6170;
    /** Badge of elements whose values the quick actions dialog keeps from when the player joined. */
    static final int FROZEN = 0xFF7FC8FF;
    static final int FROZEN_FILL = 0xD0102838;

    private DialogWorkspace()
    {}

    static boolean isDialog(SnapshotPacket snapshot)
    {
        return snapshot != null && snapshot.menuId().startsWith(EditorProtocol.DIALOG_PREFIX);
    }

    /** The safe frame in menu coordinates, centered on the canvas. */
    static CanvasSnapping.Box safeFrame()
    {
        double halfWidth = EditorProtocol.DIALOG_SAFE_WIDTH / 2.0;
        double halfHeight = EditorProtocol.DIALOG_SAFE_HEIGHT / 2.0;
        return new CanvasSnapping.Box(-halfWidth, halfWidth, -halfHeight, halfHeight);
    }

    static boolean outside(CanvasSnapping.Box box)
    {
        CanvasSnapping.Box frame = safeFrame();
        double tolerance = 1.0e-6;
        return box.left() < frame.left() - tolerance || box.right() > frame.right() + tolerance
                || box.bottom() < frame.bottom() - tolerance || box.top() > frame.top() + tolerance;
    }

    /**
     * Whether the element shows text or a tooltip that the quick actions dialog keeps as it was when the player
     * joined; such values do not refresh when the player presses the key again.
     */
    static boolean frozen(NodeSnapshot node)
    {
        for (PropertySnapshot property : node.properties())
        {
            if ((property.key().equals("content") || property.key().equals("tooltip"))
                    && EditorProtocol.dialogTextFreezes(property.value()))
            {
                return true;
            }
        }
        return false;
    }

    /** Why a Dialog ignores the property, or an empty string when it shows it. */
    static String unsupportedReason(String key)
    {
        return switch (key)
        {
        case "rotation.x", "rotation.y" -> EditorI18n.text("arcmenu_editor.dialog.unsupported.rotation");
        case "update" -> EditorI18n.text("arcmenu_editor.dialog.unsupported.update");
        case "on-hover", "on-leave" -> EditorI18n.text("arcmenu_editor.dialog.unsupported.hover");
        case "caret", "on-complete" -> EditorI18n.text("arcmenu_editor.dialog.unsupported.input");
        default -> "";
        };
    }
}
