package com.fentai.arcmenu.editor;

import com.google.gson.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import java.util.*;
import java.util.function.Function;
import java.util.concurrent.CompletableFuture;

/** Native, translated forms keep destructive confirmation separate from selection. */
public final class WorkspaceFormScreen extends Screen {
    private final WorkspaceScreen parent;
    private final String mode;
    private final List<WorkspaceState.Project> projects;
    private final Map<String,EditBox> fields=new LinkedHashMap<>();
    private final List<String> labels=new ArrayList<>();
    private final Map<String,String> values=new LinkedHashMap<>();
    private final Function<JsonObject,CompletableFuture<JsonObject>> submit;
    private String status="";
    private int left,top,panelWidth,rowHeight;
    private Button accept;
    private boolean busy;
    private MultiLineEditBox descriptionBox;
    private final MarketPreview coverPreview=new MarketPreview();
    private EditorLayout.Rect confirmBounds=new EditorLayout.Rect(0,0,0,0),cancelBounds=confirmBounds,coverBounds=confirmBounds;
    private int listingHeight,contentWidth;
    private long coverGeneration;
    private boolean coverRequested;
    private boolean compactListing;
    WorkspaceFormScreen(WorkspaceScreen parent,String mode,List<WorkspaceState.Project> projects,Function<JsonObject,CompletableFuture<JsonObject>> submit) {
        super(EditorI18n.component("arcmenu_editor.projects."+mode));this.parent=parent;this.mode=mode;this.projects=List.copyOf(projects);this.submit=submit;
        if(mode.equals("new_menu")||mode.equals("new_effect")) {values.put("id","");values.put("folder","");geometry(null);}
        else if(mode.equals("configure")) {geometry(projects.getFirst());values.put("permission",projects.getFirst().permission());}
        else if(mode.equals("export")) {values.put("id",projects.size()==1?projects.getFirst().id():"menu-pack");values.put("version","1.0.0");values.put("title",projects.size()==1?projects.getFirst().id():EditorI18n.text("arcmenu_editor.projects.default_pack_title"));values.put("description","");values.put("allowCe","false");}
        else if(mode.equals("edit_metadata")) {values.put("title",parent.marketTitle());values.put("description",parent.marketDescription());}
        else if(mode.equals("publish")) {var pack=parent.selectedLocalPackage();if(pack!=null){values.put("title",pack.title());values.put("description",pack.description());}}
        else if(mode.equals("import"))values.put("filename","");
    }
    private void geometry(WorkspaceState.Project p) {values.put("width",format(p==null?WorkspaceState.DEFAULT_WIDTH:p.width()));values.put("height",format(p==null?WorkspaceState.DEFAULT_HEIGHT:p.height()));values.put("pixelsPerBlock",format(p==null?WorkspaceState.DEFAULT_PIXELS_PER_BLOCK:p.pixelsPerBlock()));values.put("distance",format(p==null?WorkspaceState.DEFAULT_DISTANCE:p.distance()));values.put("depthMode",p==null?"perspective":p.depthMode());}
    private static String format(double v) {return v==Math.rint(v)?Long.toString((long)v):Double.toString(v);}
    @Override public boolean isPauseScreen(){return false;}
    @Override protected void init() {
        if(descriptionBox!=null)values.put("description",descriptionBox.getValue());
        fields.forEach((key,box)->values.put(key,box.getValue()));fields.clear();labels.clear();
        if(listingForm()){initListing();return;}
        panelWidth=Math.min(520,width-20);left=(width-panelWidth)/2;top=Math.max(8,(height-(values.size()*29+108))/2);rowHeight=Math.max(22,Math.min(29,(height-112)/Math.max(1,values.size())));
        int y=top+34;for(var entry:values.entrySet()){String key=entry.getKey();labels.add(key);var box=new EditBox(font,left+142,y,panelWidth-152,18,EditorI18n.component("arcmenu_editor.projects.field."+key));box.setMaxLength(key.equals("description")?3000:256);box.setValue(entry.getValue());box.setTooltip(Tooltip.create(EditorI18n.component("arcmenu_editor.projects.help."+key)));fields.put(key,box);addRenderableWidget(box);y+=rowHeight;}
        int buttonsY=Math.min(height-30,y+36);
        accept=addRenderableWidget(Button.builder(EditorI18n.component("arcmenu_editor.projects.confirm"),button->send()).bounds(left+panelWidth-200,buttonsY,92,20).build());
        addRenderableWidget(Button.builder(EditorI18n.component("arcmenu_editor.projects.back"),button->onClose()).bounds(left+panelWidth-100,buttonsY,92,20).build());
        if(!fields.isEmpty())setFocused(fields.values().iterator().next());accept.active=!busy;
    }
    private boolean listingForm(){return mode.equals("publish")||mode.equals("edit_metadata");}
    private void initListing(){
        panelWidth=Math.min(680,width-28);listingHeight=Math.min(376,height-24);left=(width-panelWidth)/2;top=(height-listingHeight)/2;
        compactListing=listingHeight<260;boolean columns=panelWidth>=530&&!compactListing;contentWidth=columns?panelWidth-268:panelWidth-40;
        var box=new EditBox(font,left+24,top+(compactListing?62:89),contentWidth-8,20,EditorI18n.component("arcmenu_editor.projects.field.title"));
        box.setMaxLength(160);box.setValue(values.getOrDefault("title",""));box.setBordered(false);box.setTextColor(0xFF203047);box.setTextShadow(false);box.addFormatter((text,index)->EffectUi.component(text).getVisualOrderText());fields.put("title",box);addRenderableWidget(box);
        int descriptionHeight=compactListing?Math.max(24,listingHeight-142):Math.max(42,listingHeight-207);
        descriptionBox=createDescription(left+20,top+(compactListing?100:140),contentWidth,descriptionHeight);descriptionBox.setCharacterLimit(3000);descriptionBox.setValue(values.getOrDefault("description",""));addRenderableWidget(descriptionBox);
        int buttonY=top+listingHeight-(compactListing?30:42),buttonHeight=compactListing?22:26;
        confirmBounds=new EditorLayout.Rect(left+panelWidth-160,buttonY,140,buttonHeight);cancelBounds=new EditorLayout.Rect(left+panelWidth-240,buttonY,70,buttonHeight);
        accept=Button.builder(EditorI18n.component("arcmenu_editor.projects.confirm"),button->send()).bounds(confirmBounds.x(),confirmBounds.y(),confirmBounds.width(),confirmBounds.height()).build();accept.active=!busy;setFocused(box);
        coverBounds=columns?new EditorLayout.Rect(left+panelWidth-226,top+81,202,114):new EditorLayout.Rect(0,0,0,0);
        if(!coverRequested){coverRequested=true;loadCover();}
    }
    private void loadCover(){
        CompletableFuture<byte[]> image=null;
        if(mode.equals("publish")){var pack=parent.selectedLocalPackage();if(pack!=null&&!pack.cover().isBlank()){var args=new JsonObject();args.addProperty("filename",pack.filename());args.addProperty("name",pack.cover());image=WorkspaceClient.request("pack-preview",args).thenApply(reply->Base64.getDecoder().decode(reply.get("data").getAsString()));}}
        else{String url=parent.marketCoverUrl();if(!url.isBlank())image=WorkspaceClient.MARKET.image(url);}
        if(image==null)return;long generation=++coverGeneration;image.whenComplete((bytes,error)->minecraft.execute(()->{if(generation!=coverGeneration)return;if(error!=null){status=WorkspaceClient.error(error);return;}try{coverPreview.load(bytes);}catch(Exception failure){status=WorkspaceClient.error(failure);}}));
    }
    private MultiLineEditBox createDescription(int x,int y,int w,int h){
        return MultiLineEditBox.builder().setX(x).setY(y).setTextColor(0xFF203047).setTextShadow(false).setShowBackground(false).setShowDecorations(false).setPlaceholder(EditorI18n.component("arcmenu_editor.market.description_hint")).build(font,w,h,EditorI18n.component("arcmenu_editor.projects.field.description"));
    }
    private void send() {
        if(busy)return;
        try {
            var json=new JsonObject();for(var entry:fields.entrySet()){String key=entry.getKey(),value=entry.getValue().getValue().strip();
                if(List.of("width","height","pixelsPerBlock","distance").contains(key)){double number;try{number=Double.parseDouble(value);}catch(NumberFormatException invalid){number=Double.NaN;}if(!Double.isFinite(number)||number<=0)throw new IllegalArgumentException(EditorI18n.text("arcmenu_editor.projects.positive",EditorI18n.text("arcmenu_editor.projects.field."+key)));json.addProperty(key,number);}
                else if(key.equals("depthMode"))json.addProperty(key,WorkspaceState.depthMode(value));
                else if(key.equals("allowCe")){if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException(EditorI18n.text("arcmenu_editor.projects.boolean"));json.addProperty(key,Boolean.parseBoolean(value));}
                else json.addProperty(key,value);
            }
            if(descriptionBox!=null)json.addProperty("description",descriptionBox.getValue().strip());
            if(mode.equals("export")||mode.equals("publish")||mode.equals("edit_metadata")){
                for(String key:List.of("title","description"))if(WorkspaceState.string(json,key,"").isBlank())throw new IllegalArgumentException(EditorI18n.text("arcmenu_editor.projects.required",EditorI18n.text("arcmenu_editor.projects.field."+key)));
            }
            if(mode.equals("new_menu")||mode.equals("new_effect"))json.addProperty("type",mode.equals("new_menu")?"menu":"effect");
            if(mode.equals("configure")){json.addProperty("type",projects.getFirst().type());json.addProperty("id",projects.getFirst().id());}
            if(mode.equals("delete")||mode.equals("export")){var refs=new JsonArray();projects.forEach(p->refs.add(p.reference()));json.add("projects",refs);json.addProperty("confirm",true);}
            if(mode.equals("discard"))json.addProperty("confirmDiscard",true);
            if(mode.equals("export")&&WorkspaceClient.MARKET.authenticated()){json.addProperty("authorUuid",WorkspaceClient.MARKET.userId());json.addProperty("authorName",WorkspaceClient.MARKET.userName());}
            busy=true;accept.active=false;status=EditorI18n.text("arcmenu_editor.projects.working");
            submit.apply(json).whenComplete((reply,error)->minecraft.execute(()->{busy=false;if(error!=null){status=WorkspaceClient.error(error);accept.active=true;}else{parent.completed(mode,reply);if(minecraft.screen==this)minecraft.setScreen(parent);}}));
        }catch(RuntimeException error){status=WorkspaceClient.error(error);}
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float delta){}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){
        if(listingForm()){renderListing(g,mx,my,delta);return;}
        g.fill(0,0,width,height,EffectUi.BACKGROUND);g.fill(left,top,left+panelWidth,Math.min(height-5,top+values.size()*rowHeight+110),EffectUi.PANEL);g.outline(left,top,panelWidth,Math.min(height-5-top,values.size()*rowHeight+110),EffectUi.BORDER);
        EffectUi.text(g,font,title.getString(),left+10,top+10,EffectUi.TEXT);
        int y=top+39;for(String key:labels){EffectUi.text(g,font,EffectUi.fit(font,EditorI18n.text("arcmenu_editor.projects.field."+key),126),left+10,y,EffectUi.TEXT);y+=rowHeight;}
        String hint=EditorI18n.text("arcmenu_editor.projects.hint."+mode);
        if(fields.containsKey("pixelsPerBlock")){var box=fields.get("pixelsPerBlock");if(mx>=box.getX()&&mx<box.getX()+box.getWidth()&&my>=box.getY()&&my<box.getY()+18)hint=EditorI18n.text("arcmenu_editor.projects.ppb_hint");}
        EffectUi.text(g,font,EffectUi.fit(font,hint,panelWidth-20),left+10,y+3,EffectUi.MUTED);
        EffectUi.text(g,font,EffectUi.fit(font,status,panelWidth-20),left+10,y+18,0xFFFFC857);super.extractRenderState(g,mx,my,delta);
    }
    private void renderListing(GuiGraphicsExtractor g,int mx,int my,float delta){
        int ink=0xFF203047,muted=0xFF718096,accent=0xFF5765DB,border=0xFFE1E6EF;
        g.fill(0,0,width,height,0xFFEEF1F7);g.fill(left+3,top+4,left+panelWidth+3,top+listingHeight+4,0xFFDCE2EC);g.fill(left,top,left+panelWidth,top+listingHeight,0xFFFFFFFF);
        EffectUi.text(g,font,EditorI18n.text(mode.equals("publish")?"arcmenu_editor.market.publish_title":"arcmenu_editor.projects.edit_metadata"),left+20,top+18,ink);
        if(!compactListing)EffectUi.text(g,font,EffectUi.fit(font,EditorI18n.text(mode.equals("publish")?"arcmenu_editor.market.publish_steps":"arcmenu_editor.market.edit_steps"),panelWidth-40),left+20,top+38,muted);int separator=top+(compactListing?38:60);g.fill(left+20,separator,left+panelWidth-20,separator+1,border);
        var titleBox=fields.get("title");EffectUi.text(g,font,EditorI18n.text("arcmenu_editor.projects.field.title"),left+20,titleBox.getY()-17,muted);g.fill(left+20,titleBox.getY()-5,left+20+contentWidth,titleBox.getY()+26,0xFFF7F9FD);g.outline(left+20,titleBox.getY()-5,contentWidth,31,border);
        EffectUi.text(g,font,EditorI18n.text("arcmenu_editor.projects.field.description"),left+20,descriptionBox.getY()-14,muted);g.fill(descriptionBox.getX(),descriptionBox.getY(),descriptionBox.getX()+descriptionBox.getWidth(),descriptionBox.getY()+descriptionBox.getHeight(),0xFFF7F9FD);g.outline(descriptionBox.getX(),descriptionBox.getY(),descriptionBox.getWidth(),descriptionBox.getHeight(),border);
        if(coverBounds.width()>0){g.fill(coverBounds.x(),coverBounds.y(),coverBounds.right(),coverBounds.bottom(),0xFFEAF0FA);coverPreview.draw(g,coverBounds);EffectUi.text(g,font,EditorI18n.text("arcmenu_editor.market.cover_label"),coverBounds.x(),top+66,muted);
            var information=new ArrayList<String>();var pack=mode.equals("publish")?parent.selectedLocalPackage():null;
            if(pack!=null){information.add(pack.id()+" · "+pack.version());information.add(EditorI18n.text("arcmenu_editor.market.package_projects",pack.projects().size()));information.add(EditorI18n.text("arcmenu_editor.market.package_size",String.format(Locale.ROOT,"%.1f",pack.size()/1024.0)));}
            else if(parent.marketListing()!=null){var listing=parent.marketListing();information.add(WorkspaceState.string(listing,"id","")+" · "+WorkspaceState.string(listing,"latestVersion",""));if(listing.has("projects"))information.add(EditorI18n.text("arcmenu_editor.market.package_projects",listing.getAsJsonArray("projects").size()));if(listing.has("author"))information.add(EditorI18n.text("arcmenu_editor.market.by_author",WorkspaceState.string(listing.getAsJsonObject("author"),"name","")));}
            int y=coverBounds.bottom()+12;for(String line:information){EffectUi.text(g,font,EffectUi.fit(font,line,coverBounds.width()),coverBounds.x(),y,muted);y+=17;}
        }
        int sy=compactListing?top+31:top+listingHeight-66;if(!compactListing||!status.isBlank())EffectUi.text(g,font,EffectUi.fit(font,status.isBlank()?EditorI18n.text(mode.equals("publish")?"arcmenu_editor.market.publish_review_hint":"arcmenu_editor.market.edit_review_hint"):status,panelWidth-40),left+20,sy,status.isBlank()?muted:0xFFB25936);
        g.fill(cancelBounds.x(),cancelBounds.y(),cancelBounds.right(),cancelBounds.bottom(),0xFFF0F3F9);g.fill(confirmBounds.x(),confirmBounds.y(),confirmBounds.right(),confirmBounds.bottom(),busy?0xFF9AA6D2:accent);
        EffectUi.text(g,font,EditorI18n.text("arcmenu_editor.projects.back"),cancelBounds.x()+12,cancelBounds.y()+8,ink);EffectUi.text(g,font,EditorI18n.text(busy?"arcmenu_editor.projects.working":mode.equals("publish")?"arcmenu_editor.market.publish_now":"arcmenu_editor.market.save_changes"),confirmBounds.x()+12,confirmBounds.y()+8,0xFFFFFFFF);super.extractRenderState(g,mx,my,delta);
    }
    private static boolean inside(EditorLayout.Rect r,double x,double y){return x>=r.x()&&x<r.right()&&y>=r.y()&&y<r.bottom();}
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){if(listingForm()&&event.button()==0){if(inside(confirmBounds,event.x(),event.y())){send();return true;}if(inside(cancelBounds,event.x(),event.y())){onClose();return true;}}return super.mouseClicked(event,doubleClick);}
    @Override public boolean keyPressed(KeyEvent event){if(event.isEscape()){onClose();return true;}if(event.isConfirmation()&&getFocused()!=descriptionBox){send();return true;}return super.keyPressed(event);}
    @Override public void removed(){coverGeneration++;coverPreview.close();super.removed();}
    @Override public void onClose(){if(!busy)minecraft.setScreen(parent);}
}
