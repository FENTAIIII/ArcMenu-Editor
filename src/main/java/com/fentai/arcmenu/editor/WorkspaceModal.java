package com.fentai.arcmenu.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Values and validation shared by native workspace forms and their MCP controls. */
final class WorkspaceModal {
    final String mode;
    final List<WorkspaceState.Project> projects;
    final LinkedHashMap<String,String> values = new LinkedHashMap<>();
    boolean busy;
    String error = "";

    WorkspaceModal(String mode,List<WorkspaceState.Project> projects,WorkspaceState.LocalPack pack,JsonObject listing) {
        this.mode=mode;this.projects=List.copyOf(projects);
        switch(mode) {
            case "new_menu","new_effect" -> {values.put("id","");values.put("folder","");geometry(null);}
            case "configure" -> {if(!projects.getFirst().configurable())throw new IllegalArgumentException(EditorI18n.text("arcmenu_editor.projects.template_configure_unavailable"));geometry(projects.getFirst());values.put("permission",projects.getFirst().permission());}
            case "export" -> {
                values.put("id",projects.size()==1?projects.getFirst().id():"menu-pack");
                values.put("version","1.0.0");values.put("title",projects.size()==1?projects.getFirst().id():EditorI18n.text("arcmenu_editor.projects.default_pack_title"));
                values.put("description","");values.put("allowCe","false");
            }
            case "publish" -> {if(pack!=null){values.put("title",pack.title());values.put("description",pack.description());}}
            case "edit_metadata" -> {values.put("title",WorkspaceState.string(listing,"title",""));values.put("description",WorkspaceState.string(listing,"description",""));}
            case "import" -> values.put("filename","");
            default -> {}
        }
    }
    private void geometry(WorkspaceState.Project project) {
        values.put("width",number(project==null?800:project.width()));values.put("height",number(project==null?450:project.height()));
        values.put("pixelsPerBlock",number(project==null?WorkspaceState.defaultPixelsPerBlock(mode.equals("new_effect")?"effect":"menu"):project.pixelsPerBlock()));values.put("distance",number(project==null?2:project.distance()));
        values.put("depthMode",project==null?"perspective":project.depthMode());
    }
    private static String number(double n){return n==Math.rint(n)?Long.toString((long)n):Double.toString(n);}
    JsonObject parameters(Map<String,String> input) {
        var json=new JsonObject();
        for(String key:values.keySet()) {
            String value=input.getOrDefault(key,values.get(key)).strip();values.put(key,value);
            if(List.of("width","height","pixelsPerBlock","distance").contains(key)) {
                double number;
                try {number=Double.parseDouble(value);}catch(NumberFormatException failure){number=Double.NaN;}
                if(!Double.isFinite(number)||number<=0)throw new IllegalArgumentException(EditorI18n.text("arcmenu_editor.projects.positive",EditorI18n.text("arcmenu_editor.projects.field."+key)));
                json.addProperty(key,number);
            } else if(key.equals("depthMode")) {
                json.addProperty(key,WorkspaceState.depthMode(value));
            } else if(key.equals("allowCe")) {
                if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException(EditorI18n.text("arcmenu_editor.projects.boolean"));
                json.addProperty(key,Boolean.parseBoolean(value));
            } else json.addProperty(key,value);
        }
        for(String key:List.of("id","filename","title","description")) {
            if(values.containsKey(key)&&values.get(key).isBlank())throw new IllegalArgumentException(EditorI18n.text("arcmenu_editor.projects.required",EditorI18n.text("arcmenu_editor.projects.field."+key)));
        }
        if(mode.equals("new_menu")||mode.equals("new_effect"))json.addProperty("type",mode.equals("new_menu")?"menu":"effect");
        if(mode.equals("configure")){json.addProperty("type",projects.getFirst().type());json.addProperty("id",projects.getFirst().id());}
        if(mode.equals("delete")||mode.equals("export")){var references=new JsonArray();projects.forEach(p->references.add(p.reference()));json.add("projects",references);json.addProperty("confirm",true);}
        if(mode.equals("discard"))json.addProperty("confirmDiscard",true);
        return json;
    }
}
