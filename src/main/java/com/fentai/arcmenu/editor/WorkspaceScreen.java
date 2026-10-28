package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.editor.aui.ApricityWorkspaceView;
import com.google.gson.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.BiConsumer;

/** An embedded AUI workspace with persistent navigation and scoped asynchronous operations. */
public final class WorkspaceScreen extends Screen {
    private final Screen parent;
    private ApricityWorkspaceView view;
    private String tab, status="", searchValue="", marketSort="popular", projectType="all", selectedLocalFilename="", selectedInstalled="", lastExportFilename="";
    private List<WorkspaceState.Row> rows=List.of();
    private List<JsonObject> marketItems=List.of();
    private JsonObject detail;
    private String detailTab="overview";
    private int page=1,pages=1,previewIndex,managerPage;
    private long detailGeneration,catalogGeneration,searchDeadline;
    private boolean marketLoaded,closed,paintRequested,localRefreshQueued;
    private String localRefreshStatus;
    private final Set<String> operations=new HashSet<>();
    private final Map<String,String> fragments=new HashMap<>(),marketCovers=new HashMap<>(),localCovers=new HashMap<>();
    private final Set<String> requestedImages=new HashSet<>();
    private WorkspaceModal modal;
    private Function<JsonObject,CompletableFuture<JsonObject>> modalSubmit;
    private String lastAuth="";
    private long languageVersion=-1;
    private final MarketPreview legacyPreview=new MarketPreview();

