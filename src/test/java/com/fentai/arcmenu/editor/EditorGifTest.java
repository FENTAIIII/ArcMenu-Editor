package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EditorProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EditorGifTest {
    @Test void gifChooserKeepsOriginalPathsAndFiltersFoldersWithoutExposingInternalImages() {
        var library=new EditorAssetLibrary();
        var images=List.of(image("/still/panel.png"),image("/motion/ui/banner.gif"),image("/motion/icon.gif"),image("/cursor/mouse.gif"));
        library.update(images);
        assertEquals(List.of("","still"),library.folders());
        library.openFolder("still");library.select("/still/panel.png");
        library.selectTool(EditorProtocol.KIND_GIF);
        assertTrue(library.imageMode());assertEquals(EditorProtocol.KIND_GIF,library.imageKind());
        assertNull(library.selected());assertEquals("",library.folder());
        assertEquals(List.of("","motion","motion/ui"),library.folders());
        library.openFolder("motion/ui");library.select("/motion/ui/banner.gif");
        library.update(images);
        assertEquals("/motion/ui/banner.gif",library.selected().path());
        assertEquals(List.of("banner.gif"),library.entries().stream().map(EditorAssetLibrary.Entry::name).toList());
        library.selectTool(EditorProtocol.KIND_IMAGE);
        assertEquals(List.of("","still"),library.folders());assertNull(library.selected());
        assertFalse(EditorAssetLibrary.isUserImage("/tooltip/default.gif"));
        assertFalse(EditorAssetLibrary.isUserImage("/motion/.cache/banner.gif"));
    }

    @Test void gifThumbnailsUseTheFirstGeneratedPackFrameAndDropClassificationNeverTreatsThemAsAudio() {
        assertEquals("textures/gifs/ui/banner.gif/0.png",EditorAssetLibrary.texturePath("/ui/banner.gif"));
        assertEquals("textures/gifs/ui/banner.gif/0.png",EditorAssetLibrary.texturePath("ui\\banner.gif"));
        assertEquals("textures/images/ui/panel.png",EditorAssetLibrary.texturePath("/ui/panel.png"));
        assertTrue(EditorImageTypes.image("/arcpack-demo/MOTION.GIF"));
        assertEquals(EditorProtocol.KIND_GIF,EditorImageTypes.kind("/arcpack-demo/MOTION.GIF"));
        assertTrue(EditorImageTypes.image("/panel.webp"));assertFalse(EditorImageTypes.image("/music.ogg"));
        assertFalse(EditorImageTypes.image("/banner.gif.ogg"));
        assertFalse(EditorImageTypes.matches(EditorProtocol.KIND_GIF,"/panel.png"));
        assertFalse(EditorImageTypes.matches(EditorProtocol.KIND_IMAGE,"/banner.gif"));
        assertEquals(new EditorLayout.Rect(10,32,100,25),EditorImagePreview.fit(400,100,new EditorLayout.Rect(10,20,100,50)));
    }

    @Test void gifUsesImageGeometryCalibrationAndCanPersistWithoutChangingStillImages(@TempDir Path directory) {
        var calibration=new ElementSelectionCalibration();
        assertEquals(calibration.get(EditorProtocol.KIND_IMAGE),calibration.get(EditorProtocol.KIND_GIF));
        var gif=calibration.get(EditorProtocol.KIND_GIF);
        assertEquals(32,gif.serverRightX(10,gif.proxyX(10,44)+gif.proxyWidth(44)/2),1e-8);
        calibration.set(EditorProtocol.KIND_GIF,.2,.1,1.2,.8);
        calibration.save(directory.resolve("calibration.properties"));
        var restored=new ElementSelectionCalibration();restored.load(directory.resolve("calibration.properties"));
        assertEquals(calibration.get(EditorProtocol.KIND_GIF),restored.get(EditorProtocol.KIND_GIF));
        assertEquals(ElementSelectionCalibration.defaultFor(EditorProtocol.KIND_IMAGE),restored.get(EditorProtocol.KIND_IMAGE));
    }

    @Test void appendedGifKindAndFractionalSpeedRoundTripWithoutChangingExistingWireKinds() {
        assertEquals(7,EditorProtocol.KIND_IMAGE);assertEquals(8,EditorProtocol.KIND_LINE);assertEquals(9,EditorProtocol.KIND_GIF);assertEquals(11,EditorProtocol.VERSION);
        var create=new EditorProtocol.CreatePacket(4,EditorProtocol.TAB_FRONTEND,EditorProtocol.KIND_GIF,"group","/example.gif");
        assertEquals(create,EditorProtocol.decode(EditorProtocol.encode(create)));
        var node=new EditorProtocol.NodeSnapshot("gif","group",EditorProtocol.KIND_GIF,20,30,120,60,0,true,false,List.of(
                new EditorProtocol.PropertySnapshot("source",EditorProtocol.PROPERTY_TEXT,"/example.gif"),
                new EditorProtocol.PropertySnapshot("speed",EditorProtocol.PROPERTY_NUMBER,"0.5")));
        var snapshot=new EditorProtocol.SnapshotPacket(5,"gif-project",800,450,true,false,"26.3",List.of(node),List.of(),List.of(image("/example.gif")),List.of());
        assertEquals(snapshot,EditorProtocol.decode(EditorProtocol.encode(snapshot)));
        var state=new EditorState();state.update(snapshot);assertEquals(node,state.find("gif"));
        for(String value:List.of("0.5","2")) {
            var speed=new EditorProtocol.SetPropertyPacket(5,EditorProtocol.TAB_FRONTEND,"gif","speed",value);
            assertEquals(speed,EditorProtocol.decode(EditorProtocol.encode(speed)));
        }
        assertTrue(EffectPropertyRules.animated(EditorProtocol.KIND_GIF,"speed"));
        assertTrue(EffectPropertyRules.animated(EditorProtocol.KIND_GIF,"source"));
        assertFalse(EffectPropertyRules.animated(EditorProtocol.KIND_GIF,"gifFrame"));
        assertFalse(EffectPropertyRules.animated(EditorProtocol.KIND_IMAGE,"speed"));
    }
    private static EditorProtocol.ImageSnapshot image(String path) { return new EditorProtocol.ImageSnapshot(path,400,100); }
}
