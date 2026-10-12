package com.fentai.arcmenu.editor.aui;

import com.fentai.arcmenu.editor.EditorI18n;
import com.fentai.arcmenu.editor.aui.internal.event.Event;
import com.fentai.arcmenu.editor.aui.internal.event.KeyEvent;
import com.fentai.arcmenu.editor.aui.internal.event.MouseEvent;
import com.fentai.arcmenu.editor.aui.internal.init.Document;
import com.fentai.arcmenu.editor.aui.internal.init.Element;
import com.fentai.arcmenu.editor.aui.internal.layout.Position;
import com.fentai.arcmenu.editor.aui.internal.layout.Size;
import com.fentai.arcmenu.editor.aui.internal.parser.HTML;
import com.fentai.arcmenu.editor.aui.internal.render.Base;
import com.fentai.arcmenu.editor.aui.internal.render.Mask;
import com.fentai.arcmenu.editor.aui.internal.render.Operation;
import com.fentai.arcmenu.editor.aui.internal.render.Rect;
import com.fentai.arcmenu.editor.aui.internal.spi.*;
import com.fentai.arcmenu.editor.aui.internal.task.AbstractAsyncHandler;
import com.fentai.arcmenu.editor.aui.internal.task.FrameTaskScheduler;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import com.fentai.arcmenu.editor.aui.internal.element.AbstractText;
import com.fentai.arcmenu.editor.aui.internal.render.Drawer;
import com.fentai.arcmenu.editor.aui.internal.render.GlyphCache;
import static com.fentai.arcmenu.editor.aui.internal.resource.Font.registerFont;
import com.fentai.arcmenu.editor.aui.internal.resource.async.image.ImageAsyncHandler;
import com.fentai.arcmenu.editor.aui.internal.style.Text;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Consumer;

/** Owns one embedded ApricityUI document and a cached display surface. */
public final class ApricityWorkspaceView implements AutoCloseable {
    private static final AtomicLong IDS = new AtomicLong();
    private static final AuiSurfaceRenderer RENDERER = new AuiSurfaceRenderer();
    private static ApricityWorkspaceView active;
    private static boolean initialized;
    private final Map<String,byte[]> images = new HashMap<>();
    private final Map<String,String> values = new HashMap<>();
    private final Document document;
    private final BiConsumer<String,String> actions;
    private final AuiTextInput textInput=new AuiTextInput();
    private final AuiCaret.Blink caretBlink=new AuiCaret.Blink();
    private final AuiButtonKeys buttonKeys=new AuiButtonKeys();
    private DynamicTexture texture;
    private Identifier textureId;
    private long lastVisual = Long.MIN_VALUE;
    private long lastLanguage = Long.MIN_VALUE;
    private int warmup = 6;
    private int lastCaretPhase = -1;
    private int modifiers;
    private int lastActionModifiers;
    private Position mouse = new Position(-100,-100);
    private int x,y,width,height;
    private long paintNanos;
    private long paintCount;
    private boolean closed;

    public ApricityWorkspaceView(String resourceTemplate, BiConsumer<String,String> actions) {
        this.actions=actions;
        initialize();active=this;
        String path=resourceTemplate.replace("assets/arcmenu_editor/", "");
        try(InputStream stream=resource(path).orElseThrow()){
            HTML.putTemple(path,new String(stream.readAllBytes(),StandardCharsets.UTF_8));
        }catch(Exception error){throw new IllegalStateException("Cannot load embedded workspace template: "+path,error);}
        document=Document.create(path);
        if(document==null||document.body==null)throw new IllegalStateException("Cannot create embedded workspace document: "+path);
        document.body.addEventListener("click", this::clicked);
        document.body.addEventListener("mousedown", this::focusPointerControl);
        document.body.addEventListener("change", this::changed);
        document.body.addEventListener("input", this::changed);
    }

