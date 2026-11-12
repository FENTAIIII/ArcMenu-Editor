package com.fentai.arcmenu.editor;

import java.util.List;

/**
 * PowerPoint-style alignment for elements dragged on the menu canvas.
 * <p>
 * Works in menu coordinates (x grows to the right, y grows upwards). The moving box snaps its left edge, centre or
 * right edge to the nearest vertical line of the canvas or another element, and likewise for the horizontal edges.
 */
final class CanvasSnapping
{
    /** No snap on either axis. */
    static final Result NONE = new Result(0.0, 0.0, Double.NaN, Double.NaN);

    private CanvasSnapping()
    {}

    /** Axis-aligned rectangle in menu coordinates. */
    record Box(double left, double right, double bottom, double top)
    {
        double centerX()
        {
            return (left + right) / 2.0;
        }

        double centerY()
        {
            return (bottom + top) / 2.0;
        }

        double[] verticalLines()
        {
            return new double[] { left, centerX(), right };
        }

        double[] horizontalLines()
        {
            return new double[] { bottom, centerY(), top };
        }
    }

    /**
     * Correction to apply to the moving box and the guide line it snapped to on each axis.
     * A guide is {@code NaN} when that axis did not snap.
     */
    record Result(double deltaX, double deltaY, double guideX, double guideY)
    {
        boolean snappedX()
        {
            return !Double.isNaN(guideX);
        }

        boolean snappedY()
        {
            return !Double.isNaN(guideY);
        }
    }

    /**
     * Finds the smallest correction within {@code threshold} that aligns {@code moving} with the canvas or one of the
     * {@code others}. Each axis is snapped independently.
     */
    static Result snap(Box moving, Box canvas, List<Box> others, double threshold)
    {
        Axis horizontal = new Axis(moving.verticalLines(), threshold);
        Axis vertical = new Axis(moving.horizontalLines(), threshold);
        horizontal.offer(canvas.verticalLines());
        vertical.offer(canvas.horizontalLines());
        for (Box other : others)
        {
            horizontal.offer(other.verticalLines());
            vertical.offer(other.horizontalLines());
        }
        if (!horizontal.snapped() && !vertical.snapped())
        {
            return NONE;
        }
        return new Result(horizontal.delta(), vertical.delta(), horizontal.guide, vertical.guide);
    }

    /** Best candidate on one axis so far. */
    private static final class Axis
    {
        private final double[] movingLines;
        private double bestDistance;
        private double delta;
        private double guide = Double.NaN;

        Axis(double[] movingLines, double threshold)
        {
            this.movingLines = movingLines;
            this.bestDistance = threshold;
        }

        void offer(double[] targetLines)
        {
            for (double target : targetLines)
            {
                for (double line : movingLines)
                {
                    double distance = Math.abs(target - line);
                    if (distance <= bestDistance)
                    {
                        bestDistance = distance;
                        delta = target - line;
                        guide = target;
                    }
                }
            }
        }

        boolean snapped()
        {
            return !Double.isNaN(guide);
        }

        double delta()
        {
            return snapped() ? delta : 0.0;
        }
    }
}