    WorkspaceScreen(Screen parent,boolean market){super(EditorI18n.component("arcmenu_editor.projects.title"));this.parent=parent instanceof WorkspaceScreen workspace?workspace.returnScreen():parent;tab=market?"market":"projects";}
    public Screen returnScreen(){return parent;}
    public void navigate(boolean market){switchTab(market?"market":"projects");}
    @Override public boolean isPauseScreen(){return false;}
    @Override protected void init(){
        boolean opening=view==null;
        if(opening){view=new ApricityWorkspaceView("assets/arcmenu_editor/workspace/workspace.html",this::action);fragments.clear();}
        setFocused(view.textInputListener());
        view.resize(width,height);
        paint();
        if(opening)refresh();
    }
    private static String studio(String key,Object... values){return EditorI18n.text("arcmenu_editor.studio."+key,values);}
    private static String project(String key,Object... values){return EditorI18n.text("arcmenu_editor.projects."+key,values);}
    private static String market(String key,Object... values){return EditorI18n.text("arcmenu_editor.market."+key,values);}
    private static String e(String text){return WorkspaceMarkup.escape(text);}
    private String authenticationLabel(){
        return market(switch(WorkspaceClient.authStatus()){
            case "signed_in"->"signed_in";case "signing_in"->"signing_in";
            case "failed"->"sign_in_failed";case "unavailable"->"unavailable_title";
            default->"account_required";
        });
    }
    private String authenticationProgress(){
        String stage=WorkspaceClient.authStage();
        return List.of("configuring","challenge","session","verify","retry").contains(stage)?market("stage."+stage):market("signing_in");
    }
    private void html(String id,String markup){if(id.equals("inspector"))markup=markup.replace("id='control_","id='inspector_control_");if(view!=null&&!Objects.equals(fragments.put(id,markup),markup))view.setHtml(id,markup);}
    private void text(String id,String value){String key="text:"+id;if(view!=null&&!Objects.equals(fragments.put(key,value),value))view.setText(id,value);}
    private void attr(String id,String name,String value){String key="attr:"+id+":"+name;boolean changed=!Objects.equals(fragments.put(key,value),value);if(view!=null&&(changed||name.equals("value")))view.setAttribute(id,name,value);}
    private String button(String action,String label,boolean disabled,String classes){return WorkspaceMarkup.iconButton(action,"",label,action,disabled,classes);}
    private String button(String action,boolean disabled){return button(action,project(action),disabled,"button button-secondary");}
    private List<WorkspaceState.LocalPack> localPackages(){String query=searchValue.toLowerCase(Locale.ROOT);return WorkspaceClient.STATE.localPacks().stream().filter(p->(p.filename()+" "+p.title()+" "+p.id()).toLowerCase(Locale.ROOT).contains(query)).toList();}
    private List<WorkspaceState.Pack> installedPackages(){String query=searchValue.toLowerCase(Locale.ROOT);return WorkspaceClient.STATE.packs().stream().filter(p->(p.id()+" "+p.title()).toLowerCase(Locale.ROOT).contains(query)).toList();}
    public WorkspaceState.LocalPack selectedLocalPackage(){return WorkspaceClient.STATE.localPacks().stream().filter(pack->pack.filename().equals(selectedLocalFilename)).findFirst().orElse(null);}
    public void selectLocalPackage(String filename){selectedLocalFilename=filename;switchTab("local_packages");paint();}
    public void selectionChanged(){updateRows();paint();}
    private void updateRows(){rows=WorkspaceClient.STATE.rows(searchValue,projectType);}
    private void searchChanged(String value){
        if(Objects.equals(value,searchValue))return;
        searchValue=value;page=1;managerPage=0;catalogGeneration++;
        searchDeadline=tab.equals("market")?System.nanoTime()+350_000_000L:0;
        updateRows();paint();
    }
    private void switchTab(String next){
        if(next.equals("market")&&!WorkspaceClient.STATE.marketAllowed()){status=market("operator_required");paint();return;}
        if(modal!=null){if(modal.busy)return;saveFields();modal=null;modalSubmit=null;}
        tab=next;searchValue="";searchDeadline=0;managerPage=0;attr("search","value","");detail=null;detailGeneration++;updateRows();paint();
        if(view!=null)view.resetScroll("content_scroll");
        refresh();
    }
    private <T> void run(String key,Supplier<CompletableFuture<T>> work,Consumer<T> done){
        if(!operations.add(key))return;status=project("working");paint();
        try{work.get().whenComplete((reply,error)->minecraft.execute(()->{operations.remove(key);if(closed)return;status=error==null?project("done"):WorkspaceClient.error(error);if(error==null)done.accept(reply);paint();}));}
        catch(RuntimeException error){operations.remove(key);status=WorkspaceClient.error(error);paint();}
    }
    void refresh(){run("catalog",WorkspaceClient::refresh,json->{updateRows();if(tab.equals("market")&&WorkspaceClient.MARKET.authenticated())refreshMarket();else automaticAuthentication();});}
    private void automaticAuthentication(){
        if(!WorkspaceClient.STATE.marketAllowed())return;
        WorkspaceClient.ensureAuthentication().whenComplete((json,error)->minecraft.execute(()->{if(closed)return;if(error!=null&&tab.equals("market"))status=WorkspaceClient.error(error);paint();if(error==null&&tab.equals("market"))refreshMarket();}));
    }
    private void refreshMarket(){
        if(!WorkspaceClient.STATE.marketAllowed())return;
        if(!WorkspaceClient.MARKET.authenticated()){automaticAuthentication();paint();return;}
        long generation=++catalogGeneration;String query=searchValue,sort=marketSort;int requestedPage=page;
        String key="market:"+generation;
        run(key,()->WorkspaceClient.MARKET.request("GET","/v1/packs?q="+MarketClient.path(query)+"&page="+requestedPage+"&sort="+sort,null,true),json->{
            if(generation!=catalogGeneration)return;var values=new ArrayList<JsonObject>();String field=json.has("packs")?"packs":"items";
            if(json.has(field))json.getAsJsonArray(field).forEach(item->values.add(item.getAsJsonObject()));marketItems=List.copyOf(values);marketLoaded=true;
            pages=Math.max(1,(int)Math.ceil(WorkspaceState.number(json,"total",values.size())/20));
        });
    }
    private boolean pending(String key){return operations.contains(key);}
    private boolean marketPending(){return operations.stream().anyMatch(key->key.startsWith("market:"));}
    private void action(String action,String id){
        try{
            if(action.startsWith("nav_")){switchTab(action.substring(4));return;}
            switch(action){
                case "input"->{if(modal==null&&id.equals("search"))searchChanged(view.value(id));else if(modal!=null&&id.startsWith("field_"))modal.values.put(id.substring(6),view.value(id));}
                case "select_project","toggle_project","open_project"->{var p=WorkspaceClient.STATE.projects().stream().filter(item->item.key().equals(id)).findFirst().orElse(null);if(p!=null){int mods=view.lastActionModifiers();boolean toggle=action.equals("toggle_project");WorkspaceClient.STATE.select(p,toggle||(mods&2)!=0,!toggle&&(mods&1)!=0,rows);paint();if(action.equals("open_project"))perform("open");}}
                case "toggle_folder"->{WorkspaceClient.STATE.toggleFolder(id);updateRows();paint();}
                case "select_package"->{selectedLocalFilename=id;paint();}
                case "select_installed"->{selectedInstalled=id;paint();}
                case "open_market"->selectMarketResource(id);
                case "type_all","type_menu","type_effect","type_template"->{projectType=action.substring(5);managerPage=0;updateRows();paint();}
                case "sort_popular","sort_downloads","sort_likes","sort_rating","sort_updated"->{marketSort=action.substring(5);page=1;refreshMarket();}
                case "detail_overview","detail_gallery","detail_versions"->{detailTab=action.substring(7);paint();}
                case "preview_select"->{previewIndex=Integer.parseInt(id);paint();}
                case "install_version"->installVersion(id);
                case "rate_stars"->rate(Integer.parseInt(id));
                default->perform(action);
            }
        }catch(RuntimeException failure){status=WorkspaceClient.error(failure);paint();}
    }
    private boolean enabled(String action){
        if(!WorkspaceClient.STATE.marketAllowed()&&List.of("nav_market","login","publish","install","like","rate","edit_metadata","hide_listing").contains(action))return false;
        if ((action.equals("new_effect") || action.equals("type_effect")) && !WorkspaceClient.STATE.effectsSupported()) return false;
        int selected=WorkspaceClient.STATE.selection().size();var pack=selectedLocalPackage();
        return switch(action){
            case "open"->selected==1&&!pending("open");
            case "configure"->selected==1&&WorkspaceClient.STATE.selection().getFirst().configurable()&&!pending("open");
            case "export","delete"->selected>0;
            case "prepare"->pack!=null&&!pack.prepared()&&!pending("prepare");
            case "publish"->pack!=null&&WorkspaceClient.MARKET.authenticated()&&!pending("publish");
            case "delete_package"->pack!=null;
            case "uninstall"->!selectedInstalled.isBlank();
            case "install","like","rate"->detail!=null&&WorkspaceClient.MARKET.authenticated()&&!pending(action);
            case "edit_metadata","hide_listing"->owned();
            case "previous"->page>1;case "next"->page<pages;
            default->true;
        };
    }
    public boolean command(String action){
        if(!enabled(action))return false;
        if(action.equals("escape")){onClose();return true;}
        if(action.startsWith("nav_")||action.startsWith("detail_")||action.startsWith("sort_")||action.startsWith("type_")){action(action,"");return true;}
        if(action.equals("modal_confirm")){if(modal==null||modal.busy)return false;submitModal();return true;}
        if(action.equals("modal_cancel")){if(modal==null||modal.busy)return false;modal=null;modalSubmit=null;paint();return true;}
        if(modal!=null)return false;if(!enabled(action))return false;perform(action);return true;
    }
    public boolean input(String field,String value){
        if(modal!=null){String key=field.startsWith("field_")?field.substring(6):field;if(modal.busy||!modal.values.containsKey(key))return false;modal.values.put(key,value);view.setValue("field_"+key,value);return true;}
        if(field.equals("search")){attr("search","value",value);searchChanged(value);return true;}
        if(field.equals("sort")&&List.of("popular","downloads","likes","rating","updated").contains(value)){marketSort=value;page=1;refreshMarket();return true;}return false;
    }
    private void perform(String action){
        var selected=WorkspaceClient.STATE.selection();var pack=selectedLocalPackage();if(!enabled(action))return;
        switch(action){
            case "close"->{if(modal!=null&&modal.busy||pending("prepare")||pending("publish")||pending("install")||pending("open")){status=studio("finish_operation");paint();}else exit();}
            case "escape"->onClose();
            case "refresh"->refresh();
            case "login"->run("login",WorkspaceClient::login,json->{if(tab.equals("market"))refreshMarket();});
            case "search"->{searchValue=view.value("search");page=1;managerPage=0;searchDeadline=0;if(tab.equals("market"))refreshMarket();else{updateRows();paint();}}
            case "search_clear"->{searchValue="";attr("search","value","");page=1;managerPage=0;searchDeadline=0;updateRows();if(tab.equals("market"))refreshMarket();paint();}
            case "new_menu","new_effect","configure","delete","export","import"->form(action,action.equals("new_menu")||action.equals("new_effect")||action.equals("import")?List.of():selected,json->{
                if(action.equals("export")&&WorkspaceClient.MARKET.authenticated()){json.addProperty("authorUuid",WorkspaceClient.MARKET.userId());json.addProperty("authorName",WorkspaceClient.MARKET.userName());}
                return WorkspaceClient.request(action.startsWith("new_")?"create":action,json);
            });
            case "open"->{var p=selected.getFirst();if(!p.previewOnly()&&WorkspaceClient.dirty())form("discard",selected,json->WorkspaceClient.openProject(p,true));else run("open",()->WorkspaceClient.openProject(p,false),json->{});}
            case "select_all"->{WorkspaceClient.STATE.selectAll(rows);paint();}
            case "list_previous"->{managerPage=Math.max(0,managerPage-1);paint();}
            case "list_next"->{managerPage++;paint();}
            case "prepare"->run("prepare",()->WorkspacePublication.prepare(this,pack),json->refreshLocal());
            case "cancel_capture"->WorkspaceClient.cancelCapture();
            case "publish"->{if(pack!=null)form("publish",List.of(),json->{json.addProperty("filename",pack.filename());return WorkspaceClient.request("pack-configure",json).thenCompose(reply->WorkspaceClient.refresh()).thenCompose(reply->WorkspacePublication.publish(this,selectedLocalPackage()));});}
            case "delete_package"->{if(pack!=null)form(action,List.of(),json->{var names=new JsonArray();names.add(pack.filename());json.add("filenames",names);json.addProperty("confirm",true);return WorkspaceClient.request("pack-delete",json);});}
            case "uninstall"->form(action,List.of(),json->{json.addProperty("id",selectedInstalled);return WorkspaceClient.request("uninstall",json);});
            case "installed_projects"->{String id=selectedInstalled;switchTab("projects");input("search","arcpack-"+id);}
            case "previous","next"->{page+=action.equals("next")?1:-1;refreshMarket();}
            case "browse"->{detail=null;detailGeneration++;paint();}
            case "install"->installVersion(WorkspaceState.string(detail,"latestVersion",""));
            case "like"->like();
            case "rate"->form("rate",List.of(),json->CompletableFuture.completedFuture(json));
            case "edit_metadata"->form(action,List.of(),json->WorkspaceClient.MARKET.request("PUT","/v1/packs/"+MarketClient.path(WorkspaceState.string(detail,"id","")),json,true));
            case "hide_listing"->form(action,List.of(),json->WorkspaceClient.MARKET.request("DELETE","/v1/packs/"+MarketClient.path(WorkspaceState.string(detail,"id","")),null,true));
            case "modal_confirm"->submitModal();
            case "modal_cancel"->{if(modal!=null&&!modal.busy){modal=null;modalSubmit=null;paint();}}
            default->throw new IllegalArgumentException("Unknown workspace command: "+action);
        }
    }
    private void form(String mode,List<WorkspaceState.Project> projects,Function<JsonObject,CompletableFuture<JsonObject>> submit){modal=new WorkspaceModal(mode,projects,selectedLocalPackage(),detail);modalSubmit=submit;paint();if(!modal.values.isEmpty())view.focus("field_"+modal.values.keySet().iterator().next());}
    private void saveFields(){if(modal!=null&&view!=null)for(String key:modal.values.keySet())modal.values.put(key,view.value("field_"+key));}
    private void submitModal(){
        if(modal==null||modal.busy)return;saveFields();var current=modal;
        try{var args=current.parameters(current.values);current.busy=true;current.error="";paint();modalSubmit.apply(args).whenComplete((reply,error)->minecraft.execute(()->{
            current.busy=false;if(closed)return;if(error!=null){current.error=WorkspaceClient.error(error);if(modal==current)paintModal();paint();return;}
            if(modal==current){modal=null;modalSubmit=null;}completed(current.mode,reply);
        }));}catch(RuntimeException error){current.error=WorkspaceClient.error(error);paintModal();}
    }
    void completed(String mode,JsonObject reply){
        status=project("done");
        if(mode.equals("export")){
            lastExportFilename=WorkspaceState.string(reply,"filename","");
            modal=null;modalSubmit=null;selectedLocalFilename="";tab="local_packages";
            searchValue="";searchDeadline=0;managerPage=0;detail=null;detailGeneration++;
            attr("search","value","");
            if(view!=null)view.resetScroll("content_scroll");
            status=project("exported",lastExportFilename);
            if(reply.has("warnings")&&!reply.getAsJsonArray("warnings").isEmpty())status+=" · "+reply.get("warnings");
            refreshLocal(status);
        }
        else if(mode.equals("edit_metadata")){detail=reply;paint();}
        else if(mode.equals("hide_listing")){detail=null;marketLoaded=false;refreshMarket();}
        else if(mode.equals("discard"))paint();
        else{if(reply.has("resourcePending")&&reply.get("resourcePending").getAsBoolean())status+=" · "+project("resources_pending");refreshLocal();}
        paint();
    }
    private void refreshLocal(){refreshLocal(null);}
    private void refreshLocal(String completionStatus){
        if(pending("catalog")){localRefreshQueued=true;if(completionStatus!=null)localRefreshStatus=completionStatus;return;}
        run("catalog",WorkspaceClient::refresh,json->{updateRows();localCovers.clear();requestedImages.removeIf(key->key.startsWith("local:"));if(completionStatus!=null)status=completionStatus;});
    }
    String marketTitle(){return detail==null?"":WorkspaceState.string(detail,"title","");}
    String marketDescription(){return detail==null?"":WorkspaceState.string(detail,"description","");}
    JsonObject marketListing(){return detail;}
    String marketCoverUrl(){return detail==null?"":coverUrl(detail);}
    MarketPreview selectedLocalCover(){return legacyPreview;}
    private static String coverUrl(JsonObject json){if(json.has("cover")&&json.get("cover").isJsonObject())return WorkspaceState.string(json.getAsJsonObject("cover"),"url","");if(json.has("previews")&&!json.getAsJsonArray("previews").isEmpty())return WorkspaceState.string(json.getAsJsonArray("previews").get(0).getAsJsonObject(),"url","");return "";}
    public boolean selectMarketResource(String id){
        if(!tab.equals("market")||!WorkspaceClient.MARKET.authenticated())return false;
        var cached=marketItems.stream().filter(pack->WorkspaceState.string(pack,"id","").equals(id)).findFirst().orElse(null);if(cached==null)return false;
        detail=cached;detailTab="overview";previewIndex=0;long generation=++detailGeneration;paint();
        run("detail:"+generation,()->WorkspaceClient.MARKET.request("GET","/v1/packs/"+MarketClient.path(id),null,true),json->{if(generation==detailGeneration&&tab.equals("market")){detail=json;previewIndex=coverIndex(json);}});return true;
    }
    private static int coverIndex(JsonObject json){if(!json.has("cover")||!json.get("cover").isJsonObject()||!json.has("previews"))return 0;String name=WorkspaceState.string(json.getAsJsonObject("cover"),"name","");var values=json.getAsJsonArray("previews");for(int i=0;i<values.size();i++)if(WorkspaceState.string(values.get(i).getAsJsonObject(),"name","").equals(name))return i;return 0;}
    private boolean owned(){return detail!=null&&WorkspaceClient.MARKET.authenticated()&&detail.has("author")&&WorkspaceState.string(detail.getAsJsonObject("author"),"uuid","").replace("-","").equalsIgnoreCase(WorkspaceClient.MARKET.userId().replace("-",""));}
    private void installVersion(String version){if(detail==null||pending("install"))return;var args=new JsonObject();args.addProperty("id",WorkspaceState.string(detail,"id",""));args.addProperty("version",version);args.addProperty("token",WorkspaceClient.MARKET.token());run("install",()->WorkspaceClient.request("install",args),json->{switchTab("installed");refreshLocal();});}
    private void like(){var target=detail;var args=new JsonObject();args.addProperty("liked",!target.has("myLike")||!target.get("myLike").getAsBoolean());run("like",()->WorkspaceClient.MARKET.request("POST","/v1/packs/"+MarketClient.path(WorkspaceState.string(target,"id",""))+"/likes",args,true),json->{target.add("likeCount",json.get("likeCount"));target.add("myLike",json.get("myLike"));});}
    private void rate(int stars){if(detail==null||stars<1||stars>5)return;var target=detail;var args=new JsonObject();args.addProperty("stars",stars);run("rate",()->WorkspaceClient.MARKET.request("POST","/v1/packs/"+MarketClient.path(WorkspaceState.string(target,"id",""))+"/ratings",args,true),json->{target.add("ratingAverage",json.get("ratingAverage"));target.add("ratingCount",json.get("ratingCount"));target.addProperty("myRating",stars);modal=null;});}