    private static void initialize(){
        if(initialized)return;initialized=true;
        AuiElements.register();
        AuiServices.setRender(RENDERER.service());
        AuiServices.setResources(new AuiResourceService(){
            public Optional<InputStream> openResource(String path){return resource(path);}
            public Map<String,String> listResourcePaths(String path,String suffix){return Map.of();}
            public TextureKey locationOf(String key){return TextureKey.of(key);}
            public TextureKey tryParseTextureKey(String key){return key==null?null:TextureKey.of(key);}
            public Object textureLocation(TextureKey key){return key.value();}
            public RenderHandle smoothRenderType(TextureKey key,boolean blur,boolean depth){return RenderHandle.of(key.value());}
        });
        AuiServices.setClient((AuiClientService)Proxy.newProxyInstance(AuiClientService.class.getClassLoader(),new Class<?>[]{AuiClientService.class},(proxy,method,raw)->{
            Object[] a=raw==null?new Object[0]:raw;Minecraft client=Minecraft.getInstance();
            var window=client==null?null:client.getWindow();
            return switch(method.getName()){
                case "getWindowSize" -> new Size(window==null?1280:window.getGuiScaledWidth(),window==null?800:window.getGuiScaledHeight());
                case "getMousePosition","getMousePositionDirectly","getMousePositionForWorldInteraction" -> active==null?Position.ZERO:active.mouse;
                case "getWindowWidth" -> window==null?1280d:(double)window.getWidth();
                case "getWindowHeight" -> window==null?800d:(double)window.getHeight();
                case "getScaledWidth" -> window==null?1280:window.getGuiScaledWidth();
                case "getScaledHeight" -> window==null?800:window.getGuiScaledHeight();
                case "getWindowHandle" -> 0L;
                case "isMouseGrabbed" -> false;
                case "isProduction" -> true;
                case "getGameDirectory" -> FabricLoader.getInstance().getGameDir();
                case "getConfigDirectory" -> FabricLoader.getInstance().getConfigDir();
                case "isKeyPressed" -> active!=null&&active.modifierDown((String)a[0]);
                case "getDefaultFontWidth" -> new Font(Font.SANS_SERIF,Font.PLAIN,16).getStringBounds((String)a[0],new FontRenderContext(null,true,true)).getBounds().width;
                case "drawDefaultFont" -> { RENDERER.defaultText(((PoseStack)a[0]).last().pose(),(Text)a[1],(String)a[2],(Position)a[3]);yield null; }
                default -> AuiSurfaceRenderer.defaultValue(method.getReturnType());
            };
        }));
        try(InputStream stream=resource("font/effect_inter.ttf").orElse(null)){
            if(stream!=null)registerFont("WorkspaceInter",stream);
        }catch(Exception ignored){}
    }

    private static Optional<InputStream> resource(String raw){
        if(raw==null||raw.isBlank())return Optional.empty();
        String path=raw.replace("assets/arcmenu_editor/","");
        if(path.startsWith("apricity/"))path=path.substring(9);
        if(active!=null&&active.images.containsKey(path))return Optional.of(new ByteArrayInputStream(active.images.get(path)));
        if(path.equals("global.css"))path="workspace/aui-global.css";
        if(path.equals("global.js"))return Optional.empty();
        InputStream stream=ApricityWorkspaceView.class.getClassLoader().getResourceAsStream("assets/arcmenu_editor/"+path);
        return Optional.ofNullable(stream);
    }

