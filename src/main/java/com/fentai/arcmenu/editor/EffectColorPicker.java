package com.fentai.arcmenu.editor;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import java.util.function.Consumer;
import org.lwjgl.glfw.GLFW;
import java.util.Locale;

/** RGBA and hexadecimal color editing; values are committed once on confirmation. */
final class EffectColorPicker extends Screen implements EffectWorkspaceChild {
    private final EffectEditorScreen parent;
    private final Consumer<String> commit;
    private final int original;
    private EditBox hex;
    private int color,left,top,w,slider;
    EffectColorPicker(EffectEditorScreen parent,String value,Consumer<String> commit) {super(EditorI18n.component("arcmenu_editor.workspace.color_picker"));this.parent=parent;this.commit=commit;color=parse(value);original=color;}
    static int parse(String value) {
        String s=value.trim().replace("#","");if(s.startsWith("0x"))s=s.substring(2);
        if(s.length()==6)s="FF"+s;if(s.length()!=8)throw new IllegalArgumentException("Color must use RRGGBB or AARRGGBB hexadecimal notation");
        return (int)Long.parseLong(s,16);
    }
    @Override public EffectEditorScreen workspace(){return parent;}
    @Override public boolean isPauseScreen(){return false;}
    @Override protected void init(){w=Math.min(340,width-24);left=(width-w)/2;top=Math.max(16,(height-210)/2);
        hex=new EditBox(font,left+12,top+32,w-24,20,title);hex.setMaxLength(9);updateHex();addRenderableWidget(hex);
        addRenderableWidget(Button.builder(EditorI18n.component("arcmenu_editor.effect.confirm"),button->{try{color=parse(hex.getValue());commit.accept(String.format(Locale.ROOT,"#%08X",color));onClose();}catch(IllegalArgumentException error){hex.setTextColor(0xFFFF8888);}}).bounds(left+w-170,top+176,76,20).build());
        addRenderableWidget(Button.builder(EditorI18n.component("arcmenu_editor.effect.cancel"),button->onClose()).bounds(left+w-88,top+176,76,20).build());
    }
    private void updateHex(){hex.setValue(String.format(Locale.ROOT,"#%08X",color));hex.setTextColor(EffectUi.TEXT);}
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float delta){}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){g.fill(0,0,width,height,0xE0101010);g.fill(left,top,left+w,top+204,EffectUi.PANEL);g.outline(left,top,w,204,EffectUi.BORDER);EffectUi.text(g,font,title.getString(),left+12,top+10,EffectUi.TEXT);
        for(int c=0;c<4;c++){int y=top+66+c*23,bit=c==3?24:16-c*8;EffectUi.mono(g,font,new String[]{"R","G","B","A"}[c],left+12,y+3,EffectUi.TEXT);
            for(int i=0;i<w-74;i++){int n=i*255/Math.max(1,w-75),v=(color&~(255<<bit))|(n<<bit);g.fill(left+36+i,y,left+37+i,y+12,0xFF000000|v);}
            int n=(color>>>bit)&255,x=left+36+n*(w-75)/255;g.outline(x-1,y-2,3,16,0xFFFFFFFF);EffectUi.mono(g,font,Integer.toString(n),left+w-31,y+3,EffectUi.MUTED);
        }
        g.fill(left+12,top+165,left+47,top+175,original);g.fill(left+47,top+165,left+82,top+175,color);super.extractRenderState(g,mx,my,delta);
    }
    private void slide(double x){int bit=slider==4?24:16-(slider-1)*8,n=(int)Math.round(Math.max(0,Math.min(1,(x-left-36)/(w-75)))*255);color=(color&~(255<<bit))|(n<<bit);updateHex();}
    @Override public boolean mouseClicked(MouseButtonEvent e,boolean twice){int row=(int)((e.y()-top-66)/23);if(e.button()==GLFW.GLFW_MOUSE_BUTTON_LEFT&&row>=0&&row<4&&e.y()>=top+66&&e.y()<top+66+row*23+13&&e.x()>=left+36&&e.x()<left+w-38){slider=row+1;slide(e.x());return true;}return super.mouseClicked(e,twice);}
    @Override public boolean mouseDragged(MouseButtonEvent e,double dx,double dy){if(slider!=0){slide(e.x());return true;}return super.mouseDragged(e,dx,dy);}
    @Override public boolean mouseReleased(MouseButtonEvent e){slider=0;return super.mouseReleased(e);}
    @Override public void onClose(){minecraft.setScreen(parent);}
}
