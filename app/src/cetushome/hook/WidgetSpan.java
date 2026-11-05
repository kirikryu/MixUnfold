package cetushome.hook;

/**
 * Pure span-fitting math for the runtime widget shrink (hook 17).
 *
 * Background (4.39.12 decompile): every workspace item is parsed from the
 * favorites DB through ItemInfo.load(Context, Cursor); an item whose
 * cellX+spanX exceeds the live column count then throws inside
 * CellLayout.updateCellOccupiedMarks (mOccupiedCell is indexed by
 * cellX..cellX+spanX with no bounds guard) and Launcher.bindAppWidget
 * swallows the RuntimeException ("server side exception,ignore it") —
 * the item is never added to the workspace and simply does not show,
 * while its DB row stays intact. Rows like that exist whenever the grid
 * narrows below the count the layout was authored for: a full-width 2x4
 * placed under the stock 4-column grid, or any item sitting at cellX>=1
 * that was legal in 4-column coordinates.
 *
 * This module only forces the column count at the getter level, so no
 * stock migration ever rewrites those rows. The fix clamps the in-memory
 * ItemInfo right after the DB parse — the DB itself is never written, so
 * switching the column setting back restores the original sizes on the
 * next load (non-destructive by design).
 *
 * Only the X side is clamped: the module never changes the row count, so
 * spanY/cellY cannot overflow the stock grid. Folder contents (container
 * != desktop) use their own grid and are excluded by the caller.
 */
final class WidgetSpan {

    private WidgetSpan() {
    }

    /**
     * Shrink a span so it fits the live column count. countX < 1 means the
     * effective grid is unknown — return the span unchanged rather than
     * inventing a layout. Spans are never grown (0-span degenerate rows
     * from NULL columns pass through untouched; stock code owns them).
     */
    static int fitSpanX(int spanX, int countX) {
        if (countX < 1 || spanX <= countX) {
            return spanX;
        }
        return countX;
    }

    /**
     * Slide an item left so the (already fitted) span stays inside the
     * grid: cellX + spanX must not exceed countX. Negative coordinates
     * (corrupt rows) are raised to 0. Assumes spanX has been through
     * {@link #fitSpanX(int, int)}.
     */
    static int fitCellX(int cellX, int spanX, int countX) {
        if (countX < 1 || cellX < 0) {
            return Math.max(0, cellX);
        }
        int limit = countX - spanX;
        if (limit < 0) {
            limit = 0;
        }
        return cellX > limit ? limit : cellX;
    }

    /** True when either clamp would change the values (log gate). */
    static boolean needsFit(int spanX, int cellX, int countX) {
        if (countX < 1) {
            return false;
        }
        int fittedSpan = fitSpanX(spanX, countX);
        return fittedSpan != spanX || fitCellX(cellX, fittedSpan, countX) != cellX;
    }
}
