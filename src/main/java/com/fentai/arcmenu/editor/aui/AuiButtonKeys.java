package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.init.Element;

/** Keeps remembered pointer focus from turning Space into an unintended action. */
final class AuiButtonKeys {
    private Element tabFocused;

    void reset() { tabFocused=null; }
    void tabFocused(Element element) { tabFocused=element; }

    boolean route(int logicalKey,Element focused,Runnable activate) {
        if(logicalKey==257||logicalKey==335) {
            if(focused==null||!"BUTTON".equalsIgnoreCase(focused.tagName))return false;
            if(button(focused))activate.run();
            return true;
        }
        if(logicalKey!=32||AuiInputFocus.editable(focused))return false;
        if(focused==tabFocused&&button(focused))activate.run();
        // Consume Space before the embedded library activates remembered buttons.
        return true;
    }

    static boolean button(Element element) {
        return element!=null&&"BUTTON".equalsIgnoreCase(element.tagName)
                &&!element.isDisabled()&&!"true".equals(element.getAttribute("aria-disabled"))
                &&AuiFocus.visible(element);
    }
}
