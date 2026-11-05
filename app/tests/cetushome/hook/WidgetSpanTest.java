package cetushome.hook;

/** JVM regression tests for the runtime widget-shrink math (hook 17,
 *  WidgetSpan). Pins the fitting semantics from DESIGN.md §14: spans only
 *  shrink (never grow), positions only slide left (never below 0), rows
 *  that already fit are byte-identical no-ops, and a degenerate/unknown
 *  grid is passed through untouched instead of guessed. Run via
 *  tools/run-tests.sh. */
public final class WidgetSpanTest {

    private static int failures;

    public static void main(String[] args) {
        // --- rows that already fit are untouched -------------------------
        check("fitting span is a no-op", 3, WidgetSpan.fitSpanX(3, 3));
        check("fitting cell is a no-op", 1, WidgetSpan.fitCellX(1, 2, 3));
        check("stock 4-column row needs no fit",
                false, WidgetSpan.needsFit(4, 0, 4));
        check("row that fits needs no fit",
                false, WidgetSpan.needsFit(3, 0, 3));

        // --- the reported bug: full-width 2x4 under 3 columns -------------
        // spanX 4 -> 3 so the widget renders shrunk (2x3), DB row intact
        check("2x4 span shrinks to 3", 3, WidgetSpan.fitSpanX(4, 3));
        check("2x4 at cellX 0 stays at 0", 0, WidgetSpan.fitCellX(0, 3, 3));
        check("2x4 row needs fit", true, WidgetSpan.needsFit(4, 0, 3));

        // --- legal-in-4-columns positions slide left ----------------------
        // a 2x3 placed at cellX=1 was valid on the stock grid and would
        // throw inside CellLayout under 3 columns; it must slide to 0
        check("2x3 at cellX 1 slides to 0", 0, WidgetSpan.fitCellX(1, 3, 3));
        check("2x3 at cellX 1 needs fit", true, WidgetSpan.needsFit(3, 1, 3));
        check("icon at cellX 3 slides to 2", 2, WidgetSpan.fitCellX(3, 1, 3));
        check("2x1 at cellX 2 slides to 1", 1, WidgetSpan.fitCellX(2, 2, 3));

        // --- other column settings ----------------------------------------
        check("span clamps to 2 columns", 2, WidgetSpan.fitSpanX(3, 2));
        check("cell clamps to 2 columns", 0, WidgetSpan.fitCellX(1, 2, 2));
        check("6-column setting never shrinks a 4-span",
                4, WidgetSpan.fitSpanX(4, 6));

        // --- spans never grow, positions never go negative -----------------
        check("span never grows", 2, WidgetSpan.fitSpanX(2, 3));
        check("negative cellX raised to 0", 0, WidgetSpan.fitCellX(-2, 1, 3));

        // --- degenerate rows / unknown grid pass through -------------------
        // NULL columns parse as 0-span rows — stock owns them, not us
        check("zero-span row untouched", 0, WidgetSpan.fitSpanX(0, 3));
        check("unknown grid keeps span", 4, WidgetSpan.fitSpanX(4, 0));
        check("unknown grid keeps cell", 1, WidgetSpan.fitCellX(1, 2, 0));
        check("unknown grid needs no fit",
                false, WidgetSpan.needsFit(4, 1, 0));

        // --- idempotence: a fitted row is stable across reloads ------------
        int span = WidgetSpan.fitSpanX(4, 3);
        int cell = WidgetSpan.fitCellX(1, span, 3);
        check("second pass is a no-op",
                false, WidgetSpan.needsFit(span, cell, 3));

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static void check(String name, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        } else {
            System.out.println("pass " + name);
        }
    }

    private WidgetSpanTest() {
    }
}
