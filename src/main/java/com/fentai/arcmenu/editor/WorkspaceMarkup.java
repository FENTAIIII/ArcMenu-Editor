package com.fentai.arcmenu.editor;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/** Pure markup fragments for the embedded workspace document. */
public final class WorkspaceMarkup {
    private WorkspaceMarkup() {}

    public static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    public static String text(String key, Object... values) {
        return EditorI18n.text("arcmenu_editor.studio." + key, values);
    }

    public static String icon(String name) {
        String shape = switch (name) {
            case "folder" -> "<path d='M3 6h7l2 2h9v12H3V6Z'/><path d='M3 6V4h7l2 2'/>";
            case "menu", "projects", "installed_projects" -> "<rect x='3' y='3' width='7' height='7' rx='1.5'/><rect x='14' y='3' width='7' height='7' rx='1.5'/><rect x='3' y='14' width='7' height='7' rx='1.5'/><rect x='14' y='14' width='7' height='7' rx='1.5'/>";
            case "effect", "new_effect" -> "<path d='M4 4h16v16H4V4Z'/><path d='m10 8 6 4-6 4V8Z'/>";
            case "template" -> "<rect x='3' y='3' width='13' height='13' rx='2'/><rect x='8' y='8' width='13' height='13' rx='2'/>";
            case "package" -> "<path d='M12 3 3 7.5v9L12 21l9-4.5v-9L12 3Z'/><path d='m3 7.5 9 4.5 9-4.5M12 12v9M7.5 5.25l9 4.5'/>";
            case "download", "import", "install", "install_version" -> "<path d='M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5'/>";
            case "upload", "publish" -> "<path d='M12 16V3m-5 5 5-5 5 5M4 16v5h16v-5'/>";
            case "export" -> "<path d='M12 3 3 7.5v9L12 21l9-4.5v-9L12 3Z'/><path d='m3 7.5 9 4.5 9-4.5M12 12v9'/>";
            case "heart", "like" -> "<path d='M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z'/>";
            case "star", "rate" -> "<path d='m12 3 2.8 5.8 6.4.9-4.6 4.5 1.1 6.4L12 17.6l-5.7 3 1.1-6.4L2.8 9.7l6.4-.9L12 3Z'/>";
            case "check", "modal_confirm" -> "<path d='m5 12 4 4L19 6'/>";
            case "plus", "new", "new_menu" -> "<path d='M12 4v16M4 12h16'/>";
            case "edit", "configure", "edit_metadata" -> "<path d='m16 3 5 5-12 12H4v-5L16 3Z'/><path d='m13 6 5 5'/>";
            case "delete", "delete_package", "uninstall", "hide_listing" -> "<path d='M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7'/>";
            case "open", "arrow", "next" -> "<path d='M5 12h14m-6-6 6 6-6 6'/>";
            case "back", "previous", "browse" -> "<path d='M19 12H5m6-6-6 6 6 6'/>";
            case "close", "modal_cancel" -> "<path d='m6 6 12 12M18 6 6 18'/>";
            case "image", "prepare" -> "<rect x='3' y='3' width='18' height='18' rx='2'/><circle cx='8' cy='8' r='1.5'/><path d='m4 18 5-5 4 3 4-6 4 7'/>";
            case "user", "author", "login" -> "<circle cx='12' cy='8' r='4'/><path d='M4 21v-2a8 8 0 0 1 16 0v2'/>";
            case "search" -> "<circle cx='10.5' cy='10.5' r='6.5'/><path d='m16 16 5 5'/>";
            case "refresh" -> "<path d='M20 7v5h-5M4 17v-5h5'/><path d='M6 6a8 8 0 0 1 13 2M18 18A8 8 0 0 1 5 16'/>";
            case "chevron" -> "<path d='m8 4 8 8-8 8'/>";
            default -> "<circle cx='12' cy='12' r='8'/><path d='M12 7v6M12 16v1'/>";
        };
        return "<svg viewBox='0 0 24 24' class='icon' fill='none' stroke='currentColor' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'>" + shape + "</svg>";
    }

    public static String button(String action, String id, String label, boolean disabled) {
        return button(action, id, label, disabled, "button button-secondary");
    }

