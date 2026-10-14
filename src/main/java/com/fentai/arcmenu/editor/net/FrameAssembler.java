package com.fentai.arcmenu.editor.net;

import com.fentai.arcmenu.protocol.EditorProtocol;

public final class FrameAssembler {
    private final EditorProtocol.FrameAssembler shared = new EditorProtocol.FrameAssembler();

    public byte[] accept(byte[] bytes) {
        return shared.accept(bytes);
    }

    public void clear() {
        shared.clear();
    }
}
