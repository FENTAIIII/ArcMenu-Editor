package com.fentai.arcmenu.editor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasSnappingTest
{
    private static final CanvasSnapping.Box CANVAS = new CanvasSnapping.Box(-160, 160, -90, 90);

    @Test
    void centreSnapsToCanvasCentreLines()
    {
        CanvasSnapping.Box moving = new CanvasSnapping.Box(-12, 18, -7, 13);

        CanvasSnapping.Result result = CanvasSnapping.snap(moving, CANVAS, List.of(), 4);

        assertEquals(-3, result.deltaX(), 1e-9);
        assertEquals(0, result.guideX(), 1e-9);
        assertEquals(-3, result.deltaY(), 1e-9);
        assertEquals(0, result.guideY(), 1e-9);
    }

    @Test
    void edgeSnapsToCanvasEdge()
    {
        CanvasSnapping.Box moving = new CanvasSnapping.Box(118, 158, 40, 60);

        CanvasSnapping.Result result = CanvasSnapping.snap(moving, CANVAS, List.of(), 4);

        assertEquals(2, result.deltaX(), 1e-9);
        assertEquals(160, result.guideX(), 1e-9);
        assertFalse(result.snappedY());
    }

    @Test
    void nearestCandidateWinsAcrossOtherElements()
    {
        CanvasSnapping.Box moving = new CanvasSnapping.Box(41, 61, 50, 70);
        CanvasSnapping.Box farther = new CanvasSnapping.Box(0, 38, -40, -20);
        CanvasSnapping.Box nearer = new CanvasSnapping.Box(62, 100, -40, -20);

        CanvasSnapping.Result result = CanvasSnapping.snap(moving, CANVAS, List.of(farther, nearer), 4);

        assertEquals(1, result.deltaX(), 1e-9);
        assertEquals(62, result.guideX(), 1e-9);
        assertFalse(result.snappedY());
    }

    @Test
    void nothingWithinThresholdLeavesPositionUnchanged()
    {
        CanvasSnapping.Box moving = new CanvasSnapping.Box(30, 50, 30, 50);

        CanvasSnapping.Result result = CanvasSnapping.snap(moving, CANVAS, List.of(), 4);

        assertSame(CanvasSnapping.NONE, result);
        assertTrue(Double.isNaN(result.guideX()));
    }
}
