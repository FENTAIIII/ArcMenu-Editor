package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.init.Element;
import com.fentai.arcmenu.editor.aui.internal.style.Interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Browser-style focus order for the host's one document and modal scope. */
final class AuiFocus {
    private AuiFocus() {}

    static Element next(Element scope, Element current, boolean reverse) {
        List<Element> controls=new ArrayList<>();collect(scope,controls);
        if(controls.isEmpty())return null;
        int index=controls.indexOf(current);
        if(index<0)return reverse?controls.getLast():controls.getFirst();
        return controls.get(Math.floorMod(index+(reverse?-1:1),controls.size()));
    }

    static boolean visible(Element element) {
        if(element==null||!Interaction.isDisplayed(element)||!Interaction.isVisible(element))return false;
        for(Element node=element;node!=null;node=node.parentElement)if(node.hasAttribute("hidden"))return false;
        return true;
    }

    private static void collect(Element element,List<Element> controls) {
        if(!visible(element))return;
        String tag=element.tagName==null?"":element.tagName.toUpperCase(Locale.ROOT);
        if(List.of("INPUT","TEXTAREA","SELECT","BUTTON").contains(tag)&&!element.isDisabled()
                &&!"true".equals(element.getAttribute("aria-disabled"))
                &&!(tag.equals("INPUT")&&"hidden".equalsIgnoreCase(element.getAttribute("type")))
                &&nonNegativeTabIndex(element))controls.add(element);
        for(Element child:element.children)collect(child,controls);
    }

    private static boolean nonNegativeTabIndex(Element element) {
        String value=element.getAttribute("tabindex");
        if(value==null)return true;
        try{return Integer.parseInt(value)>=0;}catch(NumberFormatException ignored){return true;}
    }
}
