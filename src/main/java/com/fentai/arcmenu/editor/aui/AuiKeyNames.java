package com.fentai.arcmenu.editor.aui;

/** Names AUI's logical keys without loading a window-system native library. */
public final class AuiKeyNames {
    private AuiKeyNames() {}

    public static String name(int keyCode,int scanCode) {
        if(keyCode>=65&&keyCode<=90)return Character.toString((char)(keyCode+32));
        if(keyCode>=32&&keyCode<=126)return Character.toString((char)keyCode);
        if(keyCode>=290&&keyCode<=314)return "F"+(keyCode-289);
        if(keyCode>=320&&keyCode<=329)return Integer.toString(keyCode-320);
        return switch(keyCode) {
            case 330 -> ".";case 331 -> "/";case 332 -> "*";case 333 -> "-";case 334 -> "+";
            case 335 -> "Enter";case 336 -> "=";default -> "Unidentified";
        };
    }
}
