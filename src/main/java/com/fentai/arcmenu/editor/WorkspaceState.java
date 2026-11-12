package com.fentai.arcmenu.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Server-owned catalogue and a separate, deterministic project selection. */
public final class WorkspaceState
{
    /** New menus and effects start from the same canvas as the server's bundled default menus. */
    static final double DEFAULT_WIDTH = 320;
    static final double DEFAULT_HEIGHT = 180;
    static final double DEFAULT_PIXELS_PER_BLOCK = 42.7;
    static final double DEFAULT_DISTANCE = 3;

    public record Project(String type, String id, String path, double width, double height, double pixelsPerBlock,
            double distance, String permission, String rootId, int nodeCount, String depthMode)
    {
        public Project(String type, String id, String path, double width, double height, double pixelsPerBlock,
                double distance, String permission, String rootId, int nodeCount)
        {
            this(type, id, path, width, height, pixelsPerBlock, distance, permission, rootId, nodeCount, "perspective");
        }

        public Project(String type, String id, String path, double width, double height, double pixelsPerBlock,
                double distance, String permission)
        {
            this(type, id, path, width, height, pixelsPerBlock, distance, permission, "", 0, "perspective");
        }

        public String key()
        {
            return type + ":" + id;
        }

        public JsonObject reference()
        {
            var json = new JsonObject();
            json.addProperty("type", type);
            json.addProperty("id", id);
            return json;
        }

        public boolean previewOnly()
        {
            return type.equals("template");
        }

        public boolean configurable()
        {
            return type.equals("menu") || type.equals("effect");
        }

        public String openAction()
        {
            return previewOnly() ? "preview-open" : "open";
        }

        static Project from(JsonObject json)
        {
            String id = string(json, "id", "");
            String type = string(json, "type", "menu");
            return new Project(type, id, string(json, "path", id + ".yml"), number(json, "width", DEFAULT_WIDTH),
                    number(json, "height", DEFAULT_HEIGHT), number(json, "pixelsPerBlock", DEFAULT_PIXELS_PER_BLOCK),
                    number(json, "distance", DEFAULT_DISTANCE), string(json, "permission", ""),
                    string(json, "rootId", ""), (int) number(json, "nodeCount", 0),
                    string(json, "depthMode", "perspective"));
        }
    }

    static String depthMode(String value)
    {
        if (!List.of("perspective", "layered").contains(value))
        {
            throw new IllegalArgumentException(EditorI18n.text("arcmenu_editor.projects.depth_mode_invalid"));
        }
        return value;
    }

    public record Pack(String id, String title, String version)
    {}

    public record LocalPack(String filename, String id, String version, String title, String description,
            JsonObject author, JsonArray projects, JsonArray dependencies, long size, String modifiedAt,
            JsonArray previews, String cover, boolean prepared)
    {
        public static LocalPack from(JsonObject json)
        {
            return new LocalPack(string(json, "filename", ""), string(json, "id", ""), string(json, "version", ""),
                    string(json, "title", ""), string(json, "description", ""), object(json, "author"),
                    array(json, "projects"), array(json, "dependencies"), (long) number(json, "size", 0),
                    string(json, "modifiedAt", ""), array(json, "previews"), string(json, "cover", ""),
                    json.has("prepared") && json.get("prepared").getAsBoolean());
        }

        public List<Project> captureProjects()
        {
            var list = new ArrayList<Project>();
            for (var value : projects)
            {
                list.add(Project.from(value.getAsJsonObject()));
            }
            return List.copyOf(list);
        }
    }

    public record Row(String key, String label, int depth, Project project, boolean expanded)
    {
        public boolean folder()
        {
            return project == null;
        }
    }

    private boolean effectsSupported = true;
    private boolean marketAllowed = true;

    public boolean marketAllowed()
    {
        return marketAllowed;
    }

    public boolean effectsSupported()
    {
        return effectsSupported;
    }

    private List<Project> projects = List.of();
    private List<Pack> packs = List.of();
    private List<LocalPack> localPacks = List.of();
    private String marketUrl = "";
    private String marketCertificateSha256 = "";
    private String anchor = "";
    private final Set<String> collapsed = new HashSet<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();

    public List<Project> projects()
    {
        return projects;
    }

    public List<Pack> packs()
    {
        return packs;
    }

    public List<LocalPack> localPacks()
    {
        return localPacks;
    }

    public String marketUrl()
    {
        return marketUrl;
    }

    public String marketCertificateSha256()
    {
        return marketCertificateSha256;
    }

    public boolean selected(Project project)
    {
        return selected.contains(project.key());
    }

    public List<Project> selection()
    {
        return projects.stream().filter(this::selected).toList();
    }

    public JsonArray references()
    {
        var json = new JsonArray();
        selection().forEach(project -> json.add(project.reference()));
        return json;
    }

