package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.element.Img;
import com.fentai.arcmenu.editor.aui.internal.element.Input;
import com.fentai.arcmenu.editor.aui.internal.element.Option;
import com.fentai.arcmenu.editor.aui.internal.element.Path;
import com.fentai.arcmenu.editor.aui.internal.element.Select;
import com.fentai.arcmenu.editor.aui.internal.element.Svg;
import com.fentai.arcmenu.editor.aui.internal.element.TextArea;
import com.fentai.arcmenu.editor.aui.internal.init.Document;
import com.fentai.arcmenu.editor.aui.internal.init.Element;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AuiElementsTest
{
    @Test
    void parsedControlsUseTheLibraryImplementationsWithoutAnAnnotationScanner()
    {
        AuiElements.register();
        var document = new Document("embedded-controls-test", false);
        assertInstanceOf(Input.class, Element.init(new Element(document, "INPUT")));
        assertInstanceOf(TextArea.class, Element.init(new Element(document, "TEXTAREA")));
        assertInstanceOf(Select.class, Element.init(new Element(document, "SELECT")));
        assertInstanceOf(Option.class, Element.init(new Element(document, "OPTION")));
        assertInstanceOf(Svg.class, Element.init(new Element(document, "SVG")));
        assertInstanceOf(Path.class, Element.init(new Element(document, "PATH")));
        assertInstanceOf(Img.class, Element.init(new Element(document, "IMG")));
    }
}
