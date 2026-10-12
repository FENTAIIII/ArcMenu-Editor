package com.fentai.arcmenu.editor.aui;

import com.mojang.blaze3d.platform.InputConstants;

/** Translates SDL scancodes into AUI's version-neutral logical keyboard codes. */
final class AuiKeyCodes {
    private AuiKeyCodes() {}

    static int translate(int key) {
        if(key>=InputConstants.KEY_A&&key<=InputConstants.KEY_Z)return key-InputConstants.KEY_A+65;
        if(key>=InputConstants.KEY_1&&key<=InputConstants.KEY_9)return key-InputConstants.KEY_1+49;
        if(key>=InputConstants.KEY_F1&&key<=InputConstants.KEY_F12)return key-InputConstants.KEY_F1+290;
        if(key>=InputConstants.KEY_F13&&key<=InputConstants.KEY_F24)return key-InputConstants.KEY_F13+302;
        if(key>=InputConstants.KEY_NUMPAD1&&key<=InputConstants.KEY_NUMPAD9)return key-InputConstants.KEY_NUMPAD1+321;
        return switch(key) {
            case InputConstants.KEY_0 -> 48;
            case InputConstants.KEY_SPACE -> 32;
            case InputConstants.KEY_APOSTROPHE -> 39;
            case InputConstants.KEY_COMMA -> 44;
            case InputConstants.KEY_MINUS -> 45;
            case InputConstants.KEY_PERIOD -> 46;
            case InputConstants.KEY_SLASH -> 47;
            case InputConstants.KEY_SEMICOLON -> 59;
            case InputConstants.KEY_EQUALS -> 61;
            case InputConstants.KEY_LBRACKET -> 91;
            case InputConstants.KEY_BACKSLASH -> 92;
            case InputConstants.KEY_RBRACKET -> 93;
            case InputConstants.KEY_GRAVE -> 96;
            case InputConstants.KEY_ESCAPE -> 256;
            case InputConstants.KEY_RETURN,InputConstants.KEY_NUMPADENTER -> 257;
            case InputConstants.KEY_TAB -> 258;
            case InputConstants.KEY_BACKSPACE -> 259;
            case InputConstants.KEY_INSERT -> 260;
            case InputConstants.KEY_DELETE -> 261;
            case InputConstants.KEY_RIGHT -> 262;
            case InputConstants.KEY_LEFT -> 263;
            case InputConstants.KEY_DOWN -> 264;
            case InputConstants.KEY_UP -> 265;
            case InputConstants.KEY_PAGEUP -> 266;
            case InputConstants.KEY_PAGEDOWN -> 267;
            case InputConstants.KEY_HOME -> 268;
            case InputConstants.KEY_END -> 269;
            case InputConstants.KEY_CAPSLOCK -> 280;
            case InputConstants.KEY_SCROLLLOCK -> 281;
            case 83 -> 282;
            case InputConstants.KEY_PRINTSCREEN -> 283;
            case InputConstants.KEY_PAUSE -> 284;
            case InputConstants.KEY_NUMPAD0 -> 320;
            case 99 -> 330;
            case 84 -> 331;
            case InputConstants.KEY_MULTIPLY -> 332;
            case 86 -> 333;
            case InputConstants.KEY_ADD -> 334;
            case InputConstants.KEY_NUMPADEQUALS -> 336;
            case InputConstants.KEY_LSHIFT -> 340;
            case InputConstants.KEY_LCONTROL -> 341;
            case InputConstants.KEY_LALT -> 342;
            case InputConstants.KEY_LGUI -> 343;
            case InputConstants.KEY_RSHIFT -> 344;
            case InputConstants.KEY_RCONTROL -> 345;
            case InputConstants.KEY_RALT -> 346;
            case InputConstants.KEY_RGUI -> 347;
            case 118 -> 348;
            default -> -1;
        };
    }
}
