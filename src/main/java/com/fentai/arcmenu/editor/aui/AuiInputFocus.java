package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.element.AbstractText;
import com.fentai.arcmenu.editor.aui.internal.element.Select;
import com.fentai.arcmenu.editor.aui.internal.init.Element;

/** Resolves a control when the pointer hits its label or an inline descendant. */
final class AuiInputFocus {
    private AuiInputFocus() {}

    static Element control(Element target) {
        for(Element node=target;node!=null;node=node.parentElement) {
            if("LABEL".equalsIgnoreCase(node.tagName)) {
                Element labeled=node.getLabeledControl();
                return usable(labeled)?labeled:null;
            }
            if(node instanceof AbstractText||node instanceof Select||"BUTTON".equalsIgnoreCase(node.tagName))
                return usable(node)?node:null;
        }
        return null;
    }

    static boolean editable(Element element) {
        return element instanceof AbstractText text&&text.canEditText()&&usable(element);
    }

    private static boolean usable(Element element) {
        return element!=null&&!element.isDisabled()&&AuiFocus.visible(element);
    }
}