    public static String button(String action, String id, String label, boolean disabled, String classes) {
        return "<button id='control_" + escape(action) + (id == null || id.isEmpty() ? "" : "_" + escape(id)) + "' class='" + escape(classes) + "' data-action='" + escape(action)
                + "' data-id='" + escape(id) + "'" + (disabled ? " disabled" : "") + ">"
                + escape(label) + "</button>";
    }

    public static String iconButton(String action, String id, String label, String icon, boolean disabled, String classes) {
        return "<button id='control_" + escape(action) + (id == null || id.isEmpty() ? "" : "_" + escape(id)) + "' class='" + escape(classes) + "' data-action='" + escape(action)
                + "' data-id='" + escape(id) + "'" + (disabled ? " disabled" : "") + ">"
                + icon(icon) + "<span>" + escape(label) + "</span></button>";
    }

    public static String projects(List<WorkspaceState.Row> rows, List<WorkspaceState.Project> selected) {
        Set<String> selectedKeys = new HashSet<>();
        selected.forEach(project -> selectedKeys.add(project.key()));
        StringBuilder html = new StringBuilder("<div class='project-table'><div class='table-head'><span class='row-checkbox'></span><span class='row-resource-icon' style='visibility:hidden'></span><span class='row-name'>")
                .append(escape(text("column.name"))).append("</span><span class='row-type'>")
                .append(escape(text("column.type"))).append("</span><span class='row-dimensions'>")
                .append(escape(text("column.canvas"))).append("</span><span class='row-open'></span></div>");
        for (WorkspaceState.Row row : rows) {
            if (row.folder()) {
                html.append("<button class='table-folder' data-action='toggle_folder' data-id='")
                        .append(escape(row.key())).append("' style='padding-left:")
                        .append(16 + row.depth() * 12).append("px'>").append(icon("folder"))
                        .append("<span>").append(escape(folderLabel(row))).append("</span><span class='folder-state'>")
                        .append(row.expanded() ? "−" : "+").append("</span></button>");
                continue;
            }
            var project = row.project();
            boolean selectedNow = selectedKeys.contains(project.key());
            html.append("<div id='resource_").append(escape(project.key())).append("' class='project-row").append(selectedNow ? " selected" : "")
                    .append("' data-action='select_project' data-id='").append(escape(project.key()))
                    .append("'><button id='checkbox_").append(escape(project.key()))
                    .append("' class='row-checkbox' type='button' role='checkbox' aria-checked='").append(selectedNow)
                    .append("' aria-label='").append(escape(project.id()))
                    .append("' data-action='toggle_project' data-id='").append(escape(project.key())).append("'>")
                    .append(selectedNow ? icon("check") : "")
                    .append("</button><span class='row-resource-icon ").append(escape(project.type()))
                    .append("'>").append(icon(project.type())).append("</span><span class='row-name'><strong>")
                    .append(escape(project.id())).append("</strong><span>").append(escape(project.path()))
                    .append("</span></span><span class='row-type'><span class='pill ")
                    .append(project.type().equals("effect") ? "pill-purple" : project.previewOnly() ? "pill-green" : "")
                    .append("'>").append(escape(text("type." + project.type())))
                    .append("</span></span><span class='row-dimensions'>")
                    .append(number(project.width())).append(" × ").append(number(project.height()))
                    .append("</span><span class='row-open'>")
                    .append(button("open_project", project.key(), project.previewOnly() ? EditorI18n.text("arcmenu_editor.projects.preview") : text("open"), false, "button button-small button-ghost"))
                    .append("</span></div>");
        }
        return html.append("</div>").toString();
    }

    public static String folderLabel(WorkspaceState.Row row) {
        return row.depth()==0 ? text("filter."+row.label()) : row.label();
    }

    public static String projectDetails(WorkspaceState.Project project) {
        String details="<h2>"+escape(project.id())+"</h2><p>"+escape(text("type."+project.type()))+"</p>"
                +property(text("column.path"),project.path())
                +property(text("column.canvas"),number(project.width())+" × "+number(project.height()));
        if(project.previewOnly()) {
            if(!project.rootId().isBlank())details+=property(EditorI18n.text("arcmenu_editor.projects.template_root"),project.rootId());
            if(project.nodeCount()>0)details+=property(EditorI18n.text("arcmenu_editor.projects.template_nodes"),Integer.toString(project.nodeCount()));
            return details+"<p>"+escape(EditorI18n.text("arcmenu_editor.projects.template_hint"))+"</p>";
        }
        return details+property(EditorI18n.text("arcmenu_editor.projects.field.pixelsPerBlock"),number(project.pixelsPerBlock()))
                +"<p>"+escape(EditorI18n.text("arcmenu_editor.projects.ppb_hint"))+"</p>";
    }

