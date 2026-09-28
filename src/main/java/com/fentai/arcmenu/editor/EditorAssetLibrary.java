package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Folder navigation and filtering for the bottom media panel. */
final class EditorAssetLibrary {
    private static final Set<String> INTERNAL = Set.of("mouse", "tooltip", "tooltips", "cursor", "cursors", "local_cursor", "__tooltip");
    private List<EditorProtocol.ImageSnapshot> images = List.of();
    private List<String> folders = List.of("");
    private boolean imageMode;
    private String folder = "";
    private String selectedPath = "";

    void selectTool(byte kind) {
        if (kind == EditorProtocol.KIND_IMAGE) imageMode = true;
        else if (kind == -1) imageMode = false;
    }
    boolean imageMode() { return imageMode; }
    String folder() { return folder; }
    List<String> folders() { return folders; }
    String selectedPath() { return selectedPath; }
    void select(String path) { selectedPath = path; }
    EditorProtocol.ImageSnapshot selected() {
        return images.stream().filter(image -> image.path().equals(selectedPath)).findFirst().orElse(null);
    }
    void openFolder(String path) {
        if (folders.contains(path)) { folder = path; selectedPath = ""; }
    }
    void up() { openFolder(parent(folder)); }

    void update(List<EditorProtocol.ImageSnapshot> available) {
        images = available.stream().filter(image -> isUserImage(image.path()))
                .sorted(Comparator.comparing(image -> relative(image.path()))).toList();
        TreeSet<String> paths = new TreeSet<>();
        paths.add("");
        for (var image : images) {
            String path = parent(relative(image.path()));
            while (!path.isEmpty()) { paths.add(path); path = parent(path); }
        }
        folders = List.copyOf(paths);
        while (!folders.contains(folder)) folder = parent(folder);
        if (selected() == null) selectedPath = "";
    }

    List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        for (String path : folders) {
            if (!path.isEmpty() && parent(path).equals(folder)) result.add(new Entry(path, name(path), null));
        }
        for (var image : images) {
            String path = relative(image.path());
            if (parent(path).equals(folder)) result.add(new Entry(path, name(path), image));
        }
        return List.copyOf(result);
    }

    static boolean isUserImage(String source) {
        String path = relative(source).toLowerCase(Locale.ROOT);
        if (!path.endsWith(".png")) return false;
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == parts.length - 1) part = part.substring(0, part.length() - 4);
            if (part.isBlank() || part.startsWith(".") || INTERNAL.contains(part)) return false;
        }
        return true;
    }
    static String texturePath(String source) { return "textures/images/" + relative(source); }
    static String relative(String source) { return source.replace('\\', '/').replaceAll("^/+", ""); }
    static String parent(String path) { int slash = path.lastIndexOf('/'); return slash < 0 ? "" : path.substring(0, slash); }
    static String name(String path) { return path.substring(path.lastIndexOf('/') + 1); }
    record Entry(String path, String name, EditorProtocol.ImageSnapshot image) {
        boolean directory() { return image == null; }
    }
}
