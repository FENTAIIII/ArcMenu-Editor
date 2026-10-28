package com.fentai.arcmenu.editor;

import java.util.*;

/** Command metadata shared by menu hints, shortcut lookup, command search and MCP discovery. */
public final class EffectCommands {
    public record Command(String id, String label, String shortcut) {}
    private static final List<Command> COMMANDS = List.of(
        c("save","save","Ctrl+S"),c("undo","undo","Ctrl+Z"),c("redo","redo","Ctrl+Shift+Z"),
        c("copy","copy","Ctrl+C"),c("cut","cut","Ctrl+X"),c("paste","paste","Ctrl+V"),
        c("duplicate","duplicate","Ctrl+D"),c("split","split","Ctrl+Shift+D"),c("delete","delete","Delete"),
        c("select-all","select_all","Ctrl+A"),c("deselect","deselect","F2"),c("rename","rename","Enter"),
        c("group-layers","group_layers","Ctrl+Shift+C"),c("focus-properties","focus_properties","F3"),c("group","group","Ctrl+G"),c("ungroup","ungroup","Ctrl+Shift+G"),
        c("settings","settings","Ctrl+K"),c("layer-settings","layer_settings","Ctrl+Shift+Y"),
        c("new-text","new_text","Ctrl+Alt+Shift+T"),c("new-rectangle","new_rectangle","Ctrl+Y"),
        c("new-gif","new_gif",""),
        c("move-into","move_into",""),c("move-out","move_out",""),c("move-root","move_root",""),
        c("visibility","visibility",""),c("solo","solo",""),c("trim-start","trim_start",""),c("trim-end","trim_end",""),
        c("new-group","new_group","Ctrl+Alt+Shift+Y"),c("tool-select","select","V"),
        c("tool-hand","hand","H"),c("tool-zoom","zoom","Z"),c("tool-rotate","rotate_tool","W"),
        c("tool-shape","shape_tool","Q"),c("tool-text","text_tool","Ctrl+T"),
        c("lock","lock","Ctrl+L"),c("unlock-all","unlock_all","Ctrl+Shift+L"),
        c("to-front","to_front","Ctrl+Alt+Shift+Up"),c("to-back","to_back","Ctrl+Alt+Shift+Down"),
        c("raise","raise","Ctrl+Alt+Up"),c("lower","lower","Ctrl+Alt+Down"),
        c("guides","guides","Ctrl+Shift+H"),c("safe-guides","safe_guides","'"),
        c("fit","fit_view","Shift+/"),c("actual-size","playback_view","/"),
        c("command-search","command_search","Ctrl+Shift+P"),c("shortcuts","shortcuts","Ctrl+Alt+'"),
        c("search-project","search_project","Ctrl+F"),c("reset-workspace","reset_workspace",""),
        c("assets","assets",""),c("add-key","add_key",""),c("cue","cue",""),c("marker","marker",""),
        c("auto-key","auto_key",""),c("audio-preview","audio_preview",""),c("value-graph","value_graph",""),c("speed-graph","speed_graph",""),
        c("timeline","timeline",""),c("mcp","mcp",""),c("close","close","Esc")
    );
    private static Command c(String id,String label,String shortcut) {return new Command(id,label,shortcut);}
    public static List<Command> list() {return COMMANDS;}
    public static String shortcut(String id) {return COMMANDS.stream().filter(c->c.id.equals(id)).map(Command::shortcut).findFirst().orElse("");}
    public static String lookup(String key,boolean ctrl,boolean shift,boolean alt) {
        String chord=(ctrl?"Ctrl+":"")+(alt?"Alt+":"")+(shift?"Shift+":"")+key;
        return COMMANDS.stream().filter(c->!c.shortcut.isEmpty()&&c.shortcut.equals(chord)).map(Command::id).findFirst().orElse("");
    }
    public static List<String> layerMenu() {
        return List.of("group", "ungroup", "new-group", "move-into", "move-out", "move-root",
                "copy", "cut", "paste", "duplicate", "split", "rename", "visibility", "solo", "lock", "unlock-all",
                "raise", "lower", "to-front", "to-back", "trim-start", "trim-end", "layer-settings", "delete",
                "new-text", "new-rectangle", "new-gif");
    }
    private EffectCommands() {}
}
