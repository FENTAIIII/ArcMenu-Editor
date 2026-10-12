package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import java.util.*;

/** Converts canvas movement to the selected layer's local axes through animated parent transforms. */
public final class EffectTransforms {
    public static double number(EditorProtocol.NodeSnapshot node,String key,double fallback){return node.properties().stream().filter(p->p.key().equals(key)).map(EditorProtocol.PropertySnapshot::value).mapToDouble(Double::parseDouble).findFirst().orElse(fallback);}
    public static Vector3d localDelta(EditorProtocol.NodeSnapshot node,List<EditorProtocol.NodeSnapshot> nodes,double x,double y) {
        Map<String,EditorProtocol.NodeSnapshot> indexed=new HashMap<>();nodes.forEach(n->indexed.put(n.id(),n));var parents=new ArrayList<EditorProtocol.NodeSnapshot>();var seen=new HashSet<String>();String id=node.parentId();
        while(!id.isEmpty()&&seen.add(id)){var parent=indexed.get(id);if(parent==null)break;parents.add(parent);id=parent.parentId();}
        Collections.reverse(parents);var matrix=new Matrix4d();
        for(var parent:parents)matrix.translate(number(parent,"offset.x",0),number(parent,"offset.y",0),number(parent,"offset.z",0)).rotateZ(Math.toRadians(number(parent,"rotation.z",0))).rotateX(Math.toRadians(number(parent,"rotation.x",0))).rotateY(Math.toRadians(number(parent,"rotation.y",0))).scale(number(parent,"scale.x",1),number(parent,"scale.y",1),number(parent,"scale.z",1));
        if(Math.abs(matrix.determinant())<1e-9)throw new IllegalArgumentException("The parent transform is not invertible");
        return matrix.invert().transformDirection(new Vector3d(x,y,0));
    }
    private EffectTransforms(){}
}
