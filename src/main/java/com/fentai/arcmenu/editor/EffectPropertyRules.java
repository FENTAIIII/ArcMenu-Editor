package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import java.util.Set;

/** Animatable properties of the menu scene model; editor-only metadata has no stopwatch. */
public final class EffectPropertyRules {
    private static final Set<String> COMMON=Set.of("offset.x","offset.y","offset.z","rotation.x","rotation.y","rotation.z","scale.x","scale.y","scale.z","visible");
    public static boolean animated(byte kind,String key) {
        if(COMMON.contains(key))return true;
        return switch(kind) {
            case EditorProtocol.KIND_GROUP -> key.equals("opacity");
            case EditorProtocol.KIND_RECTANGLE,EditorProtocol.KIND_LINE -> Set.of("width","height","color","opacity").contains(key);
            case EditorProtocol.KIND_FRAME -> Set.of("width","height","thickness","color","opacity").contains(key);
            case EditorProtocol.KIND_TEXT -> Set.of("content","size","font","line-width","alignment","opacity").contains(key);
            case EditorProtocol.KIND_IMAGE -> Set.of("source","width","height","color","opacity").contains(key);
            case EditorProtocol.KIND_GIF -> Set.of("source","width","height","color","opacity","speed").contains(key);
            case EditorProtocol.KIND_ITEM -> Set.of("material","context").contains(key);
            case EditorProtocol.KIND_BLOCK -> key.equals("block-data");
            default -> false;
        };
    }
    private EffectPropertyRules(){}
}
