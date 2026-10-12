package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class EffectWorkspaceTest {
    @Test void splitDuplicateAndTextShortcutsRemainDistinct() {
        assertEquals("split",EffectCommands.lookup("D",true,true,false));
        assertEquals("duplicate",EffectCommands.lookup("D",true,false,false));
        assertEquals("new-text",EffectCommands.lookup("T",true,true,true));
        assertEquals("tool-text",EffectCommands.lookup("T",true,false,false));
        assertEquals("",EffectCommands.lookup("D",false,true,false));
        var bindings=EffectCommands.list().stream().map(EffectCommands.Command::shortcut).filter(s->!s.isEmpty()).toList();
        assertEquals(bindings.size(),new HashSet<>(bindings).size());
    }
    @Test void zoomAndPanRemainInsideActualWorldCapture() {
        var base=new EditorLayout.Rect(100,60,400,225);
        assertEquals(base,EffectViewNavigation.crop(base,1280,720,1,0,0));
        assertEquals(new EditorLayout.Rect(200,116,200,113),EffectViewNavigation.crop(base,1280,720,2,0,0));
        assertEquals(new EditorLayout.Rect(1080,607,200,113),EffectViewNavigation.crop(base,1280,720,2,100,100));
        assertThrows(IllegalArgumentException.class,()->EffectViewNavigation.crop(base,1280,720,Double.NaN,0,0));
    }
    private static EditorProtocol.NodeSnapshot node(String id,String parent,byte kind,String... pairs) {
        var properties=new ArrayList<EditorProtocol.PropertySnapshot>();for(int i=0;i<pairs.length;i+=2)properties.add(new EditorProtocol.PropertySnapshot(pairs[i],EditorProtocol.PROPERTY_NUMBER,pairs[i+1],List.of()));
        return new EditorProtocol.NodeSnapshot(id,parent,kind,400,600,100,20,0,true,false,properties);
    }
    @Test void canvasNudgeUsesInverseAnimatedParentAxesAndIgnoresWorldPositions() {
        var group=node("group","",EditorProtocol.KIND_GROUP,"rotation.z","90","scale.x","2","scale.y","3","offset.x","200");
        var child=node("child","group",EditorProtocol.KIND_TEXT,"offset.x","5","offset.y","7");
        var delta=EffectTransforms.localDelta(child,List.of(group,child),6,0);
        assertEquals(0,delta.x,1e-8);assertEquals(-2,delta.y,1e-8);assertEquals(0,delta.z,1e-8);
        assertEquals(5,EffectTransforms.number(child,"offset.x",0));
    }
    @Test void singularParentPreventsCoordinateCorruption() {
        var group=node("group","",EditorProtocol.KIND_GROUP,"scale.x","0");var child=node("child","group",EditorProtocol.KIND_TEXT);
        assertThrows(IllegalArgumentException.class,()->EffectTransforms.localDelta(child,List.of(group,child),1,0));
    }
    @Test void editorMetadataAndBackendFieldsCannotPretendToAnimate() {
        assertTrue(EffectPropertyRules.animated(EditorProtocol.KIND_TEXT,"content"));
        assertTrue(EffectPropertyRules.animated(EditorProtocol.KIND_LINE,"width"));
        assertFalse(EffectPropertyRules.animated(EditorProtocol.KIND_TEXT,"id"));
        assertFalse(EffectPropertyRules.animated(EditorProtocol.KIND_TEXT,"shadow"));
        assertFalse(EffectPropertyRules.animated(EditorProtocol.KIND_REGION,"width"));
    }
}