    public void updateCapabilities(JsonObject json)
    {
        if (json.has("capabilities"))
        {
            var capabilities = json.getAsJsonObject("capabilities");
            effectsSupported = !capabilities.has("effects") || capabilities.get("effects").getAsBoolean();
            marketAllowed = !capabilities.has("market") || capabilities.get("market").getAsBoolean();
        }
    }

    public void update(JsonObject json)
    {
        updateCapabilities(json);
        var next = new ArrayList<Project>();
        if (json.has("projects"))
        {
            for (var value : json.getAsJsonArray("projects"))
            {
                var project = Project.from(value.getAsJsonObject());
                if (!project.id().isEmpty())
                {
                    next.add(project);
                }
            }
        }
        next.sort(Comparator.comparing(Project::type).thenComparing(Project::path).thenComparing(Project::id));
        projects = List.copyOf(next);
        selected.retainAll(projects.stream().map(Project::key).toList());
        var installed = new ArrayList<Pack>();
        if (json.has("packs"))
        {
            for (var value : json.getAsJsonArray("packs"))
            {
                var entry = value.getAsJsonObject();
                installed.add(
                        new Pack(string(entry, "id", ""), string(entry, "title", ""), string(entry, "version", "")));
            }
        }
        packs = List.copyOf(installed);
        marketUrl = string(json, "marketUrl", "");
        var archives = new ArrayList<LocalPack>();
        if (json.has("localPacks"))
        {
            for (var value : json.getAsJsonArray("localPacks"))
            {
                archives.add(LocalPack.from(value.getAsJsonObject()));
            }
        }
        archives.sort(Comparator.comparing(LocalPack::filename));
        localPacks = List.copyOf(archives);
        marketCertificateSha256 = string(json, "marketCertificateSha256", "");
    }

    public List<Row> rows(String query)
    {
        return rows(query, "all");
    }

    public List<Row> rows(String query, String type)
    {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        var rows = new ArrayList<Row>();
        var added = new HashSet<String>();
        for (var project : projects)
        {
            if (!type.equals("all") && !project.type().equals(type))
            {
                continue;
            }
            if (!(project.type() + " " + project.id() + " " + project.path()).toLowerCase(Locale.ROOT).contains(needle))
            {
                continue;
            }
            String normalized = project.path().replace('\\', '/');
            String[] parts = normalized.split("/");
            String key = project.type();
            boolean hidden = false;
            for (int i = 0; i < parts.length; i++)
            {
                String label = i == 0 ? project.type() : parts[i - 1];
                if (added.add(key))
                {
                    rows.add(new Row(key, label, i, null, !collapsed.contains(key)));
                }
                if (needle.isEmpty() && collapsed.contains(key))
                {
                    hidden = true;
                    break;
                }
                if (i < parts.length - 1)
                {
                    key += "/" + parts[i];
                }
            }
            if (!hidden)
            {
                rows.add(new Row(project.key(), project.id(), parts.length, project, false));
            }
        }
        return List.copyOf(rows);
    }

    public void toggleFolder(String key)
    {
        if (!collapsed.remove(key))
        {
            collapsed.add(key);
        }
    }

    public void select(Project project, boolean control, boolean shift, List<Row> visible)
    {
        if (shift && !anchor.isEmpty())
        {
            var ids = visible.stream().filter(row -> !row.folder()).map(Row::key).toList();
            int anchorIndex = ids.indexOf(anchor);
            int targetIndex = ids.indexOf(project.key());
            if (anchorIndex >= 0 && targetIndex >= 0)
            {
                if (!control)
                {
                    selected.clear();
                }
                selected.addAll(
                        ids.subList(Math.min(anchorIndex, targetIndex), Math.max(anchorIndex, targetIndex) + 1));
                return;
            }
        }
        if (!control)
        {
            selected.clear();
        }
        if (control && selected.contains(project.key()))
        {
            selected.remove(project.key());
        }
        else
        {
            selected.add(project.key());
        }
        anchor = project.key();
    }

    public void selectAll(List<Row> visible)
    {
        selected.clear();
        visible.stream().filter(row -> !row.folder()).forEach(row -> selected.add(row.key()));
    }

    public void clearSelection()
    {
        selected.clear();
        anchor = "";
    }

    public void clear()
    {
        effectsSupported = marketAllowed = true;
        projects = List.of();
        packs = List.of();
        localPacks = List.of();
        marketUrl = marketCertificateSha256 = anchor = "";
        selected.clear();
        collapsed.clear();
    }

    private static JsonObject object(JsonObject json, String key)
    {
        return json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key).deepCopy() : new JsonObject();
    }

    private static JsonArray array(JsonObject json, String key)
    {
        return json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key).deepCopy() : new JsonArray();
    }

    static String string(JsonObject json, String key, String fallback)
    {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;
    }

    static double number(JsonObject json, String key, double fallback)
    {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsDouble() : fallback;
    }
}
