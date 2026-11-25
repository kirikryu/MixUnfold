package cetushome.hook;

/** JVM regression tests for the appearance memo (1.9.5). The memo is the
 *  decision half of the {@code createAppearance} hook: a hit must return
 *  the exact int the ROM returned for the same (bitmap, rect), a miss must
 *  never invent one, and a rotating set of rects must not be able to pin
 *  the table. Bitmap identity is compared by reference, so these tests
 *  hand in plain Objects as stand-ins — the memo is deliberately free of
 *  Android types. Run via tools/run-tests.sh. */
public final class AppearanceMemoTest {

    private static int failures;

    public static void main(String[] args) {
        // --- empty table: nothing to hit --------------------------------
        AppearanceMemo m = new AppearanceMemo(4);
        check("empty lookup misses", null, m.lookup(new Object(), 0, 0, 10, 10));
        check("empty size is 0", 0, m.size());

        // --- same key hits with the stored value ------------------------
        Object bmp = new Object();
        m.remember(bmp, 0, 0, 930, 107, 8);
        check("same owner+rect hits", 8, m.lookup(bmp, 0, 0, 930, 107));
        check("negative result is cached too",
                cacheNegative(), Integer.valueOf(-1));

        // --- a different rect on the same bitmap misses ------------------
        check("different right edge misses", null, m.lookup(bmp, 0, 0, 1860, 107));
        check("different top misses", null, m.lookup(bmp, 0, 0, 930, 108));
        m.remember(bmp, 0, 0, 1860, 107, 0);
        check("both rects now hit independently", 8, m.lookup(bmp, 0, 0, 930, 107));
        check("...second rect hits too", 0, m.lookup(bmp, 0, 0, 1860, 107));

        // --- a different bitmap (wallpaper changed) misses ---------------
        Object newBmp = new Object();
        check("new bitmap misses the old entries",
                null, m.lookup(newBmp, 0, 0, 930, 107));
        m.remember(newBmp, 0, 0, 930, 107, 0);
        check("old bitmap keeps its own value", 8, m.lookup(bmp, 0, 0, 930, 107));
        check("new bitmap has its own value", 0, m.lookup(newBmp, 0, 0, 930, 107));

        // --- null owner (no bitmap loaded) never keys --------------------
        AppearanceMemo n = new AppearanceMemo(2);
        n.remember(null, 0, 0, 10, 10, 8);
        check("null owner never hits", null, n.lookup(null, 0, 0, 10, 10));
        check("null owner is not stored", 0, n.size());

        // --- overwrite in place, no growth -------------------------------
        AppearanceMemo o = new AppearanceMemo(2);
        o.remember(bmp, 0, 0, 10, 10, 8);
        o.remember(bmp, 0, 0, 10, 10, 0);
        check("re-record overwrites in place", 0, o.lookup(bmp, 0, 0, 10, 10));
        check("overwrite did not consume a slot", 1, o.size());

        // --- capacity: a rotating set of rects cannot pin the table ------
        AppearanceMemo c = new AppearanceMemo(2);
        c.remember(bmp, 0, 0, 1, 1, 1);
        c.remember(bmp, 0, 0, 2, 1, 2);
        check("two entries fit", 2, c.size());
        c.remember(bmp, 0, 0, 3, 1, 3);
        check("third entry evicts instead of growing", 2, c.size());
        check("newest entry present", 3, c.lookup(bmp, 0, 0, 3, 1));
        check("oldest entry evicted", null, c.lookup(bmp, 0, 0, 1, 1));

        // --- letterboxed rects can carry negative origins ----------------
        AppearanceMemo g = new AppearanceMemo(2);
        g.remember(bmp, -5, -7, 930, 107, 8);
        check("negative origin hits", 8, g.lookup(bmp, -5, -7, 930, 107));
        check("negative origin is rect-exact", null, g.lookup(bmp, -5, -6, 930, 107));

        // --- clear() drops everything ------------------------------------
        g.clear();
        check("clear empties the table", 0, g.size());
        check("clear misses", null, g.lookup(bmp, -5, -7, 930, 107));

        // --- capacity guard ----------------------------------------------
        boolean threw = false;
        try {
            new AppearanceMemo(0);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check("capacity 0 is rejected", true, threw);

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    /** The ROM returns -1 for a rect outside the bitmap or on failure. That
     *  result must be memoised as well: it is the case the stock single-slot
     *  cache refuses to keep, which is why every layout pass re-sampled. */
    private static Object cacheNegative() {
        AppearanceMemo m = new AppearanceMemo(2);
        Object bmp = new Object();
        m.remember(bmp, 930, 0, 2480, 107, -1);
        return m.lookup(bmp, 930, 0, 2480, 107);
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
