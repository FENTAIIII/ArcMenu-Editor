package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.EffectProtocol;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class EffectCurvePreviewTest {
    private static EffectProtocol.Key key(int frame, String value, String interpolation) { return new EffectProtocol.Key(frame, value, interpolation, 1.0 / 3, 0, 2.0 / 3, 1); }
    private static EffectProtocol.Track track(EffectProtocol.Key... keys) { return new EffectProtocol.Track("title", "offset.x", true, List.of(keys)); }
    @Test void temporalBezierInvertsTimeInsteadOfUsingTheCurveParameter() {
        var curve = track(new EffectProtocol.Key(0, "0", "bezier", .8, .1, .9, .7), key(100, "100", "linear"));
        assertEquals(15.625, EffectCurvePreview.value(curve, 47.96875), .0000001);
        assertEquals(0, EffectCurvePreview.value(curve, -10));
        assertEquals(100, EffectCurvePreview.value(curve, 110));
        var overshoot = track(new EffectProtocol.Key(0, "0", "bezier", 1.0 / 3, 2, 2.0 / 3, 2), key(100, "100", "linear"));
        assertEquals(162.5, EffectCurvePreview.value(overshoot, 50), .0000001);
    }
    @Test void automaticBezierUsesNeighborTimeSpansAndHermiteTangents() {
        var irregular = track(key(0, "0", "auto-bezier"), key(10, "10", "auto-bezier"), key(30, "0", "auto-bezier"), key(60, "20", "auto-bezier"));
        assertEquals(6.25, EffectCurvePreview.value(irregular, 5), .0000001);
        assertEquals(4.5, EffectCurvePreview.value(irregular, 20), .0000001);
        assertEquals(0, EffectCurvePreview.value(irregular, 30));
    }
    @Test void easePresetsHaveSymmetricMidpointsAndAConstantEndpointRange() {
        var easeIn = track(key(0, "0", "ease-in"), key(100, "100", "linear"));
        var easeOut = track(key(0, "0", "ease-out"), key(100, "100", "linear"));
        var symmetric = track(key(0, "0", "ease-in-out"), key(100, "100", "linear"));
        assertEquals(31.5356812506, EffectCurvePreview.value(easeIn, 50), .000001);
        assertEquals(68.4643187494, EffectCurvePreview.value(easeOut, 50), .000001);
        assertEquals(50, EffectCurvePreview.value(symmetric, 50), .000001);
    }
    @Test void velocityIsMeasuredPerSecondAndHoldJumpsDoNotBecomeSpeedSpikes() {
        var linear = track(key(0, "0", "linear"), key(10, "20", "linear"));
        assertEquals(48, EffectCurvePreview.speed(linear, 5, 24), .000001);
        assertEquals(48, EffectCurvePreview.speed(linear, 0, 24), .000001);
        assertEquals(0, EffectCurvePreview.speed(linear, -1, 24));
        var hold = track(key(0, "2", "hold"), key(10, "100", "hold"));
        assertEquals(2, EffectCurvePreview.value(hold, 9.99));
        assertEquals(100, EffectCurvePreview.value(hold, 10));
        assertEquals(0, EffectCurvePreview.speed(hold, 10, 24));
    }
    @Test void graphTracksRejectDiscreteAndNonFiniteValues() {
        assertTrue(EffectCurvePreview.numeric(track(key(0, "1.25", "linear"))));
        assertFalse(EffectCurvePreview.numeric(track(key(0, "true", "hold"))));
        assertFalse(EffectCurvePreview.numeric(track(key(0, "Infinity", "linear"))));
        assertFalse(EffectCurvePreview.numeric(new EffectProtocol.Track("title", "color", true, List.of(key(0, "123456", "linear")))));
    }
    @Test void presetHandlesCanBeEditedWithoutChangingTheExistingCurve() {
        for (String mode : List.of("ease-in", "ease-out", "ease-in-out")) {
            var original = key(0, "-2", mode); var converted = EffectCurvePreview.explicitBezier(original);
            for (int frame = 0; frame <= 100; frame++)
                assertEquals(EffectCurvePreview.value(track(original, key(100, "8", "linear")), frame),
                    EffectCurvePreview.value(track(converted, key(100, "8", "linear")), frame), .0000001);
        }
    }
}