    private void paint(){
        if(view==null||closed)return;updateRows();
        for(String name:List.of("projects","local_packages","installed","market")){text("nav_"+name+"_label",studio("nav."+name));attr("nav_"+name,"class","nav-item"+(name.equals(tab)?" active":""));}
        if(!WorkspaceClient.STATE.marketAllowed())attr("nav_market","class","nav-item hidden");
        text("navigation_label",studio("navigation"));text("folders_label",studio("folders"));text("refresh_label",project("refresh"));text("close_label",studio(parent instanceof EditorScreen||parent instanceof EffectEditorScreen?"return_editor":"close_workspace"));
        text("projects_count",Integer.toString(WorkspaceClient.STATE.projects().size()));text("local_packages_count",Integer.toString(WorkspaceClient.STATE.localPacks().size()));text("installed_count",Integer.toString(WorkspaceClient.STATE.packs().size()));
        text("page_title",studio("page."+tab+".title"));text("page_subtitle",studio("page."+tab+".hint"));
        attr("search","placeholder",studio("search."+tab));attr("search","maxlength",tab.equals("market")?"128":null);attr("search_clear","class","search-clear"+(searchValue.isEmpty()?" hidden":""));
        boolean authed=WorkspaceClient.MARKET.authenticated(),authPending=WorkspaceClient.authStatus().equals("signing_in");
        String authError=MarketMessages.authentication(WorkspaceClient.authError());
        text("account_name",authed?WorkspaceClient.MARKET.userName():studio("account.minecraft"));
        text("account_status",authPending?authenticationProgress():authenticationLabel());
        attr("account_status","title",authError.isBlank()?market("nonblocking_hint"):authError);
        attr("account_indicator","class","account-indicator"+(authed?" verified":authPending?" pending":WorkspaceClient.authStatus().equals("failed")?" failed":""));
        text("account_error",authError);attr("account_error","class","account-error"+(authed||authPending||authError.isBlank()?" hidden":""));
        attr("account_retry","class","account-retry"+(authed||authPending?" hidden":""));text("account_retry",market("retry_login"));
        text("status_text",status.isBlank()?studio("ready"):status);attr("status_indicator","class","status-indicator"+(!operations.isEmpty()?" pending":""));text("status_detail",studio("escape_hint"));
        StringBuilder folders=new StringBuilder();if(tab.equals("projects"))for(var row:WorkspaceClient.STATE.rows("",projectType))if(row.folder())folders.append("<button class='folder-item' data-action='toggle_folder' data-id='").append(e(row.key())).append("' style='padding-left:").append(12+row.depth()*10).append("px'>").append(WorkspaceMarkup.icon("folder")).append("<span class='folder-name'>").append(e(WorkspaceMarkup.folderLabel(row))).append("</span></button>");html("folder_tree",folders.toString());attr("folders_label","class","sidebar-caption folders-caption"+(!tab.equals("projects")?" hidden":""));
        attr("inspector","class","inspector"+(tab.equals("market")&&detail==null?" hidden":""));
        attr("searchbar","class","searchbar"+(detail!=null?" hidden":""));attr("toolbar","class","toolbar"+(detail!=null?" hidden":""));
        attr("body_layout","class","body-layout"+(detail!=null?" detail-mode":""));
        switch(tab){case "projects"->paintProjects();case "local_packages"->paintPackages();case "installed"->paintInstalled();case "market"->paintMarket();default->throw new IllegalStateException("Unknown workspace tab: "+tab);}
        paintModal();
    }
    private void paintProjects(){
        var selected=WorkspaceClient.STATE.selection();StringBuilder toolbar=new StringBuilder();toolbar.append(button("new_menu",project("new_menu"),false,"button button-primary"));if(WorkspaceClient.STATE.effectsSupported())toolbar.append(button("new_effect",false));toolbar.append("<span class='toolbar-spacer'></span>");
        for(String action:List.of("open","configure","export","delete"))toolbar.append(button(action,project(action.equals("open")&&selected.size()==1&&selected.getFirst().previewOnly()?"preview":action),!enabled(action),action.equals("delete")?"button button-danger":"button button-secondary"));html("toolbar",toolbar.toString());
        StringBuilder filters=new StringBuilder();for(String type:WorkspaceClient.STATE.effectsSupported()?List.of("all","menu","effect","template"):List.of("all","menu","template"))filters.append(WorkspaceMarkup.button("type_"+type,"",studio("filter."+type),false,"filter-pill"+(projectType.equals(type)?" active":"")));html("filter_controls",filters.toString());
        int totalPages=Math.max(1,(rows.size()+24)/25);managerPage=Math.min(managerPage,totalPages-1);int first=managerPage*25;
        String content=rows.isEmpty()?WorkspaceMarkup.empty(studio("empty.projects.title"),studio("empty.projects.hint"),"new_menu",project("new_menu")):WorkspaceMarkup.projects(rows.subList(first,Math.min(rows.size(),first+25)),selected);
        if(totalPages>1)content+="<div class='pagination'>"+button("list_previous",studio("market.previous"),managerPage==0,"button button-secondary")+"<span class='pagination-label'>"+studio("market.page",managerPage+1,totalPages)+"</span>"+button("list_next",studio("market.next"),managerPage==totalPages-1,"button button-secondary")+"</div>";html("content",content);
        StringBuilder inspect=new StringBuilder();if(selected.isEmpty())inspect.append(WorkspaceMarkup.inspectorEmpty(studio("selection.none"),studio("selection.hint")));
        else{inspect.append("<div class='inspector-label'>").append(e(studio("selection.count",selected.size()))).append("</div>");if(selected.size()==1){var p=selected.getFirst();inspect.append(WorkspaceMarkup.projectDetails(p));}else for(var p:selected)inspect.append(WorkspaceMarkup.property(studio("type."+p.type()),p.id()));inspect.append("<div class='inspector-actions'>");if(selected.size()==1&&selected.getFirst().previewOnly())inspect.append(button("open",project("preview"),!enabled("open"),"button button-primary button-wide"));inspect.append(button("export",project("export"),false,"button button-primary button-wide")).append("</div>");}html("inspector",inspect.toString());
    }
    private void paintPackages(){
        var pack=selectedLocalPackage();html("filter_controls","");html("toolbar","<span class='toolbar-label'>"+e(studio("pack.workflow"))+"</span><span class='toolbar-spacer'></span>"+button("import",false)+button("prepare",!enabled("prepare"))+button("publish",project("publish"),!enabled("publish"),"button button-primary")+button("delete_package",project("delete_package"),!enabled("delete_package"),"button button-danger"));
        var packs=localPackages();html("content",packs.isEmpty()?WorkspaceMarkup.empty(studio("empty.packages.title"),studio("empty.packages.hint"),"nav_projects",studio("nav.projects")):WorkspaceMarkup.packs(packs,selectedLocalFilename,localCovers));
        for(var item:packs)requestLocalCover(item);
        if(pack==null){html("inspector",WorkspaceMarkup.inspectorEmpty(studio("pack.select"),studio("pack.select_hint")));return;}
        String image=localCovers.get(pack.filename());String inspect="<div class='inspector-label'>"+e(studio("pack.details"))+"</div><h2>"+e(pack.title())+"</h2><p>"+e(pack.description())+"</p>"+(image==null?"":"<img class='inspector-cover' src='"+e(image)+"'>")+WorkspaceMarkup.property(project("field.filename"),pack.filename())+WorkspaceMarkup.property(project("field.version"),pack.version())+WorkspaceMarkup.property(studio("pack.contents"),Integer.toString(pack.projects().size()))+WorkspaceMarkup.property(studio("pack.size"),bytes(pack.size()))+"<span class='pill "+(pack.prepared()?"pill-green":"pill-amber")+"'>"+e(studio(pack.prepared()?"pack.ready":"pack.needs_preview"))+"</span><div class='inspector-actions'>"+button("publish",project("publish"),!enabled("publish"),"button button-primary button-wide")+button("prepare",!enabled("prepare"))+"</div>";html("inspector",inspect);
    }
    private void paintInstalled(){
        html("filter_controls","");html("toolbar","<span class='toolbar-label'>"+e(studio("installed.hint"))+"</span><span class='toolbar-spacer'></span>"+button("installed_projects",studio("installed.open"),selectedInstalled.isBlank(),"button button-secondary")+button("uninstall",project("uninstall"),!enabled("uninstall"),"button button-danger"));
        var packs=installedPackages();html("content",packs.isEmpty()?WorkspaceMarkup.empty(studio("empty.installed.title"),studio("empty.installed.hint"),"nav_market",studio("nav.market")):WorkspaceMarkup.installed(packs,selectedInstalled));
        var selected=packs.stream().filter(p->p.id().equals(selectedInstalled)).findFirst().orElse(null);html("inspector",selected==null?WorkspaceMarkup.inspectorEmpty(studio("installed.select"),studio("installed.select_hint")):"<div class='inspector-label'>"+e(studio("installed"))+"</div><h2>"+e(selected.title())+"</h2>"+WorkspaceMarkup.property(project("field.id"),selected.id())+WorkspaceMarkup.property(project("field.version"),selected.version())+"<p>"+e(studio("installed.removal_hint"))+"</p><div class='inspector-actions'>"+button("installed_projects",studio("installed.open"),false,"button button-primary button-wide")+button("uninstall",project("uninstall"),false,"button button-danger")+"</div>");
    }
    private void paintMarket(){
        if(detail!=null){paintDetail();return;}
        StringBuilder filters=new StringBuilder();for(String sort:List.of("popular","downloads","updated"))filters.append(WorkspaceMarkup.button("sort_"+sort,"",market("sort."+sort),false,"filter-pill"+(marketSort.equals(sort)?" active":"")));html("filter_controls",filters.toString());
        html("toolbar","<span class='toolbar-label'>"+e(studio("market.results",marketItems.size()))+"</span><span class='toolbar-spacer'></span>"+(pages>1?button("previous",!enabled("previous"))+"<span class='toolbar-label'>"+page+" / "+pages+"</span>"+button("next",!enabled("next")):""));
        String content="";
        if(!WorkspaceClient.MARKET.authenticated()){
            boolean authPending=WorkspaceClient.authStatus().equals("signing_in");
            String hint=authPending?authenticationProgress()+" · "+market("nonblocking_hint"):
                    WorkspaceClient.authStatus().equals("unavailable")?market("unavailable_hint"):market("nonblocking_hint");
            content+=WorkspaceMarkup.authentication(authenticationLabel(),hint,authPending?"":MarketMessages.authentication(WorkspaceClient.authError()),authPending,market("retry_login"));
        }
        else if(marketItems.isEmpty())content+=marketPending()?WorkspaceMarkup.skeleton(6):WorkspaceMarkup.empty(market("empty_title"),market("empty_hint"),"refresh",project("refresh"));
        else content+=WorkspaceMarkup.market(marketItems,marketCovers);html("content",content);html("inspector","");for(var item:marketItems)requestMarketImage(WorkspaceState.string(item,"id",""),coverUrl(item));
    }
    private void paintDetail(){
        String id=WorkspaceState.string(detail,"id","");String source=marketCovers.get(id);var previews=detail.has("previews")?detail.getAsJsonArray("previews"):new JsonArray();
        if(!previews.isEmpty()){previewIndex=Math.floorMod(previewIndex,previews.size());String key="detail:"+id+":"+previewIndex;source=marketCovers.getOrDefault(key,source);requestMarketImage(key,WorkspaceState.string(previews.get(previewIndex).getAsJsonObject(),"url",""));}
        String author=detail.has("author")?WorkspaceState.string(detail.getAsJsonObject("author"),"name",""):"";
        StringBuilder content=new StringBuilder("<div class='detail-page'>");content.append(button("browse",market("browse_back"),false,"button button-ghost button-small")).append("<div class='detail-heading'><h2>").append(e(marketTitle())).append("</h2><p>").append(e(market("by_author",author))).append(" · ").append(e(WorkspaceState.string(detail,"latestVersion",""))).append("</p></div>");content.append(WorkspaceMarkup.cover(source,"detail-cover","image"));
        content.append("<div class='detail-tabs'>");for(String value:List.of("overview","gallery","versions"))content.append(WorkspaceMarkup.button("detail_"+value,"",market(value),false,"filter-pill"+(detailTab.equals(value)?" active":"")));content.append("</div>");
        if(detailTab.equals("gallery")){
            int first=previewIndex/12*12,last=Math.min(previews.size(),first+12);
            content.append("<div class='gallery-grid'>");
            for(int i=first;i<last;i++){
                var image=previews.get(i).getAsJsonObject();String imageKey="gallery:"+id+":"+i;
                requestMarketImage(imageKey,WorkspaceState.string(image,"url",""));
                String thumbnail=marketCovers.get(imageKey);
                content.append("<button class='gallery-card").append(i==previewIndex?" active":"")
                        .append("' id='control_preview_select_").append(i).append("' data-action='preview_select' data-id='").append(i).append("'>")
                        .append(thumbnail==null?"<div class='gallery-placeholder'>"+WorkspaceMarkup.icon("image")+"</div>":"<img class='gallery-image' src='"+e(thumbnail)+"'>")
                        .append("<span class='gallery-caption'>").append(e(WorkspaceState.string(image,"name",""))).append("</span></button>");
            }
            content.append("</div>");
            if(previews.size()>12){content.append("<div class='pagination'>");if(first>0)content.append(WorkspaceMarkup.button("preview_select",Integer.toString(first-1),studio("market.previous"),false,"button button-small"));content.append("<span class='pagination-label'>").append(e(studio("market.page",first/12+1,(previews.size()+11)/12))).append("</span>");if(last<previews.size())content.append(WorkspaceMarkup.button("preview_select",Integer.toString(last),studio("market.next"),false,"button button-small"));content.append("</div>");}
        }
        else if(detailTab.equals("versions")){if(detail.has("versions"))for(var value:detail.getAsJsonArray("versions")){var version=value.getAsJsonObject();String name=WorkspaceState.string(version,"version","");content.append("<div class='version-row'><div class='version-name'><strong>").append(e(name)).append("</strong><p>").append(e(bytes((long)WorkspaceState.number(version,"size",0)))).append("</p></div>").append(WorkspaceMarkup.button("install_version",name,market("install"),pending("install"),"button button-primary")).append("</div>");}}
        else{content.append("<div class='detail-description'>").append(e(marketDescription())).append("</div>");if(detail.has("projects")){content.append("<h3>").append(e(market("package_contents",detail.getAsJsonArray("projects").size()))).append("</h3>");for(var value:detail.getAsJsonArray("projects")){var p=value.getAsJsonObject();content.append(WorkspaceMarkup.property(studio("type."+WorkspaceState.string(p,"type","menu")),WorkspaceState.string(p,"id","")));}}for(String key:List.of("compatibility","dependencies"))if(detail.has(key))content.append(WorkspaceMarkup.property(market(key),detail.get(key).toString()));}content.append("</div>");html("content",content.toString());
        String inspect="<div class='inspector-label'>"+e(studio("market.resource"))+"</div><span class='pill pill-green'>"+e(market("free"))+"</span><div class='inspector-actions'>"+button("install",market("install"),!enabled("install"),"button button-primary button-wide")+button("like",market(detail.has("myLike")&&detail.get("myLike").getAsBoolean()?"unlike":"like")+" · "+WorkspaceMarkup.compact(WorkspaceState.number(detail,"likeCount",0)),!enabled("like"),"button button-secondary button-wide")+button("rate",market("rate"),!enabled("rate"),"button button-secondary button-wide")+"</div><div class='inspector-divider'></div>"+WorkspaceMarkup.property(studio("market.author"),author)+WorkspaceMarkup.property(studio("market.downloads"),WorkspaceMarkup.compact(WorkspaceState.number(detail,"downloads",0)))+WorkspaceMarkup.property(studio("market.rating"),String.format(Locale.ROOT,"%.1f",WorkspaceState.number(detail,"ratingAverage",0))+" / 5")+WorkspaceMarkup.property(project("field.filename"),id+".arcpack");if(owned())inspect+="<div class='inspector-actions'>"+button("edit_metadata",market("edit"),false,"button button-secondary button-wide")+button("hide_listing",market("hide"),false,"button button-danger button-wide")+"</div>";html("inspector",inspect);
    }
    private void requestMarketImage(String key,String url){
        if(url.isBlank()||marketCovers.containsKey(key)||!requestedImages.add("remote:"+key+":"+url))return;
        WorkspaceClient.MARKET.image(url).whenComplete((bytes,error)->minecraft.execute(()->{if(closed||error!=null||view==null)return;marketCovers.put(key,view.putImage("remote:"+key,bytes));paintRequested=true;}));
    }
    private void requestLocalCover(WorkspaceState.LocalPack pack){
        if(pack.cover().isBlank()||localCovers.containsKey(pack.filename())||!requestedImages.add("local:"+pack.filename()+":"+pack.modifiedAt()))return;
        var args=new JsonObject();args.addProperty("filename",pack.filename());args.addProperty("name",pack.cover());args.addProperty("width",360);
        WorkspaceClient.request("pack-preview",args).whenComplete((reply,error)->minecraft.execute(()->{if(closed||error!=null||view==null)return;localCovers.put(pack.filename(),view.putImage("local:"+pack.filename(),Base64.getDecoder().decode(reply.get("data").getAsString())));paintRequested=true;}));
    }
    private void paintModal(){
        if(view==null)return;
        if(modal==null){html("modal_host","");attr("modal_host","class","modal-host hidden");fragments.remove("modal.instance");return;}
        attr("modal_host","class","modal-host");
        boolean listing=modal.mode.equals("publish")||modal.mode.equals("edit_metadata");
        int viewportHeight=Math.max(240,view.cssHeight());
        int preferredHeight=listing?720:modal.mode.equals("export")?780:modal.mode.equals("rate")?330:
                modal.mode.equals("import")?410:modal.values.isEmpty()?(modal.projects.size()>4?600:450):640;
        int modalHeight=Math.min(preferredHeight,viewportHeight-60);
        int headerHeight=viewportHeight<590?86:96,footerHeight=viewportHeight<590?66:78;
        int bodyHeight=Math.max(64,modalHeight-headerHeight-footerHeight);
        String modalStyle="position:relative;height:"+modalHeight+"px;max-height:none;box-shadow:none;";
        String headerStyle="height:"+headerHeight+"px;flex:0 0 "+headerHeight+"px;";
        String bodyStyle="position:absolute;left:0;top:"+headerHeight+"px;width:100%;height:"+bodyHeight+"px;max-height:none;flex:none;box-sizing:border-box;overflow-y:auto;";
        String footerStyle="position:absolute;left:0;top:"+(modalHeight-footerHeight)+"px;width:100%;height:"+footerHeight+"px;box-sizing:border-box;";
        String instance=modal.mode+":"+System.identityHashCode(modal);
        boolean mounting=!instance.equals(fragments.get("modal.instance"));
        if(mounting){
            fragments.put("modal.instance",instance);
            for(String id:List.of("modal_review","modal_notice","modal_footer_actions"))fragments.remove(id);
            StringBuilder value=new StringBuilder("<div class='modal-backdrop'><div id='studio_modal' class='modal").append(listing?" wide":"")
                    .append("' style='").append(modalStyle).append("'><div id='modal_header' class='modal-header' style='").append(headerStyle).append("'><div><span class='modal-eyebrow'>").append(e(studio("modal.eyebrow")))
                    .append("</span><h2>").append(e(modal.mode.equals("rate")?market("rate"):project(modal.mode)))
                    .append("</h2></div>").append(WorkspaceMarkup.button("modal_cancel","header",studio("cancel"),modal.busy,"button button-ghost button-small"))
                    .append("</div><div id='modal_body' class='modal-body' style='").append(bodyStyle).append("'><p class='modal-hint'>")
                    .append(e(project("hint."+modal.mode))).append("</p><div class='").append(listing?"publish-layout":"modal-fields-layout")
                    .append("'><div class='publish-fields'>");
            if(modal.mode.equals("rate")){
                value.append("<div class='rating-buttons'>");
                for(int stars=1;stars<=5;stars++)value.append(WorkspaceMarkup.button("rate_stars",Integer.toString(stars),"★".repeat(stars),pending("rate"),"button button-secondary"));
                value.append("</div>");
            }else{
                value.append("<div class='form-grid'>");
                for(var field:modal.values.entrySet()){
                    String key=field.getKey(),type=key.equals("description")?"textarea":List.of("width","height","pixelsPerBlock","distance").contains(key)?"number":"text";
                    if(key.equals("allowCe"))type="select-ce";
                    value.append(WorkspaceMarkup.field("field_"+key,project("field."+key),field.getValue(),project("help."+key),type,List.of("id","filename","title","description").contains(key)));
                }
                value.append("</div>");
            }
            int count=0;
            for(var p:modal.projects){if(count++>=12)break;value.append("<div class='confirmation-resource'>").append(WorkspaceMarkup.icon(p.type())).append("<span>").append(e(p.id())).append("</span></div>");}
            if(modal.projects.size()>12)value.append("<p class='modal-hint'>").append(e(studio("selection.count",modal.projects.size()))).append("</p>");
            value.append("</div>");if(listing)value.append("<aside id='modal_review' class='publish-review'></aside>");
            value.append("</div><div id='modal_notice'></div></div><div id='modal_footer_actions' class='modal-footer' style='").append(footerStyle).append("'></div></div></div>");
            String markup=value.toString();
            remountFields(modal.values,()->html("modal_host",markup),(key,initial)->view.setValue("field_"+key,initial));
        }
        attr("studio_modal","style",modalStyle);
        attr("modal_header","style",headerStyle);
        attr("modal_body","style",bodyStyle);
        attr("modal_footer_actions","style",footerStyle);
        attr("modal_body","class","modal-body"+(modal.busy?" in-flight":""));
        attr("control_modal_cancel_header","disabled",modal.busy?"":null);
        if(modal.mode.equals("publish")||modal.mode.equals("edit_metadata"))html("modal_review",publicationReview());
        String notices=modal.error.isBlank()?"":"<div class='form-error'>"+e(modal.error)+"</div>";
        if(modal.busy)notices+="<div class='form-notice'>"+e(project("working"))+"</div>";
        html("modal_notice",notices);
        String actions="<span>"+e(studio("modal.escape"))+"</span>"+button("modal_cancel",studio("cancel"),modal.busy,"button button-secondary");
        if(!modal.mode.equals("rate"))actions+=button("modal_confirm",modal.busy?project("working"):modal.mode.equals("publish")?market("publish_now"):project("confirm"),modal.busy,"button "+(List.of("delete","delete_package","uninstall","hide_listing","discard").contains(modal.mode)?"button-danger":"button-primary"));
        html("modal_footer_actions",actions);
    }
    static void remountFields(Map<String,String> fields,Runnable mount,BiConsumer<String,String> restore){
        var preserved=new LinkedHashMap<>(fields);
        // Removing a focused input can dispatch change after its ID is unregistered.
        // Keep that teardown callback from replacing the saved draft with an empty value.
        try{mount.run();}finally{fields.clear();fields.putAll(preserved);}
        preserved.forEach(restore);
    }
    private String publicationReview(){
        String image=null;StringBuilder information=new StringBuilder();
        if(modal.mode.equals("publish")){
            var pack=selectedLocalPackage();
            if(pack!=null){requestLocalCover(pack);image=localCovers.get(pack.filename());information.append(WorkspaceMarkup.property(project("field.filename"),pack.filename()))
                    .append(WorkspaceMarkup.property(project("field.version"),pack.version()))
                    .append(WorkspaceMarkup.property(studio("pack.contents"),Integer.toString(pack.projects().size())))
                    .append(WorkspaceMarkup.property(studio("pack.size"),bytes(pack.size())))
                    .append("<span class='pill ").append(pack.prepared()?"pill-green":"pill-amber").append("'>")
                    .append(e(studio(pack.prepared()?"pack.ready":"pack.needs_preview"))).append("</span>");}
        }else if(detail!=null){
            String id=WorkspaceState.string(detail,"id","");String key="review:"+id;
            requestMarketImage(key,coverUrl(detail));image=marketCovers.getOrDefault(key,marketCovers.get(id));
            information.append(WorkspaceMarkup.property(project("field.filename"),id+".arcpack"))
                    .append(WorkspaceMarkup.property(project("field.version"),WorkspaceState.string(detail,"latestVersion","")));
            if(detail.has("projects"))information.append(WorkspaceMarkup.property(studio("pack.contents"),Integer.toString(detail.getAsJsonArray("projects").size())));
        }
        String cover=image==null?"<div class='publish-review-placeholder'>"+WorkspaceMarkup.icon("image")+"</div>":"<img class='publish-review-cover' src='"+e(image)+"'>";
        return cover+"<div class='publish-review-body'><h3>"+e(studio("form.package_review"))+"</h3>"+information
                +"<p class='review-hint'>"+e(studio("form.cover_hint"))+"</p></div>";
    }
    public JsonObject metadata(){
        var json=new JsonObject();var gson=new Gson();json.addProperty("renderer","embedded-apricityui");if(view!=null)json.add("view",gson.toJsonTree(view.metadata()));json.addProperty("tab",tab);json.addProperty("status",status);json.addProperty("busy",!operations.isEmpty()||modal!=null&&modal.busy);json.addProperty("navigationEnabled",true);json.add("operations",gson.toJsonTree(operations));json.addProperty("exportFilename",lastExportFilename);json.addProperty("selectedLocalFilename",selectedLocalFilename);json.add("selectedLocalPackage",gson.toJsonTree(selectedLocalPackage()));json.add("localPackages",gson.toJsonTree(localPackages()));json.add("rows",gson.toJsonTree(rows));json.add("selection",WorkspaceClient.STATE.references());json.add("marketItems",gson.toJsonTree(marketItems));json.add("detail",detail);json.addProperty("detailTab",detailTab);json.addProperty("search",searchValue);json.add("capture",WorkspaceClient.captureProgress());
        json.addProperty("projectType",projectType);
        var authentication=new JsonObject();authentication.addProperty("status",WorkspaceClient.authStatus());authentication.addProperty("stage",WorkspaceClient.authStage());authentication.addProperty("error",WorkspaceClient.authError());json.add("authentication",authentication);
        if(modal!=null){saveFields();var form=new JsonObject();form.addProperty("mode",modal.mode);form.add("fields",gson.toJsonTree(modal.values));form.addProperty("busy",modal.busy);form.addProperty("error",modal.error);json.add("modal",form);}else json.add("modal",JsonNull.INSTANCE);
        var controls=new JsonArray();
        if(view!=null){
            var ids=new ArrayList<>(List.of("nav_projects","nav_local_packages","nav_installed","nav_market","action_close","action_refresh","search","toolbar","content","inspector","modal_host","control_type_all","control_type_menu","control_type_effect","control_type_template","control_modal_confirm","control_modal_cancel","control_new_menu","control_new_effect","control_open","control_configure","control_export","control_delete","control_prepare","control_publish","control_import","control_delete_package","control_uninstall","control_browse","control_install","control_like","control_rate","control_edit_metadata","control_hide_listing","field_id","field_title","field_description","field_pixelsPerBlock","account_status","account_error","account_retry","authentication_title","authentication_hint","authentication_error","control_login"));
            if(modal!=null)for(String key:modal.values.keySet())if(!ids.contains("field_"+key))ids.add("field_"+key);
            for(var row:rows)if(!row.folder()){ids.add("resource_"+row.key());ids.add("checkbox_"+row.key());}
            for(var pack:localPackages())ids.add("resource_"+pack.filename());
            for(String id:ids){
                var c=new JsonObject();c.addProperty("id",id);double[] bounds=view.bounds(id);
                if(bounds==null&&id.startsWith("control_"))bounds=view.bounds("inspector_"+id);
                c.add("bounds",gson.toJsonTree(bounds));controls.add(c);
            }
        }
        json.add("controls",controls);return json;
    }
    @Override public void tick(){
        if(view==null)return;refreshLanguage();view.tick();if(paintRequested){paintRequested=false;paint();}String auth=WorkspaceClient.authStatus()+":"+WorkspaceClient.authStage()+":"+WorkspaceClient.authError()+":"+WorkspaceClient.MARKET.userName();if(!auth.equals(lastAuth)){lastAuth=auth;paint();}
        if(localRefreshQueued&&!pending("catalog")){localRefreshQueued=false;String completionStatus=localRefreshStatus;localRefreshStatus=null;refreshLocal(completionStatus);}
        if(modal==null)searchChanged(view.value("search"));
        if(searchDeadline!=0&&System.nanoTime()>=searchDeadline){searchDeadline=0;if(tab.equals("market"))refreshMarket();}
    }
    private void refreshLanguage(){
        long current=EditorI18n.languageVersion();if(current==languageVersion)return;languageVersion=current;
        saveFields();searchValue=view.value("search");String focused=view.focusedId();
        fragments.clear();paint();
        if(!focused.isBlank())view.focus(focused);
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float delta){}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){g.fill(0,0,width,height,0xFFF3F5F8);if(view!=null)view.render(g,0,0,width,height,mx,my,delta);}
    private int modifiers(){int result=0;if(InputConstants.isKeyDown(minecraft.getWindow(),InputConstants.KEY_LSHIFT)||InputConstants.isKeyDown(minecraft.getWindow(),InputConstants.KEY_RSHIFT))result|=1;if(InputConstants.isKeyDown(minecraft.getWindow(),InputConstants.KEY_LCONTROL)||InputConstants.isKeyDown(minecraft.getWindow(),InputConstants.KEY_RCONTROL))result|=2;if(InputConstants.isKeyDown(minecraft.getWindow(),InputConstants.KEY_LALT)||InputConstants.isKeyDown(minecraft.getWindow(),InputConstants.KEY_RALT))result|=4;return result;}
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean twice){return view!=null&&view.mouseClick(event.x(),event.y(),event.button(),modifiers());}
    @Override public boolean mouseReleased(MouseButtonEvent event){return view!=null&&view.mouseRelease(event.x(),event.y(),event.button(),modifiers());}
    @Override public void mouseMoved(double x,double y){if(view!=null)view.mouseMove(x,y);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return view!=null&&view.scroll(x,y,horizontal,vertical);}
    @Override public boolean charTyped(CharacterEvent event){return view!=null&&view.character(event.codepoint(),modifiers());}
    @Override public boolean keyPressed(KeyEvent event){
        if(event.isEscape()){onClose();return true;}int mods=modifiers()|(event.hasShiftDown()?1:0)|(event.hasControlDown()?2:0)|(event.hasAltDown()?4:0);if(event.key()==InputConstants.KEY_F&&(mods&2)!=0){view.focus("search");return true;}
        if(modal!=null&&event.isConfirmation()){if(view.activateFocusedButton())return true;if(!view.focusedId().equals("field_description")){submitModal();return true;}}
        if(view!=null&&view.inputFocused()){if(event.isConfirmation()&&view.focusedId().equals("search")){perform("search");return true;}return view.key(event.key(),0,mods);}if(view!=null&&view.key(event.key(),0,mods))return true;if(modal!=null)return true;
        if(event.key()==InputConstants.KEY_N&&(mods&2)!=0){perform((mods&1)!=0?"new_effect":"new_menu");return true;}
        if(tab.equals("projects")){if(event.key()==InputConstants.KEY_A&&(mods&2)!=0){perform("select_all");return true;}if(event.key()==InputConstants.KEY_DELETE){if(enabled("delete"))perform("delete");return true;}if(event.key()==InputConstants.KEY_F2){if(enabled("configure"))perform("configure");return true;}}
        return true;
    }
    @Override public void onClose(){
        if(modal!=null){if(modal.busy){status=studio("finish_operation");paint();return;}modal=null;modalSubmit=null;paint();return;}
        if(detail!=null){detail=null;detailGeneration++;paint();return;}
        if(!WorkspaceClient.STATE.selection().isEmpty()){WorkspaceClient.STATE.clearSelection();paint();return;}
        status=studio("escape_hint");paint();
    }
    private void exit(){closed=true;if(view!=null){view.close();view=null;}legacyPreview.close();minecraft.setScreen(parent);}
    @Override public void removed(){if(!WorkspacePublication.capturing()&&!(minecraft.screen instanceof WorkspacePreviewScreen preview&&preview.manager()==this)&&minecraft.screen!=this){if(view!=null){view.close();view=null;}fragments.clear();}super.removed();}
    void previewFinished(Throwable error){status=error==null?project("done"):WorkspaceClient.error(error);paint();}
    private static String bytes(long value){return value>=1_048_576?String.format(Locale.ROOT,"%.1f MB",value/1_048_576.0):value>=1024?String.format(Locale.ROOT,"%.1f KB",value/1024.0):value+" B";}
}
