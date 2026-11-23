package cetushome.hook;

/** Read-point mask policy (1.9.2): which requested orientations count as
 *  an app-forced portrait pin, and which window forms fall inside the
 *  mask's scope. Pure decision logic — the E2/E3 hook bodies translate
 *  live reflection facts into these inputs so the semantics are
 *  JVM-testable without a device.
 *
 *  Portrait-pin family: requests that force the display onto the portrait
 *  axis regardless of the sensor (PORTRAIT, SENSOR_PORTRAIT,
 *  REVERSE_PORTRAIT, NOSENSOR — the natural orientation of both cetus
 *  displays is portrait). The USER/LOCKED family is the user's own
 *  rotation-lock will and is never masked; USER_PORTRAIT likewise honors
 *  the user's chosen rotation, not a hard pin. Every landscape request
 *  passes through — the mirror of the MIUI engine's philosophy: free
 *  portrait locks, honor explicit landscape (video fullscreen buttons,
 *  landscape games keep stock behavior). */
final class OrientationPolicy {

    // android.content.pm.ActivityInfo screenOrientation values (int
    // constants kept local: the class is not linkable in JVM tests)
    static final int UNSPECIFIED = -1;
    static final int PORTRAIT = 1;
    static final int NOSENSOR = 5;
    static final int SENSOR_PORTRAIT = 7;
    static final int REVERSE_PORTRAIT = 9;

    /** True when a requested orientation pins the display portrait. */
    static boolean isPortraitPin(int orientation) {
        return orientation == PORTRAIT || orientation == NOSENSOR
                || orientation == SENSOR_PORTRAIT || orientation == REVERSE_PORTRAIT;
    }

    /** Scope decision from live reflection facts: plain fullscreen records
     *  and embedding-organized panes (magic-window TaskFragments, or the
     *  magic-window windowing mode 101) are covered; system-governed
     *  multi-window (split screen, freeform, PIP) keeps its own
     *  orientation management. Callers pass conservative defaults when a
     *  fact cannot be reflected (multiWindow=true, embedded=false,
     *  windowingMode=0) so every failure closes to "out of scope". */
    static boolean inMaskScope(boolean multiWindow, boolean embedded, int windowingMode) {
        if (!multiWindow) {
            return true; // plain fullscreen record
        }
        return embedded || windowingMode == 101;
    }

    private OrientationPolicy() {
    }
}
