package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EffectProtocol;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class EffectKeyframeGesturesTest {
    private static EffectKeyframeGestures.Ref ref(String property, int frame, String value) {
        return new EffectKeyframeGestures.Ref("title", property, new EffectProtocol.Key(frame, value, "bezier", .2, -.5, .8, 1.5));
    }
    @Test void horizontalTimingMovesNeverRewriteSpatialValues() {
        var payload = EffectKeyframeGestures.moved(List.of(ref("offset.y", 10, "71.25"), ref("visible", 20, "false")), 5, "", "", 0);
        var entries = payload.getAsJsonArray("keys");
        assertEquals(15, entries.get(0).getAsJsonObject().get("newFrame").getAsInt());
        assertFalse(entries.get(0).getAsJsonObject().has("value"));
        assertFalse(entries.get(1).getAsJsonObject().has("value"));
    }
    @Test void valueGraphMovesChangeOnlyTheGrabbedProperty() {
        var payload = EffectKeyframeGestures.moved(List.of(ref("offset.y", 10, "5"), ref("offset.x", 20, "13")), 2, "title", "offset.y", -2);
        assertEquals("3.0", payload.getAsJsonArray("keys").get(0).getAsJsonObject().get("value").getAsString());
        assertFalse(payload.getAsJsonArray("keys").get(1).getAsJsonObject().has("value"));
    }
    @Test void mappingUsesThePointerOriginAndDoesNotJumpAtRoundingBoundaries() {
        var mapping = new EffectKeyframeGestures.Mapping(200, 600, 20, 120, 100, 300, -10, 30, 200);
        assertEquals(0, mapping.delta(232.6, 232.5));
        assertEquals(10, mapping.delta(282.5, 232.5));
        assertEquals(10, mapping.value(200), .000001);
        assertEquals(20, mapping.value(150), .000001);
    }
    @Test void groupBoundsClampTheWholeSelectionWithoutDroppingKeys() {
        var keys = List.of(ref("offset.y", 2, "5"), ref("offset.y", 90, "20"));
        assertEquals(-2, EffectKeyframeGestures.boundedDelta(keys, -40, 100));
        assertEquals(10, EffectKeyframeGestures.boundedDelta(keys, 40, 100));
    }
    @Test void clipboardRetainsValuesAndCurvesAndFitsAsACompleteGroup() {
        var payload = EffectKeyframeGestures.pasted(List.of(ref("offset.y", 10, "5"), ref("offset.y", 40, "20")), 95, 100);
        var first = payload.getAsJsonArray("keys").get(0).getAsJsonObject();
        var last = payload.getAsJsonArray("keys").get(1).getAsJsonObject();
        assertEquals(70, first.get("frame").getAsInt()); assertEquals(100, last.get("frame").getAsInt());
        assertEquals("5", first.get("value").getAsString()); assertEquals(-.5, first.get("y1").getAsDouble());
    }
    @Test void marqueeWorksInEveryDragDirection() {
        assertTrue(EffectKeyframeGestures.inside(5, 8, 10, 10, 0, 0));
        assertTrue(EffectKeyframeGestures.inside(5, 8, 0, 0, 10, 10));
        assertFalse(EffectKeyframeGestures.inside(11, 8, 10, 10, 0, 0));
    }
    @Test void reversingTimeReversesBezierHandlesAndAttachesTheOriginalIncomingSegment() {
        var a = new EffectKeyframeGestures.Ref("title", "offset.y", new EffectProtocol.Key(0, "0", "bezier", .15, .25, .65, 1.2));
        var b = ref("offset.y", 30, "100");
        var entries = EffectKeyframeGestures.reversed(List.of(a, b)).getAsJsonArray("keys");
        var reversedStart = entries.get(1).getAsJsonObject();
        assertEquals(0, reversedStart.get("newFrame").getAsInt());
        assertEquals(.35, reversedStart.get("x1").getAsDouble(), .000001);
        assertEquals(-.2, reversedStart.get("y1").getAsDouble(), .000001);
        assertEquals(.85, reversedStart.get("x2").getAsDouble(), .000001);
        assertEquals(.75, reversedStart.get("y2").getAsDouble(), .000001);
        var original = new EffectProtocol.Track("title", "offset.y", true, List.of(a.key(), b.key()));
        var reversed = new EffectProtocol.Track("title", "offset.y", true, List.of(new EffectProtocol.Key(0, "100", "bezier", .35, -.2, .85, .75), new EffectProtocol.Key(30, "0", "linear", .333, 0, .667, 1)));
        for (int frame = 0; frame <= 30; frame++) assertEquals(EffectCurvePreview.value(original, 30 - frame), EffectCurvePreview.value(reversed, frame), .000001);
    }
    @Test void easyEaseChangesBothSidesOfAnInteriorKeyWithoutFlatteningNeighborTangents() {
        var a = new EffectProtocol.Key(0, "0", "linear", .333, 0, .667, 1);
        var b = new EffectProtocol.Key(10, "10", "linear", .333, 0, .667, 1);
        var c = new EffectProtocol.Key(20, "30", "linear", .333, 0, .667, 1);
        var track = new EffectProtocol.Track("title", "offset.y", true, List.of(a, b, c));
        var selected = List.of(new EffectKeyframeGestures.Ref("title", "offset.y", b));
        var both = EffectKeyframeGestures.eased(selected, List.of(track), "ease").getAsJsonArray("keys");
        assertEquals(2, both.size());
        var outgoing = both.get(0).getAsJsonObject(); var incoming = both.get(1).getAsJsonObject();
        assertEquals(0, outgoing.get("y1").getAsDouble()); assertEquals(2.0 / 3, outgoing.get("y2").getAsDouble(), .000001);
        assertEquals(1.0 / 3, incoming.get("y1").getAsDouble(), .000001); assertEquals(1, incoming.get("y2").getAsDouble());
        assertEquals(1, EffectKeyframeGestures.eased(selected, List.of(track), "ease-in").getAsJsonArray("keys").size());
        assertEquals(1, EffectKeyframeGestures.eased(selected, List.of(track), "ease-out").getAsJsonArray("keys").size());
    }
    @Test void propertyShortcutTogglesAllRequestedAxesAtTheCurrentFrameWithSourceValues() {
        var payload = EffectKeyframeGestures.toggled(List.of(ref("offset.x", 0, "12.5"), ref("offset.y", 20, "-3")), 17);
        assertEquals(2, payload.getAsJsonArray("keys").size());
        for (var entry : payload.getAsJsonArray("keys")) {
            var key = entry.getAsJsonObject(); assertEquals(17, key.get("frame").getAsInt()); assertEquals("linear", key.get("interpolation").getAsString());
        }
        assertEquals("12.5", payload.getAsJsonArray("keys").get(0).getAsJsonObject().get("value").getAsString());
        assertEquals("-3", payload.getAsJsonArray("keys").get(1).getAsJsonObject().get("value").getAsString());
    }
}
