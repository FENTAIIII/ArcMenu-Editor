package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.aui.internal.init.Element;
import com.fentai.arcmenu.editor.aui.internal.element.AbstractText;
import com.fentai.arcmenu.editor.aui.internal.layout.Box;
import com.fentai.arcmenu.editor.aui.internal.layout.Size;
import com.fentai.arcmenu.editor.aui.internal.style.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.PreeditEvent;

/** Enables SDL text entry and anchors the native IME overlay to the AUI field. */
final class AuiTextInput implements GuiEventListener,AutoCloseable {
    private Element owner;
    private boolean focused;
    private double[] bounds;
    private int caretX,caretY;
    private IMEPreeditOverlay preedit;

    void synchronize(Element element,double[] bounds) {
        Element next=focused&&AuiInputFocus.editable(element)?element:null;
        Minecraft client=Minecraft.getInstance();
        if(owner!=next) {
            if(owner!=null)client.onTextInputFocusChange(this,false);
            owner=next;preedit=null;
            if(owner!=null)client.onTextInputFocusChange(this,true);
        }
        this.bounds=next==null?null:bounds;
        if(this.bounds!=null) {
            updateCaret((AbstractText)next,bounds);
            client.textInputManager().setTextInputArea(caretX,caretY,caretX+1,caretY+client.font.lineHeight+1);
        }
    }

    private void updateCaret(AbstractText field,double[] bounds) {
        Box box=Box.of(field);Text text=Text.of(field);String value=field.getValue();
        int cursor=Math.clamp(field.getCursor(),0,value.length());
        double offsetX,offsetY;
        if(field.isMultiline()) {
            text.content=value;var wrapped=Text.wrap(text,box.innerSize().width());
            int line=0;int[] starts=wrapped.starts();
            for(int i=1;i<starts.length&&starts[i]<=cursor;i++)line=i;
            String content=wrapped.lines().get(line);
            int column=Math.clamp(cursor-starts[line],0,content.length());
            offsetX=Size.measureText(field,content.substring(0,column));offsetY=line*text.lineHeight;
        }else {
            offsetX=Size.measureText(field,value.substring(0,cursor));
            offsetY=(box.innerSize().height()-text.lineHeight)/2;
        }
        double sx=field.document.getViewportScaleX(),sy=field.document.getViewportScaleY();
        caretX=(int)Math.round(bounds[0]+(box.getBorderLeft()+box.getPaddingLeft()+offsetX-field.getScrollLeft())*sx);
        caretY=(int)Math.round(bounds[1]+(box.getBorderTop()+box.getPaddingTop()+offsetY-field.getScrollTop())*sy);
        caretX=Math.clamp(caretX,(int)bounds[0],(int)(bounds[0]+bounds[2]-1));
        caretY=Math.clamp(caretY,(int)bounds[1],(int)(bounds[1]+bounds[3]-1));
    }

    void render(GuiGraphicsExtractor graphics) {
        if(owner==null||bounds==null||preedit==null)return;
        preedit.updateInputPosition(caretX,caretY);
        graphics.setPreeditOverlay(preedit);
    }

    boolean enabled(){return owner!=null;}
    @Override public boolean preeditUpdated(PreeditEvent event) {
        if(owner==null)return false;
        Minecraft client=Minecraft.getInstance();
        preedit=event==null?null:new IMEPreeditOverlay(event,client.font,client.font.lineHeight+1);
        return true;
    }
    @Override public void setFocused(boolean value){focused=value;if(!value)close();}
    @Override public boolean isFocused(){return focused;}
    @Override public void close() {
        if(owner!=null)Minecraft.getInstance().onTextInputFocusChange(this,false);
        owner=null;bounds=null;preedit=null;focused=false;
    }
}
