package com.fentai.arcmenu.editor;

import java.util.HashSet;
import java.util.Set;

/** Consumed keys stay captured through their release even when closing a menu or text editor. */
public final class VisionInputKeys {
    public enum Action { PASS, CONSUME, CLOSE, JUMP, TEXT }
    private final Set<Integer> captured = new HashSet<>();

    public Action accept(int key, int eventAction, boolean controlling, boolean editing, boolean escape, boolean jump) {
        if (captured.contains(key)) {
            if (eventAction == 0) captured.remove(key);
            return eventAction != 0 && controlling && editing ? Action.TEXT : Action.CONSUME;
        }
        if (!controlling) return Action.PASS;
        if (editing) {
            if (eventAction != 0) captured.add(key);
            return eventAction == 0 ? Action.CONSUME : Action.TEXT;
        }
        if (!escape && !jump) return Action.PASS;
        if (eventAction != 0) captured.add(key);
        if (eventAction != 1) return Action.CONSUME;
        return escape ? Action.CLOSE : Action.JUMP;
    }

    public void reset() { captured.clear(); }
}
