package com.fentai.arcmenu.editor;

import com.fentai.arcmenu.protocol.VisionProtocol;
import com.fentai.arcmenu.protocol.VisionVisuals;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class VisionVisualProtocolTest {
    private final VisionVisuals.Vector x = new VisionVisuals.Vector(1, 0, 0);
    private final VisionVisuals.Vector y = new VisionVisuals.Vector(0, 1, 0);
    private final VisionVisuals.Plane plane = new VisionVisuals.Plane(new VisionVisuals.Vector(0, 0, 0), x, y, 40);

    @Test void followAndDetachRoundTripWithImmutableGeometry() {
        var matrix = new ArrayList<Float>();
        for (int i = 0; i < 16; i++) matrix.add(i % 5 == 0 ? 1f : 0f);
        var packet = new VisionVisuals.Follow(12, 8, plane, matrix, x, y, List.of(new VisionVisuals.Region(0, 0, 10, 10)), 0);
        matrix.set(12, 999f);
        assertEquals(0f, packet.matrix().get(12));
        assertEquals(packet, VisionProtocol.decode(VisionProtocol.encode(packet)));
        var detach = VisionVisuals.Follow.detach(12, 8);
        assertEquals(detach, VisionProtocol.decode(VisionProtocol.encode(detach)));
        var bytes = VisionProtocol.encode(packet);
        assertThrows(IllegalArgumentException.class, () -> VisionProtocol.decode(Arrays.copyOf(bytes, bytes.length - 1)));
        matrix.set(3, 1f);
        assertThrows(IllegalArgumentException.class, () -> new VisionVisuals.Follow(12, 8, plane, matrix, x, y, List.of(), -1));
    }

    @Test void nativeProjectionKeepsOutsidePointsAndRegionPriority() {
        var eye = new VisionVisuals.Vector(0, 0, 3);
        assertEquals(new VisionVisuals.Point(40, -20), plane.project(eye, new VisionVisuals.Vector(1, -.5, -3)));
        assertNull(plane.project(eye, new VisionVisuals.Vector(0, 0, 1)));
        var regions = List.of(new VisionVisuals.Region(0, 0, 2, 2), new VisionVisuals.Region(0, 0, 10, 10));
        assertEquals(0, VisionVisuals.hit(regions, new VisionVisuals.Point(1, 1)));
        assertEquals(1, VisionVisuals.hit(regions, new VisionVisuals.Point(3, 3)));
        assertEquals(-1, VisionVisuals.hit(regions, null));
    }
}
