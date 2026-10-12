package com.fentai.arcmenu.editor;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.KeyEvent;
import com.google.gson.JsonObject;

/** Native project previews with manager navigation, or a clean aperture for publication captures. */
public final class WorkspacePreviewScreen extends Screen {
    private final WorkspaceScreen manager;
    private final WorkspaceState.Project project;
    private final boolean audio;
    private Button back;
    private boolean restoring;
    WorkspacePreviewScreen(){super(Component.empty());manager=null;project=null;audio=false;}
    WorkspacePreviewScreen(WorkspaceScreen manager,WorkspaceState.Project project){
        super(EditorI18n.component("arcmenu_editor.projects.template_preview",project.id()));
        this.manager=manager;this.project=project;audio=EffectAudioPreview.INSTANCE.enabled();EffectAudioPreview.INSTANCE.enabled(false);
    }
    public WorkspaceScreen manager(){return manager;}
    public JsonObject metadata(){var json=new JsonObject();json.addProperty("type",project==null?"capture":project.type());json.addProperty("id",project==null?"":project.id());json.addProperty("restoring",restoring);return json;}
    @Override public boolean isPauseScreen(){return false;}
    @Override protected void init(){
        EditorViewport.verticalFov(70);EditorViewport.update(new EditorLayout.Rect(0,0,width,height));
        if(manager!=null){back=addRenderableWidget(Button.builder(EditorI18n.component("arcmenu_editor.projects.back_manager"),button->onClose()).bounds(12,Math.max(12,height-34),Math.min(210,Math.max(100,width-24)),22).build());back.active=!restoring;}
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float delta){}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float delta){
        EditorViewport.update(new EditorLayout.Rect(0,0,width,height));
        if(manager!=null){
            g.fill(8,8,Math.max(128,width-8),35,0xD8111827);
            EffectUi.text(g,font,EffectUi.fit(font,title.getString(),Math.max(100,width-36)),18,17,0xFFFFFFFF);
            super.extractRenderState(g,x,y,delta);return;
        }
        var progress=WorkspacePublication.progress();if(progress==null||!WorkspacePublication.overlayVisible())return;
        int left=12,right=Math.max(left+120,width-12),top=Math.max(8,height-57);
        g.fill(left,top,right,height-12,0xE8111827);
        EffectUi.text(g,font,EffectUi.fit(font,EditorI18n.text("arcmenu_editor.projects.capture_progress",progress.completed(),progress.total()),right-left-20),left+10,top+8,0xFFFFFFFF);
        EffectUi.text(g,font,EffectUi.fit(font,EditorI18n.text(progress.cancelling()?"arcmenu_editor.projects.working":"arcmenu_editor.projects.capture_cancel_hint"),right-left-20),left+10,top+23,0xFFBCCADF);
    }
    @Override public boolean keyPressed(KeyEvent event){if(event.isEscape()){onClose();return true;}return manager==null||super.keyPressed(event);}
    @Override public void onClose(){
        if(manager==null){WorkspacePublication.cancelCapture();return;}
        if(restoring)return;restoring=true;if(back!=null){back.active=false;back.setMessage(EditorI18n.component("arcmenu_editor.projects.working"));}
        WorkspaceClient.request("preview-restore",new JsonObject()).whenComplete((reply,error)->minecraft.execute(()->{
            EffectAudioPreview.INSTANCE.enabled(audio);
            if(minecraft.screen==this){manager.previewFinished(error);minecraft.setScreen(manager);}
        }));
    }
}