    public void resize(int cssWidth,int cssHeight){
        if(closed)return;activate();document.applyViewport(true);warmup=6;lastVisual=Long.MIN_VALUE;
    }
    public int cssWidth(){return document.getViewport().layoutWidth();}
    public int cssHeight(){return document.getViewport().layoutHeight();}
    public void setText(String id,String value){update(id,"text",value,element->element.setTextContent(value==null?"":value));}
    public void setHtml(String id,String html){update(id,"html",html,element->{
        var descendants=new HashSet<String>();for(Element child:document.getElements())if(child!=element&&element.contains(child)&&child.id!=null)descendants.add(child.id);
        values.keySet().removeIf(key->{int colon=key.indexOf(':');return colon>=0&&descendants.contains(key.substring(0,colon));});
        element.setInnerHTML(html==null?"":html);
    });}
    public void setAttribute(String id,String name,String value){if("value".equals(name)){setValue(id,value);return;}update(id,"attr:"+name,value,element->{if(value==null)element.removeAttribute(name);else element.setAttribute(name,value);});}
    public void setValue(String id,String value){
        if(closed)return;Element element=document.getElementById(id);if(element==null)return;
        String next=value==null?"":value;
        if(!Objects.equals(element.getValue(),next)){element.setValue(next);warmup=Math.max(warmup,2);}
    }
    private void update(String id,String key,String value,Consumer<Element> change){
        if(closed)return;Element element=document.getElementById(id);if(element==null)return;
        String cacheKey=id+":"+key;
        if(values.containsKey(cacheKey)&&Objects.equals(value,values.get(cacheKey)))return;values.put(cacheKey,value);change.accept(element);warmup=Math.max(warmup,2);
    }
    public String value(String id){Element element=document.getElementById(id);return element==null?"":element.getValue();}
    public void resetScroll(String id){
        if(closed)return;Element element=document.getElementById(id);if(element==null)return;
        try(Document.ContextScope ignored=Document.withContext(document)){
            element.setScrollLeft(0);element.setScrollTop(0);element.scrollLeft=0;element.scrollTop=0;
            document.markDirty(element,Drawer.RELAYOUT|Drawer.REPAINT|Drawer.REORDER);
            warmup=Math.max(warmup,2);
        }
    }
    public void focus(String id){Element element=document.getElementById(id);if(element!=null){buttonKeys.reset();element.focus();caretBlink.interact(System.currentTimeMillis());synchronizeTextInput();}}
    public GuiEventListener textInputListener(){return textInput;}
    public String focusedId(){Element element=document.getFocusedElement();return element==null?"":element.id==null?"":element.id;}
    public boolean inputFocused(){Element element=document.getFocusedElement();return element instanceof AbstractText;}
    public int lastActionModifiers(){return lastActionModifiers;}
    public String putImage(String key,byte[] bytes){String path="workspace/images/"+key.replaceAll("[^a-zA-Z0-9._-]","_")+".png";images.put(path,bytes.clone());return "/"+path;}

