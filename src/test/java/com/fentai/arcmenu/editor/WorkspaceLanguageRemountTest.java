package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.editor.aui.internal.element.AbstractText;
import com.fentai.arcmenu.editor.aui.internal.element.Body;
import com.fentai.arcmenu.editor.aui.internal.init.Document;
import com.fentai.arcmenu.editor.aui.internal.init.Element;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

final class WorkspaceLanguageRemountTest {
    @Test void languageRemountPreservesTheFocusedUnsavedFieldDespiteTheActualBlurChangeCallback() throws Exception {
        var register=Class.forName("com.fentai.arcmenu.editor.aui.AuiElements").getDeclaredMethod("register");
        register.setAccessible(true);register.invoke(null);
        var document=new Document("language-remount-test",false);
        document.body=new Body(document);
        document.body.setInnerHTML("<div id='modal_host'><input id='field_id'/><input id='field_folder'/></div>");
        var fields=new LinkedHashMap<String,String>();fields.put("id","");fields.put("folder","");
        var changes=new ArrayList<String>();
        document.body.addEventListener("input",event->{
            if(event.target instanceof Element field)fields.put(field.id.substring(6),field.getValue());
        });
        document.body.addEventListener("change",event->{
            if(event.target instanceof Element field){
                Element current=document.getElementById(field.id);
                String value=current==null?"":current.getValue();
                changes.add(field.id+"="+value);fields.put(field.id.substring(6),value);
            }
        });
        var id=(AbstractText)document.getElementById("field_id");
        id.focus();id.insertText("space-locale-draft");
        var folder=(AbstractText)document.getElementById("field_folder");
        folder.focus();folder.insertText("Unsaved locale draft");
        assertEquals("Unsaved locale draft",fields.get("folder"));

        Element host=document.getElementById("modal_host");
        WorkspaceScreen.remountFields(fields,
                ()->host.setInnerHTML("<label>Translated title</label><input id='field_id'/><input id='field_folder'/>") ,
                (key,value)->document.getElementById("field_"+key).setValue(value));
        document.getElementById("field_folder").focus();

        assertTrue(changes.contains("field_folder="),"The real teardown change callback must be exercised");
        assertEquals("space-locale-draft",fields.get("id"));
        assertEquals("Unsaved locale draft",fields.get("folder"));
        assertEquals("space-locale-draft",document.getElementById("field_id").getValue());
        assertEquals("Unsaved locale draft",document.getElementById("field_folder").getValue());
        assertEquals("field_folder",document.getFocusedElement().id);
    }
}
