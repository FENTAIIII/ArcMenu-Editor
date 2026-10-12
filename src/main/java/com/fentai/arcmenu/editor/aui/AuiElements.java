package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.element.*;
import com.fentai.arcmenu.editor.aui.internal.init.Document;
import com.fentai.arcmenu.editor.aui.internal.init.Element;

import java.util.function.Function;

/** Registers the library's built-in DOM controls without a loader annotation scanner. */
final class AuiElements {
    private AuiElements() {}

    static void register() {
        register("A", A::new);
        register("BODY", Body::new);
        register("CANVAS", Canvas::new);
        register("DIV", Div::new);
        register("HEAD", Head::new);
        register("HTML", Html::new);
        register("IMG", Img::new);
        register("INPUT", Input::new);
        register("OPTION", Option::new);
        register("PATH", Path::new);
        register("PRE", Pre::new);
        register("SELECT", Select::new);
        register("SPAN", Span::new);
        register("SVG", Svg::new);
        register("TEXTAREA", TextArea::new);
    }

    private static void register(String tag, Function<Document, ? extends Element> factory) {
        Element.register(tag, (document, ignored) -> factory.apply(document));
    }
}
