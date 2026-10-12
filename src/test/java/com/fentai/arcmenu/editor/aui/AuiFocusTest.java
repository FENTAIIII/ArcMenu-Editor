package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.init.Document;
import com.fentai.arcmenu.editor.aui.internal.init.Element;
import com.fentai.arcmenu.editor.aui.internal.element.Body;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuiFocusTest {
    @Test void tabOrderSkipsHiddenDisabledControlsAndWrapsWithinTheModal() {
        AuiElements.register();
        var document=new Document("focus-test",false);
        var root=new Body(document);document.body=root;
        var search=append(root,"INPUT");var modal=append(root,"DIV");
        var first=append(modal,"INPUT");var hidden=append(modal,"INPUT");hidden.setAttribute("type","hidden");
        var disabled=append(modal,"BUTTON");disabled.setAttribute("disabled","");
        var hiddenContainer=append(modal,"DIV");hiddenContainer.setAttribute("style","display:none");append(hiddenContainer,"INPUT");
        var last=append(modal,"TEXTAREA");
        assertSame(first,AuiFocus.next(modal,search,false));
        assertSame(last,AuiFocus.next(modal,first,false));
        assertSame(first,AuiFocus.next(modal,last,false));
        assertSame(last,AuiFocus.next(modal,first,true));
        assertSame(first,AuiFocus.next(modal,last,true));
    }

    private static Element append(Element parent,String tag) {
        return parent.appendChild(new Element(parent.document,tag));
    }
}
