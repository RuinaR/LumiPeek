package io.lumipeek.core;

import java.awt.*;
import java.util.List;
import java.util.Optional;

/** Pure geometry: moves only a future preview, never the mascot. No window or desktop APIs. */
public final class PreviewFollow {
    private Rectangle screen;
    private double x, y;
    private boolean left;
    public Optional<Rectangle> update(Rectangle mascot, boolean lookRight, List<Rectangle> screens,
                                      Dimension size, double elapsedSeconds) {
        if (mascot.width <= 0 || mascot.height <= 0 || size.width < 1 || size.height < 1
                || size.width > 220 || size.height > 240 || !Double.isFinite(elapsedSeconds)) { reset(); return Optional.empty(); }
        Rectangle selected = null; long area = 0;
        for (Rectangle candidate : screens) {
            Rectangle intersection = mascot.intersection(candidate);
            long next = Math.max(0, intersection.width) * (long) Math.max(0, intersection.height);
            if (next > area) { selected = new Rectangle(candidate); area = next; }
        }
        if (selected == null || selected.width < size.width + 16 || selected.height < size.height + 16) {
            reset(); return Optional.empty();
        }
        boolean changed = !selected.equals(screen);
        if (changed) left = !lookRight;
        double rightX = mascot.getMaxX() + 48, leftX = mascot.x - 48.0 - size.width;
        boolean rightFits = rightX + size.width <= selected.getMaxX() - 8;
        boolean leftFits = leftX >= selected.x + 8;
        if (left && (!leftFits || lookRight && rightX + size.width + 32 <= selected.getMaxX() - 8)) left = false;
        if (!left && (!rightFits || !lookRight && leftX - 32 >= selected.x + 8)) left = true;
        double targetX = clamp(left ? leftX : rightX, selected.x + 8, selected.getMaxX() - size.width - 8);
        double targetY = clamp(mascot.y - 24, selected.y + 8, selected.getMaxY() - size.height - 8);
        if (changed) { x = targetX; y = targetY; }
        else {
            double alpha = 1 - Math.exp(-Math.max(0, Math.min(0.1, elapsedSeconds)) / 0.22);
            x += (targetX - x) * alpha; y += (targetY - y) * alpha;
        }
        screen = selected;
        x = clamp(x, selected.x + 8, selected.getMaxX() - size.width - 8);
        y = clamp(y, selected.y + 8, selected.getMaxY() - size.height - 8);
        return Optional.of(new Rectangle((int) Math.round(x), (int) Math.round(y), size.width, size.height));
    }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    public void reset() { screen = null; }
}