    public static String packs(List<WorkspaceState.LocalPack> packs, String selectedFilename, Map<String, String> covers) {
        StringBuilder html = new StringBuilder("<div class='resource-grid'>");
        for (var pack : packs) {
            boolean selected = pack.filename().equals(selectedFilename);
            html.append("<div id='resource_").append(escape(pack.filename())).append("' class='resource-card").append(selected ? " selected" : "")
                    .append("' data-action='select_package' data-id='").append(escape(pack.filename())).append("'>")
                    .append(cover(covers.get(pack.filename()), "package-cover", "package"))
                    .append("<div class='card-info'><h3>").append(escape(pack.title().isBlank() ? pack.id() : pack.title()))
                    .append("</h3><div class='card-subtitle'>").append(escape(pack.filename()))
                    .append("</div><div class='card-description'>").append(escape(pack.description()))
                    .append("</div><div class='card-footer'><span class='pill ").append(pack.prepared() ? "pill-green" : "pill-amber")
                    .append("'>").append(escape(text(pack.prepared() ? "pack.ready" : "pack.needs_preview")))
                    .append("</span><span class='card-version'>").append(escape(pack.version()))
                    .append("</span></div></div></div>");
        }
        return html.append("</div>").toString();
    }

    public static String market(List<JsonObject> listings, Map<String, String> covers) {
        StringBuilder html = new StringBuilder("<div class='resource-grid market-grid'>");
        for (var resource : listings) {
            String id = WorkspaceState.string(resource, "id", "");
            JsonObject author = resource.has("author") && resource.get("author").isJsonObject()
                    ? resource.getAsJsonObject("author") : new JsonObject();
            String username = WorkspaceState.string(author, "username", WorkspaceState.string(author, "name", ""));
            html.append("<div id='resource_").append(escape(id)).append("' class='resource-card' data-action='open_market' data-id='").append(escape(id)).append("'>")
                    .append(cover(covers.get(id), "", "package"))
                    .append("<div class='card-info'><h3>").append(escape(WorkspaceState.string(resource, "title", id)))
                    .append("</h3><div class='card-author'><span class='author-avatar'>")
                    .append(escape(username.isEmpty() ? "A" : username.substring(0, 1))).append("</span><span>")
                    .append(escape(username)).append("</span></div><div class='card-description'>")
                    .append(escape(WorkspaceState.string(resource, "description", ""))).append("</div><div class='card-footer'>")
                    .append(metric("download", compact(WorkspaceState.number(resource, "downloads", 0))))
                    .append(metric("heart", compact(WorkspaceState.number(resource, "likeCount", 0))))
                    .append(metric("star", String.format(Locale.ROOT, "%.1f", WorkspaceState.number(resource, "ratingAverage", 0))))
                    .append("<span class='card-version pill pill-green'>").append(escape(text("free")))
                    .append("</span></div></div></div>");
        }
        return html.append("</div>").toString();
    }

    public static String installed(List<WorkspaceState.Pack> packs, String selectedId) {
        StringBuilder html = new StringBuilder("<div class='project-table'>");
        for (var pack : packs) {
            html.append("<div id='resource_").append(escape(pack.id())).append("' class='project-row").append(pack.id().equals(selectedId) ? " selected" : "")
                    .append("' data-action='select_installed' data-id='").append(escape(pack.id()))
                    .append("'><span class='row-resource-icon'>").append(icon("package"))
                    .append("</span><span class='row-name'><strong>").append(escape(pack.title()))
                    .append("</strong><span>").append(escape(pack.id())).append("</span></span><span class='pill pill-green'>")
                    .append(escape(text("installed"))).append("</span><span class='row-dimensions'>")
                    .append(escape(pack.version())).append("</span></div>");
        }
        return html.append("</div>").toString();
    }

