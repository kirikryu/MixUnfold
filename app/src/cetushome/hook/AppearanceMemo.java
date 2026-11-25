package cetushome.hook;

import java.lang.ref.WeakReference;

/** Bounded memo for the ROM's status-bar appearance sampling (1.9.5).
 *
 *  <p>{@code com.android.server.wm.WindowManagerServiceImpl.createAppearance}
 *  crops the cached blur wallpaper bitmap and walks it pixel by pixel
 *  ({@code getPixel} + four {@code Color.*} calls per pixel) to decide the
 *  status-bar icon appearance. Its caller keeps a <em>single-slot</em>
 *  cache per side, so two embedding panes asking with different rects
 *  evict each other and every layout pass re-samples both rects. Measured
 *  on cetus (2026-11-25, Perfetto): 91 passes of >50 ms each, mean 158 ms,
 *  averaging 99.9% on-CPU, all inside {@code applyPostLayoutPolicy} while
 *  the window manager global lock is held — the window-lock convoy behind
 *  the multi-second pane resize on rotation.
 *
 *  <p>This class is the decision half only: it maps (bitmap, rect) to the
 *  previously returned appearance. {@code createAppearance} is a pure
 *  function of the bitmap pixels in that rect and the rect itself, so a
 *  hit returns a byte-identical answer — no visual change, no behaviour
 *  change, nothing to undo when the switch is off. Bitmap identity is held
 *  weakly, so a wallpaper change naturally invalidates every entry instead
 *  of pinning a stale (and possibly several-MB) bitmap.
 *
 *  Pure logic, no Android types: the hook body in MainHook translates the
 *  live Rect/bitmap into the plain arguments, and this class is covered by
 *  the JVM regression suite (AppearanceMemoTest). */
final class AppearanceMemo {

    /** Default capacity. Two panes alternate between a handful of rects
     *  (pane rect, task-width rect, letterbox rect); 8 covers every shape
     *  observed on device with room to spare, and the entries are tiny. */
    static final int DEFAULT_CAPACITY = 8;

    private final Entry[] entries;
    private int next;

    AppearanceMemo() {
        this(DEFAULT_CAPACITY);
    }

    AppearanceMemo(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        entries = new Entry[capacity];
    }

    /** Previous result for (owner, rect), or null on a miss. A null owner
     *  (bitmap not loaded yet) never hits: there is nothing to key on. */
    Integer lookup(Object owner, int left, int top, int right, int bottom) {
        if (owner == null) {
            return null;
        }
        for (int i = 0; i < entries.length; i++) {
            Entry e = entries[i];
            if (e == null) {
                continue;
            }
            Object held = e.owner.get();
            if (held == null) {
                entries[i] = null; // owner was collected: drop the slot
                continue;
            }
            if (held == owner && e.sameRect(left, top, right, bottom)) {
                return Integer.valueOf(e.value);
            }
        }
        return null;
    }

    /** Record a result. Re-recording the same (owner, rect) overwrites in
     *  place so a hot key cannot evict its neighbours. */
    void remember(Object owner, int left, int top, int right, int bottom, int value) {
        if (owner == null) {
            return;
        }
        weakPurge();
        for (Entry e : entries) {
            if (e != null && e.owner.get() == owner
                    && e.sameRect(left, top, right, bottom)) {
                e.value = value;
                return;
            }
        }
        for (int i = 0; i < entries.length; i++) {
            if (entries[i] == null) {
                entries[i] = new Entry(owner, left, top, right, bottom, value);
                return;
            }
        }
        // full: round-robin, so a rotating set of rects cannot pin the table
        entries[next] = new Entry(owner, left, top, right, bottom, value);
        next = (next + 1) % entries.length;
    }

    /** Number of live entries — test/diagnostic only. */
    int size() {
        int n = 0;
        for (Entry e : entries) {
            if (e != null && e.owner.get() != null) {
                n++;
            }
        }
        return n;
    }

    void clear() {
        for (int i = 0; i < entries.length; i++) {
            entries[i] = null;
        }
        next = 0;
    }

    private void weakPurge() {
        for (int i = 0; i < entries.length; i++) {
            if (entries[i] != null && entries[i].owner.get() == null) {
                entries[i] = null;
            }
        }
    }

    private static final class Entry {
        final WeakReference<Object> owner;
        final int left;
        final int top;
        final int right;
        final int bottom;
        int value;

        Entry(Object owner, int left, int top, int right, int bottom, int value) {
            this.owner = new WeakReference<Object>(owner);
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.value = value;
        }

        boolean sameRect(int l, int t, int r, int b) {
            return left == l && top == t && right == r && bottom == b;
        }
    }
}