    public void tick(){if(closed)return;activate();AbstractAsyncHandler.tickAll();FrameTaskScheduler.tick();document.tickFrame();}
    public void render(GuiGraphicsExtractor graphics,int x,int y,int width,int height,double mx,double my,float partial){
        if(closed||width<=0||height<=0)return;activate();
        long language=EditorI18n.languageVersion();
        if(language!=lastLanguage){
            lastLanguage=language;document.markDirty(Drawer.RELAYOUT|Drawer.REPAINT|Drawer.REORDER);
            warmup=Math.max(warmup,2);lastVisual=Long.MIN_VALUE;
        }
        if(this.width!=width||this.height!=height){
            int oldCssWidth=cssWidth(),oldCssHeight=cssHeight();document.applyViewport(false);
            if(cssWidth()!=oldCssWidth||cssHeight()!=oldCssHeight)document.markDirty(Drawer.RELAYOUT|Drawer.REPAINT|Drawer.REORDER);
            warmup=6;lastVisual=Long.MIN_VALUE;
        }
        this.x=x;this.y=y;this.width=width;this.height=height;
        document.setViewportTransform(width/(double)cssWidth(),height/(double)cssHeight(),x,y);
        mouseMove(mx,my);
        AbstractAsyncHandler.tickAll();
        boolean moving=document.hasActiveMotion();
        int caretPhase=inputFocused()?caretBlink.phase(System.currentTimeMillis()):-1;
        if(caretPhase!=lastCaretPhase)document.markDirty(Drawer.REPAINT);
        if(texture==null||lastVisual!=document.getVisualVersion()||document.hasPendingVisualWork()||document.getInitialCommitSlice()!=null||moving||warmup>0||GlyphCache.hasPendingGlyphs()){
            long start=System.nanoTime();
            var window=Minecraft.getInstance().getWindow();double guiScale=window.getGuiScale();
            int physicalWidth=Math.max(1,(int)Math.round(width*guiScale)),physicalHeight=Math.max(1,(int)Math.round(height*guiScale));
            double pixelScale=physicalWidth/(double)cssWidth();
            RENDERER.begin(physicalWidth,physicalHeight,pixelScale);Mask.testScissorSink=RENDERER::clip;
            PoseStack pose=new PoseStack();pose.scale((float)pixelScale,(float)pixelScale,1);
            Mask.pushScissorScale(pixelScale,pose);
            try{Base.drawScreenDocument(pose,document);Base.commitDraws();}
            finally{Mask.popScissorScale();Mask.testScissorSink=null;RENDERER.end();}
            upload(RENDERER.image());lastVisual=document.getVisualVersion();lastCaretPhase=caretPhase;if(warmup>0)warmup--;
            paintNanos=System.nanoTime()-start;paintCount++;
        }
        if(textureId!=null){int imageWidth=texture.getPixels().getWidth(),imageHeight=texture.getPixels().getHeight();
            Identifier texture=textureId;var fit=new Box(x,y,width,height);var image=new Box(0,0,imageWidth,imageHeight);
            graphics.blit(RenderPipelines.GUI_TEXTURED, texture, fit.x(), fit.y(), 0f, 0f,
                    fit.width(), fit.height(), image.width(), image.height(), image.width(), image.height());
        }
        synchronizeTextInput();textInput.render(graphics);
    }
    private void upload(BufferedImage image){
        if(texture==null||texture.getPixels().getWidth()!=image.getWidth()||texture.getPixels().getHeight()!=image.getHeight()){
            if(textureId!=null)Minecraft.getInstance().getTextureManager().release(textureId);
            NativeImage nativeImage=new NativeImage(image.getWidth(),image.getHeight(),false);
            texture=new DynamicTexture(()->"ArcMenu marketplace preview",nativeImage);
            textureId=Identifier.fromNamespaceAndPath("arcmenu_editor","aui_workspace/"+IDS.incrementAndGet());Minecraft.getInstance().getTextureManager().register(textureId,texture);
        }
        NativeImage target=texture.getPixels();int[] pixels=image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());
        AuiPixels.upload(target,pixels);
        texture.upload();
    }
    public boolean mouseClick(double mx,double my,int button,int modifiers){if(closed)return false;buttonKeys.reset();this.modifiers=modifiers;mouseMove(mx,my);activate();boolean consumed=Operation.onMouseDown(translateMouse(button));caretBlink.interact(System.currentTimeMillis());synchronizeTextInput();return consumed;}
    public boolean mouseRelease(double mx,double my,int button,int modifiers){if(closed)return false;this.modifiers=modifiers;mouseMove(mx,my);activate();boolean consumed=Operation.onMouseUp(translateMouse(button));caretBlink.interact(System.currentTimeMillis());synchronizeTextInput();return consumed;}
    private static int translateMouse(int button){if(button==GLFW.GLFW_MOUSE_BUTTON_LEFT)return 0;if(button==GLFW.GLFW_MOUSE_BUTTON_RIGHT)return 1;if(button==GLFW.GLFW_MOUSE_BUTTON_MIDDLE)return 2;return button;}
    public void mouseMove(double mx,double my){if(closed)return;activate();mouse=new Position(mx,my);Operation.onMouseMove(mouse);}
    public boolean scroll(double mx,double my,double horizontal,double vertical){if(closed)return false;mouseMove(mx,my);return Operation.scroll(vertical);}
    public boolean key(int key,int scan,int modifiers){
        if(closed)return false;this.modifiers=modifiers;activate();caretBlink.interact(System.currentTimeMillis());warmup=Math.max(warmup,2);int translated=translateKey(key);
        if(translated==258){
            try(Document.ContextScope ignored=Document.withContext(document)){
                document.commitStyleRecalc();Element modal=document.getElementById("modal_host");
                Element next=AuiFocus.next(AuiFocus.visible(modal)?modal:document.body,document.getFocusedElement(),(modifiers&1)!=0);
                buttonKeys.tabFocused(next);if(next!=null)next.focus();
            }
            synchronizeTextInput();
            return true;
        }
        if(buttonKeys.route(translated,document.getFocusedElement(),this::activateFocusedButton))return true;
        boolean consumed=Operation.onKeyPressed(translated,scan,modifiers,false,KeyEvent.Source.SCREEN_EVENT);synchronizeTextInput();return consumed;
    }
    public boolean activateFocusedButton(){
        Element focused=document.getFocusedElement();
        if(!AuiButtonKeys.button(focused))return false;
        focused.click();warmup=Math.max(warmup,2);return true;
    }
    public void keyReleased(int key,int scan,int modifiers){if(closed)return;this.modifiers=modifiers;activate();Operation.onKeyReleased(translateKey(key),scan,modifiers,KeyEvent.Source.SCREEN_EVENT);}
    public boolean character(int codePoint,int modifiers){if(closed)return false;this.modifiers=modifiers;activate();caretBlink.interact(System.currentTimeMillis());warmup=Math.max(warmup,2);return Operation.onCharTyped(codePoint);}
    private boolean modifierDown(String key){return key.contains("control")?(modifiers&2)!=0:key.contains("shift")?(modifiers&1)!=0:key.contains("alt")?(modifiers&4)!=0:false;}
    private void activate(){active=this;AuiCaret.bind(caretBlink);}
    private void focusPointerControl(Event event){
        if(event instanceof MouseEvent pointer&&pointer.button!=0)return;
        Element control=AuiInputFocus.control(event.target instanceof Element element?element:null);
        if(control!=null)control.focus();
    }
    private void synchronizeTextInput(){
        Element focused=document.getFocusedElement();
        textInput.synchronize(focused,focused!=null&&focused.id!=null&&AuiInputFocus.editable(focused)?bounds(focused.id):null);
    }
    private void clicked(Event event){
        if(event instanceof MouseEvent mouseEvent)lastActionModifiers=(mouseEvent.controlKey?2:0)|(mouseEvent.shiftKey?1:0)|(mouseEvent.altKey?4:0);else lastActionModifiers=modifiers;
        Element element=AuiActions.target(event.target instanceof Element e?e:null);
        if(element!=null){String id=element.getAttribute("data-id");actions.accept(element.getAttribute("data-action"),id==null?"":id);}
    }
    private void changed(Event event){if(event.target instanceof Element element&&element.id!=null)actions.accept("input",element.id);}
    public double[] bounds(String id){
        Element element=document.getElementById(id);if(element==null)return null;
        try(Document.ContextScope ignored=Document.withContext(document)){
            document.commitStyleRecalc();document.commitRenderState();var bounds=Rect.of(element).getVisualBounds();
            Position p=document.documentToScreenPosition(new Position(bounds.x(),bounds.y()));
            return new double[]{p.x,p.y,bounds.width()*document.getViewportScaleX(),bounds.height()*document.getViewportScaleY()};
        }
    }
    public Map<String,Object> metadata(){Map<String,Object> data=new LinkedHashMap<>();data.put("engine","ApricityUI");data.put("cssWidth",cssWidth());data.put("cssHeight",cssHeight());data.put("paintCount",paintCount);data.put("lastPaintMillis",paintNanos/1_000_000.0);data.put("focusedId",focusedId());data.put("nativeTextInput",textInput.enabled());return data;}
    public static String escape(String text){return text==null?"":text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static int translateKey(int key){return AuiKeyCodes.translate(key);}
    @Override public void close(){if(closed)return;closed=true;textInput.close();document.remove();ImageAsyncHandler.INSTANCE.clearAndBumpGeneration();images.clear();values.clear();if(textureId!=null)Minecraft.getInstance().getTextureManager().release(textureId);texture=null;textureId=null;if(active==this)active=null;}
    private record Box(int x,int y,int width,int height){}
}
