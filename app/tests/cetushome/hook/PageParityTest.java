package cetushome.hook;

/** JVM regression tests for the even-page policy gates (hooks 11/12 and
 *  15/16, PageParity). Pins three decisions the hook bodies delegate:
 *  WHETHER the policy is on at all — 边距优化 (reverse alignment needs
 *  complete pairs), or the explicit pairs opt-in for stock margins (owner
 *  request, 1.9.3: some users simply want pairs) — WHEN a veto is allowed
 *  while it is on (stock not dragging at the clean choke point; the legacy
 *  edit-mode veto at both hooks), and WHICH page it protects (only the
 *  trailing blank page, paired with content, whose removal breaks the snap
 *  unit). Landscape three-page mode is exempt from every combination: its
 *  snap unit is three, so an even-page lock there is meaningless and would
 *  fight the module's own triple snap (hook 14). The count=8 shapes are the
 *  ones in the 1.8.2 on-device log ("vetoed removal … count=8") that led to
 *  the edit-only gate; the not-dragging cases are issue #3 (long-press
 *  shortcut menu deleted the parity page). Run via tools/run-tests.sh. */
public final class PageParityTest {

    private static int failures;

    /** policyActive(padOpt, alwaysPad, landscape, landPages) */
    private static boolean on(boolean padOpt, boolean alwaysPad,
                              boolean landscape, int landPages) {
        return PageParity.policyActive(padOpt, alwaysPad, landscape, landPages);
    }