    public static String cover(String source, String classes, String icon) {
        return "<div class='card-cover " + escape(classes) + "'>" + (source == null || source.isEmpty()
                ? "<div class='placeholder'>" + icon(icon) + "</div>"
                : "<img src='" + escape(source) + "'>") + "</div>";
    }

    public static String metric(String icon, String value) {
        return "<span class='card-metric'>" + icon(icon) + "<span>" + escape(value) + "</span></span>";
    }

    public static String property(String label, String value) {
        return "<div class='property'><span class='property-name'>" + escape(label)
                + "</span><span class='property-value'>" + escape(value) + "</span></div>";
    }

    public static String field(String id, String label, String value, String hint, String type, boolean required) {
        String start = "<div class='form-field" + (type.equals("textarea") || id.endsWith("_title") || id.endsWith("_filename") ? " full-row" : "") + "'><label for='" + escape(id) + "'>" + escape(label)
                + (required ? "<span class='field-required'>*</span>" : "") + "</label>";
        String input = type.equals("select-ce")
                ? "<select id='"+escape(id)+"'><option value='false'>"+escape(text("ce.ask"))+"</option><option value='true'>"+escape(text("ce.keep"))+"</option></select>"
                : type.equals("textarea")
                ? "<textarea id='" + escape(id) + "' maxlength='20000'" + (required ? " required" : "") + ">" + escape(value) + "</textarea>"
                : "<input id='" + escape(id) + "' type='" + escape(type) + "' value='" + escape(value)
                  + "'" + (id.endsWith("_title") ? " maxlength='128'" : "") + (required ? " required" : "") + ">";
        return start + input + "<span class='field-hint'>" + escape(hint) + "</span></div>";
    }

    public static String empty(String title, String hint, String action, String actionLabel) {
        return "<div class='empty-state'><div class='empty-symbol'>" + icon("folder") + "</div><h2>"
                + escape(title) + "</h2><p>" + escape(hint) + "</p>"
                + (action == null || action.isEmpty() ? "" : "<div class='empty-actions'>"
                  + button(action, "", actionLabel, false, "button button-primary").replace("id='control_", "id='empty_control_") + "</div>") + "</div>";
    }

    public static String authentication(String title, String hint, String error, boolean pending, String retryLabel) {
        return "<div class='empty-state authentication-state' role='status'><div class='empty-symbol'>"
                + icon("user") + "</div><h2 id='authentication_title'>" + escape(title) + "</h2>"
                + "<p id='authentication_hint'>" + escape(hint) + "</p>"
                + (error == null || error.isBlank() ? "" : "<p id='authentication_error' class='authentication-error'>" + escape(error) + "</p>")
                + (pending ? "" : "<div class='empty-actions'>" + button("login", "", retryLabel, false, "button button-primary") + "</div>")
                + "</div>";
    }

    public static String inspectorEmpty(String title, String hint) {
        return "<div class='inspector-empty'><div class='empty-symbol'>" + icon("projects")
                + "</div><h3>" + escape(title) + "</h3><p>" + escape(hint) + "</p></div>";
    }

    public static String hero(String eyebrow, String title, String hint) {
        return "<div class='market-hero'><div><div class='eyebrow'>" + escape(eyebrow)
                + "</div><h2>" + escape(title) + "</h2><p>" + escape(hint)
                + "</p></div><div class='hero-art'><div class='hero-mini'></div><div class='hero-box'>"
                + icon("package") + "</div><div class='hero-dot'></div></div></div>";
    }

    public static String skeleton(int count) {
        StringBuilder html = new StringBuilder("<div class='resource-grid market-grid'>");
        for (int i = 0; i < count; i++) html.append("<div class='resource-card'><div class='skeleton skeleton-cover'></div><div class='card-info'><div class='skeleton skeleton-medium'></div><div class='skeleton skeleton-short'></div><div class='card-footer'><div class='skeleton skeleton-short'></div></div></div></div>");
        return html.append("</div>").toString();
    }

    public static String compact(double value) {
        if (value >= 1_000_000) return String.format(Locale.ROOT, "%.1fm", value / 1_000_000);
        if (value >= 1_000) return String.format(Locale.ROOT, "%.1fk", value / 1_000);
        return Long.toString((long) value);
    }

    public static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
