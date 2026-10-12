package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.init.Element;

/** Delegated actions belong to the closest control, never its enclosing row. */
final class AuiActions {
    private AuiActions() {}

    static Element target(Element target) {
        for(Element element=target;element!=null;element=element.parentElement) {
            String action=element.getAttribute("data-action");
            if(action==null||action.isBlank())continue;
            return element.isDisabled()||"true".equals(element.getAttribute("aria-disabled"))?null:element;
        }
        return null;
    }
}