    public static void main(String[] args) {
        final boolean DELETE = true;   // deleteScreen(long): clean choke point
        final boolean REMOVE = false;  // removeScreen(int): after the DB row is gone

        // --- no switch on: nothing padded, nothing protected --------------
        check("both off: policy off (portrait)",
                false, on(false, false, false, 2));
        check("both off: policy off (landscape 2-page)",
                false, on(false, false, true, 2));
        check("both off: policy off (landscape 3-page)",
                false, on(false, false, true, 3));

        // --- 边距优化: the original reason for pairs ----------------------
        check("margin on: policy on (portrait)",
                true, on(true, false, false, 2));
        check("margin on: policy on (landscape 2-page)",
                true, on(true, false, true, 2));
        check("margin on: portrait ignores the 3-page setting",
                true, on(true, false, false, 3));

        // --- pairs opt-in without margins (owner request) -----------------
        check("pairs opt-in alone: policy on (portrait)",
                true, on(false, true, false, 2));
        check("pairs opt-in alone: policy on (landscape 2-page)",
                true, on(false, true, true, 2));
        check("pairs opt-in alone: landscape 3-page still exempt",
                false, on(false, true, true, 3));
        check("pairs opt-in with margin on: landscape 3-page still exempt",
                false, on(true, true, true, 3));
        check("both on: policy on (portrait)",
                true, on(true, true, false, 2));

        // --- issue #3: stock's post-drag / load / edit-exit trims ---------
        // long-press raised the shortcut menu (editing state 17 → back to
        // 7): not editing, drag flag already cleared — the parity page
        // must be kept
        check("idle trim vetoed in normal state",
                true, PageParity.vetoAllowed(DELETE, false, false));

        // --- drag-to-edge page flow stays untouched (1.8.2 lesson) --------
        // mid-drag removals in normal mode are stock's own flow
        check("mid-drag removal passes in normal state",
                false, PageParity.vetoAllowed(DELETE, false, true));

        // --- legacy edit-mode veto: unchanged at both hooks ---------------
        check("edit-mode veto holds mid-drag",
                true, PageParity.vetoAllowed(DELETE, true, true));
        check("edit-mode veto holds idle",
                true, PageParity.vetoAllowed(DELETE, true, false));
        check("removeScreen: edit-mode veto holds mid-drag",
                true, PageParity.vetoAllowed(REMOVE, true, true));
        check("removeScreen: edit-mode veto holds idle",
                true, PageParity.vetoAllowed(REMOVE, true, false));

        // --- removeScreen never gains the not-dragging veto ---------------
        // it runs after the DB row is gone: a veto there strands an orphan
        check("removeScreen: idle normal-state removal passes",
                false, PageParity.vetoAllowed(REMOVE, false, false));
        check("removeScreen: mid-drag normal-state removal passes",
                false, PageParity.vetoAllowed(REMOVE, false, true));

        // --- geometry: which page is protected ----------------------------
        // args: count, index, trailingEmpty, predecessorEmpty, modulo
        check("trailing blank paired with content is protected (count=8)",
                true, PageParity.breaksParity(8, 7, true, false, 2));
        check("trailing blank on-parity is stock's to take (count=7)",
                false, PageParity.breaksParity(7, 6, true, false, 2));
        check("non-trailing blank page is not protected",
                false, PageParity.breaksParity(8, 6, true, false, 2));
        check("trailing page with content is never protected",
                false, PageParity.breaksParity(8, 7, false, false, 2));
        check("single-page workspace is never protected",
                false, PageParity.breaksParity(1, 0, true, true, 2));
        check("unknown screen id (index -1) is never protected",
                false, PageParity.breaksParity(8, -1, true, false, 2));
        check("empty workspace is never protected",
                false, PageParity.breaksParity(0, -1, true, true, 2));
        check("unknown snap unit never vetoes",
                false, PageParity.breaksParity(8, 7, true, false, 0));

        // --- two blanks in a row are leftovers, not a parity page ---------
        // (old double-pad, or a page the user emptied): stock cleans them
        check("second blank in a row is not protected",
                false, PageParity.breaksParity(8, 7, true, true, 2));
        check("two-page all-blank workspace is not protected",
                false, PageParity.breaksParity(2, 1, true, true, 2));
        check("content + blank pair is protected",
                true, PageParity.breaksParity(2, 1, true, false, 2));

        // --- snap unit of 3 (pins the geometry; the policy itself is off
        //     in landscape 3-page mode — see policyActive above) -----------
        check("unit 3: trailing blank off-boundary is protected",
                true, PageParity.breaksParity(8, 7, true, false, 3));
        check("unit 3: trailing blank on-boundary passes",
                false, PageParity.breaksParity(7, 6, true, false, 3));

        // --- end to end through all gates ---------------------------------
        // 8 pages, blank parity page at the end; stock's trim asks to delete
        // page 7 once the shortcut-menu drop has ended
        check("both off: idle trim passes on the parity page",
                false, on(false, false, false, 2)
                        && PageParity.vetoAllowed(DELETE, false, false)
                        && PageParity.breaksParity(8, 7, true, false, 2));
        check("both off: edit-mode trim passes too",
                false, on(false, false, false, 2)
                        && PageParity.vetoAllowed(DELETE, true, false)
                        && PageParity.breaksParity(8, 7, true, false, 2));
        check("margin on, landscape 3-page: trim passes",
                false, on(true, false, true, 3)
                        && PageParity.vetoAllowed(DELETE, false, false)
                        && PageParity.breaksParity(8, 7, true, false, 2));
        check("pairs opt-in, landscape 3-page: trim passes",
                false, on(false, true, true, 3)
                        && PageParity.vetoAllowed(DELETE, false, false)
                        && PageParity.breaksParity(8, 7, true, false, 2));
        check("issue #3: margin on, parity page survives the post-drop trim",
                true, on(true, false, false, 2)
                        && PageParity.vetoAllowed(DELETE, false, false)
                        && PageParity.breaksParity(8, 7, true, false, 2));
        check("pairs opt-in alone: parity page survives too",
                true, on(false, true, false, 2)
                        && PageParity.vetoAllowed(DELETE, false, false)
                        && PageParity.breaksParity(8, 7, true, false, 2));
        // same request while the icon is still being dragged: untouched
        check("drag-to-edge: same removal mid-drag passes",
                true, !(on(true, false, false, 2)
                        && PageParity.vetoAllowed(DELETE, false, true)
                        && PageParity.breaksParity(8, 7, true, false, 2)));

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
}
