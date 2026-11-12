package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.element.A;
import com.fentai.arcmenu.editor.aui.internal.element.Body;
import com.fentai.arcmenu.editor.aui.internal.element.Canvas;
import com.fentai.arcmenu.editor.aui.internal.element.Div;
import com.fentai.arcmenu.editor.aui.internal.element.Head;
import com.fentai.arcmenu.editor.aui.internal.element.Html;
import com.fentai.arcmenu.editor.aui.internal.element.Img;
import com.fentai.arcmenu.editor.aui.internal.element.Input;
import com.fentai.arcmenu.editor.aui.internal.element.Option;
import com.fentai.arcmenu.editor.aui.internal.element.Path;
import com.fentai.arcmenu.editor.aui.internal.element.Pre;
import com.fentai.arcmenu.editor.aui.internal.element.Select;
import com.fentai.arcmenu.editor.aui.internal.element.Span;
import com.fentai.arcmenu.editor.aui.internal.element.Svg;
import com.fentai.arcmenu.editor.aui.internal.element.TextArea;
import com.fentai.arcmenu.editor.aui.internal.init.Document;
import com.fentai.arcmenu.editor.aui.internal.init.Element;

import java.util.function.Function;

/** Registers the library's built-in DOM controls without a loader annotation scanner. */
final class AuiElements
{
    private AuiElements()
    {}

    static void register()
    {
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

    private static void register(String tag, Function<Document, ? extends Element> factory)
    {
        Element.register(tag, (document, ignored) -> factory.apply(document));
    }
}
