package com.fentai.arcmenu.editor;

import com.google.gson.*;
import java.util.*;

/** Server-owned catalogue and a separate, deterministic project selection. */
public final class WorkspaceState {
    static double defaultPixelsPerBlock(String type) { return "effect".equals(type) ? 100.0 : 42.7; }
    public record Project(String type, String id, String path, double width, double height,
                          double pixelsPerBlock, double distance, String permission, String rootId, int nodeCount) {
        public Project(String type,String id,String path,double width,double height,double pixelsPerBlock,double distance,String permission) {
            this(type,id,path,width,height,pixelsPerBlock,distance,permission,"",0);
        }
        public String key() { return type + ":" + id; }
        public JsonObject reference() { var json = new JsonObject(); json.addProperty("type", type); json.addProperty("id", id); return json; }
        public boolean previewOnly() { return type.equals("template"); }
        public boolean configurable() { return type.equals("menu") || type.equals("effect"); }
        public String openAction() { return previewOnly() ? "preview-open" : "open"; }
        static Project from(JsonObject p) {
            String id=string(p,"id",""),type=string(p,"type","menu");
            return new Project(type,id,string(p,"path",id+".yml"),number(p,"width",800),number(p,"height",450),number(p,"pixelsPerBlock",defaultPixelsPerBlock(type)),number(p,"distance",2),string(p,"permission",""),string(p,"rootId",""),(int)number(p,"nodeCount",0));
        }
    }
    public record Pack(String id, String title, String version) {}
    public record LocalPack(String filename, String id, String version, String title, String description,
                            JsonObject author, JsonArray projects, JsonArray dependencies, long size,
                            String modifiedAt, JsonArray previews, String cover, boolean prepared) {
        public static LocalPack from(JsonObject p) {
            return new LocalPack(string(p,"filename",""),string(p,"id",""),string(p,"version",""),
                    string(p,"title",""),string(p,"description",""),object(p,"author"),array(p,"projects"),
                    array(p,"dependencies"),(long)number(p,"size",0),string(p,"modifiedAt",""),
                    array(p,"previews"),string(p,"cover",""),p.has("prepared")&&p.get("prepared").getAsBoolean());
        }
        public List<Project> captureProjects() {
            var list=new ArrayList<Project>();
            for(var value:projects)list.add(Project.from(value.getAsJsonObject()));
            return List.copyOf(list);
        }
    }
    public record Row(String key, String label, int depth, Project project, boolean expanded) { public boolean folder() { return project == null; } }
    private boolean effectsSupported = true;
    private boolean marketAllowed = true;
    public boolean marketAllowed() { return marketAllowed; }
    public boolean effectsSupported() { return effectsSupported; }
    private List<Project> projects = List.of();
    private List<Pack> packs = List.of();
    private List<LocalPack> localPacks = List.of();
    private String marketUrl = "", marketCertificateSha256 = "", anchor = "";
    private final Set<String> collapsed = new HashSet<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    public List<Project> projects() { return projects; }
    public List<Pack> packs() { return packs; }
    public List<LocalPack> localPacks() { return localPacks; }
    public String marketUrl() { return marketUrl; }
    public String marketCertificateSha256() { return marketCertificateSha256; }
    public boolean selected(Project project) { return selected.contains(project.key()); }
    public List<Project> selection() { return projects.stream().filter(this::selected).toList(); }
    public JsonArray references() { var json = new JsonArray(); selection().forEach(p -> json.add(p.reference())); return json; }
    public void updateCapabilities(JsonObject json) {
        if (json.has("capabilities")) {
            var capabilities = json.getAsJsonObject("capabilities");
            effectsSupported = !capabilities.has("effects") || capabilities.get("effects").getAsBoolean();
            marketAllowed = !capabilities.has("market") || capabilities.get("market").getAsBoolean();
        }
    }
    public void update(JsonObject json) {
        updateCapabilities(json);
        var next = new ArrayList<Project>();
        if (json.has("projects")) for (var value : json.getAsJsonArray("projects")) {
            var project=Project.from(value.getAsJsonObject());
            if (!project.id().isEmpty()) next.add(project);
        }
        next.sort(Comparator.comparing(Project::type).thenComparing(Project::path).thenComparing(Project::id));
        projects = List.copyOf(next); selected.retainAll(projects.stream().map(Project::key).toList());
        var installed = new ArrayList<Pack>();
        if (json.has("packs")) for (var value : json.getAsJsonArray("packs")) { var p=value.getAsJsonObject(); installed.add(new Pack(string(p,"id",""),string(p,"title",""),string(p,"version",""))); }
        packs = List.copyOf(installed); marketUrl = string(json,"marketUrl","");
        var archives=new ArrayList<LocalPack>();
        if(json.has("localPacks"))for(var value:json.getAsJsonArray("localPacks"))archives.add(LocalPack.from(value.getAsJsonObject()));
        archives.sort(Comparator.comparing(LocalPack::filename));localPacks=List.copyOf(archives);
        marketCertificateSha256=string(json,"marketCertificateSha256","");
    }
    public List<Row> rows(String query) {
        return rows(query,"all");
    }
    public List<Row> rows(String query,String type) {
        String q = query.trim().toLowerCase(Locale.ROOT); var rows = new ArrayList<Row>(); var added = new HashSet<String>();
        for (var p : projects) {
            if (!type.equals("all") && !p.type().equals(type)) continue;
            if (!(p.type()+" "+p.id()+" "+p.path()).toLowerCase(Locale.ROOT).contains(q)) continue;
            String normalized=p.path().replace('\\','/'); String[] parts=normalized.split("/"); String key=p.type(); boolean hidden=false;
            for (int i=0;i<parts.length;i++) {
                String label=i==0?p.type():parts[i-1];
                if (added.add(key)) rows.add(new Row(key,label,i,null,!collapsed.contains(key)));
                if (q.isEmpty() && collapsed.contains(key)) { hidden=true; break; }
                if (i<parts.length-1) key+="/"+parts[i];
            }
            if (!hidden) rows.add(new Row(p.key(),p.id(),parts.length,p,false));
        }
        return List.copyOf(rows);
    }
    public void toggleFolder(String key) { if (!collapsed.remove(key)) collapsed.add(key); }
    public void select(Project project, boolean control, boolean shift, List<Row> visible) {
        if (shift && !anchor.isEmpty()) {
            var ids=visible.stream().filter(r->!r.folder()).map(Row::key).toList(); int a=ids.indexOf(anchor),b=ids.indexOf(project.key());
            if(a>=0&&b>=0) { if(!control)selected.clear();selected.addAll(ids.subList(Math.min(a,b),Math.max(a,b)+1));return; }
        }
        if (!control) selected.clear();
        if (control && selected.contains(project.key())) selected.remove(project.key()); else selected.add(project.key());
        anchor=project.key();
    }
    public void selectAll(List<Row> visible) { selected.clear();visible.stream().filter(r->!r.folder()).forEach(r->selected.add(r.key())); }
    public void clearSelection() { selected.clear(); anchor=""; }
    public void clear() { effectsSupported=marketAllowed=true;projects=List.of();packs=List.of();localPacks=List.of();marketUrl=marketCertificateSha256=anchor="";selected.clear();collapsed.clear(); }
    private static JsonObject object(JsonObject json,String key){return json.has(key)&&json.get(key).isJsonObject()?json.getAsJsonObject(key).deepCopy():new JsonObject();}
    private static JsonArray array(JsonObject json,String key){return json.has(key)&&json.get(key).isJsonArray()?json.getAsJsonArray(key).deepCopy():new JsonArray();}
    static String string(JsonObject json,String key,String fallback) { return json.has(key)&&!json.get(key).isJsonNull()?json.get(key).getAsString():fallback; }
    static double number(JsonObject json,String key,double fallback) { return json.has(key)&&!json.get(key).isJsonNull()?json.get(key).getAsDouble():fallback; }
}
