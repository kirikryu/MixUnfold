package cetushome.hook;

/**
 * Pure decision logic for the even-page policy (hooks 11/12 and 15/16):
 * WHEN the policy applies at all, and — while it does — whether a stock
 * request to remove the trailing page must be vetoed.
 *
 * The policy exists for 边距优化: reverse alignment (hook 5: even pages
 * carry the outer bezel on the left, odd pages carry none) only interlocks
 * when pages come in pairs, which is why stock's pair-wise snapping needs a
 * trailing blank whenever the content page count is odd. With the switch off
 * the module touches no margin, stock margins look right on any page count,
 * and the launcher keeps its own paging — unless the owner opts into pairs
 * for their own sake ({@code cetushome_always_pad}, default off, added
 * 1.9.3): then the policy runs with stock margins. Both halves — the
 * padding ({@code MainHook.ensureEvenPages}) and the veto — go through
 * {@link #policyActive}, so they cannot disagree about the switches again
 * (until 1.9.3 the padding ignored them while the veto honored padOpt).
 *
 * Landscape three-page mode stays exempt in every combination. Its snap
 * unit is three, so an even-page parity lock is meaningless there, and
 * padding to 2 would leave a lone pair group for the module's own triple
 * snap (hook 14) to fight — the design decision of 1.8.2, kept.
 *
 * Stock background (4.39.12.6887 decompile, bytecode-verified on the device
 * APK): stock deletes a workspace screen through exactly one path —
 * {@code CellLayout}'s delete-self runnable calls
 * {@code CellLayout.deleteSelf()} -> {@code Workspace.deleteScreen(long)}
 * (DB delete, later {@code bindScreenRemoved} -> {@code removeScreen(int)}).
 * That runnable is posted by {@code CellLayout.checkToDeleteSelf()}, in
 * bulk from {@code checkAllScreensToSelfDelete()}, which stock runs after
 * every drop animation ({@code bindScreenTrim}), on edit-mode entry and
 * exit ({@code Launcher.setEditingState}) and when the workspace finishes
 * loading. Its gate {@code Workspace.needShowLastEmptyScreen()} keeps a
 * trailing blank page only while items are checked in multi-select
 * editing, so outside those states the parity page this module appends is
 * always "prunable" to stock.
 *
 * Reported symptom (issue #3): long-pressing an icon raised the shortcut
 * menu and the appended blank page disappeared. The menu state (17), the
 * normal state (7) and the quick-edit transition (9) all leave
 * {@code Workspace.mEditingMode} at a value that
 * {@code isInNormalEditingMode()} reports as "not editing", and the veto
 * had been gated on that flag since 1.8.2 — so the post-drop trim deleted
 * the page unopposed.
 *
 * The mode gate is therefore split in two. While a stock drag is in
 * progress the veto stays out of the way (normal mode): drag-to-edge page
 * creation legitimately removes and rebuilds the trailing page, and an
 * earlier unconditional veto killed that gesture. Once the drag flag is
 * cleared — {@code DragController.endDrag} clears it before the drop
 * animation and every post-drop clean-up runs — the veto engages.
 */
final class PageParity {

    private PageParity() {
    }

    /**
     * Is the even-page policy on at all? Switch off means stock paging:
     * nothing padded, nothing protected. The policy runs when 边距优化 is
     * on (pairs are needed for the interlock) or when the user opted into
     * pairs without it. Landscape three-page mode pages freely in every
     * combination (design decision, DESIGN §12): no parity lock there, in
     * either half of the policy.
     */
    static boolean policyActive(boolean padOpt, boolean alwaysPad,
                                boolean landscape, int landPages) {
        return (padOpt || alwaysPad) && !(landscape && landPages == 3);
    }

    /**
     * Mode gate of the veto: may this hook veto right now? Callers have
     * already established {@link #policyActive}.
     *
     * @param cleanChokePoint true for {@code deleteScreen(long)} — reached
     *        before anything is mutated, so a veto there is side-effect
     *        free. False for {@code removeScreen(int)}, which runs after the
     *        DB row is gone: vetoing there strands an orphan page view, so
     *        it keeps the legacy gate only.
     * @param editing {@code Workspace.isInNormalEditingMode()} — snapshot /
     *        multi-select editing layout.
     * @param dragging {@code DragController.mDragging}
     */
    static boolean vetoAllowed(boolean cleanChokePoint, boolean editing,
                               boolean dragging) {
        if (editing) {
            return true; // legacy edit-mode veto, held in both hooks
        }
        return cleanChokePoint && !dragging;
    }

    /**
     * Geometry gate: would removing this page break the snap-unit parity
     * AND is the page ours to keep? Only the trailing blank page counts —
     * a page carrying user content is always removable, and a trailing
     * page whose removal lands on a unit boundary is stock's to take.
     *
     * The trailing blank must also be paired with content: the page before
     * it carries items, which is the exact shape {@code ensureEvenPages}
     * appends. Two blanks in a row are not a parity page but leftovers
     * (an old double-pad, a page the user emptied) — stock keeps cleaning
     * those up. An unknown snap unit (modulo &lt; 1) never vetoes.
     */
    static boolean breaksParity(int count, int index, boolean trailingEmpty,
                                boolean predecessorEmpty, int modulo) {
        if (modulo < 1 || count <= 1 || index != count - 1) {
            return false;
        }
        if ((count - 1) % modulo == 0 || !trailingEmpty) {
            return false;
        }
        return !predecessorEmpty;
    }
}
