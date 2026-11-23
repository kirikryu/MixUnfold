package cetushome.hook;

/** JVM regression tests for the read-point mask policy (1.9.2). The
 *  numeric expectations are raw literals on purpose: they pin the
 *  android.content.pm.ActivityInfo values the hook layer depends on
 *  (the policy class keeps local constants because ActivityInfo is not
 *  linkable under android.jar on a plain JVM). Scope cases mirror the
 *  on-device shapes observed 2026-11-22: magic-window panes are
 *  embedding-organized TaskFragments whose bounds may even be
 *  fullscreen; system split-screen / freeform / PIP are multi-window
 *  without embedding. Run via tools/run-tests.sh. */
public final class OrientationPolicyTest {

    private static int failures;

    public static void main(String[] args) {
        // --- portrait-pin family: raw ActivityInfo values ---------------
        check("PORTRAIT(1) is a pin", true, OrientationPolicy.isPortraitPin(1));
        check("NOSENSOR(5) is a pin", true, OrientationPolicy.isPortraitPin(5));
        check("SENSOR_PORTRAIT(7) is a pin", true, OrientationPolicy.isPortraitPin(7));
        check("REVERSE_PORTRAIT(9) is a pin", true, OrientationPolicy.isPortraitPin(9));

        // --- everything else passes, including the user-will family -----
        check("UNSPECIFIED(-1) passes", false, OrientationPolicy.isPortraitPin(-1));
        check("UNSET(-2) passes", false, OrientationPolicy.isPortraitPin(-2));
        check("LANDSCAPE(0) passes (explicit landscape honored)",
                false, OrientationPolicy.isPortraitPin(0));
        check("SENSOR_LANDSCAPE(6) passes",
                false, OrientationPolicy.isPortraitPin(6));
        check("REVERSE_LANDSCAPE(8) passes",
                false, OrientationPolicy.isPortraitPin(8));
        check("USER(2) passes (user rotation-lock will)",
                false, OrientationPolicy.isPortraitPin(2));
        check("USER_PORTRAIT(12) passes (honors user-chosen rotation)",
                false, OrientationPolicy.isPortraitPin(12));
        check("LOCKED(14) passes (user lock, not an app pin)",
                false, OrientationPolicy.isPortraitPin(14));
        check("BEHIND(3) passes", false, OrientationPolicy.isPortraitPin(3));
        check("SENSOR(4) passes", false, OrientationPolicy.isPortraitPin(4));

        // --- mask constants agree with the ActivityInfo contract --------
        check("UNSPECIFIED constant is -1", -1, OrientationPolicy.UNSPECIFIED);

        // --- scope: plain fullscreen is covered --------------------------
        check("plain fullscreen in scope",
                true, OrientationPolicy.inMaskScope(false, false, 0));
        check("fullscreen dominates stray facts",
                true, OrientationPolicy.inMaskScope(false, true, 3));

        // --- scope: embedding-organized panes are covered ---------------
        check("multi-window pane (embedded) in scope",
                true, OrientationPolicy.inMaskScope(true, true, 6));
        check("fullscreen-bounds embedded fragment in scope (article page)",
                true, OrientationPolicy.inMaskScope(true, true, 1));
        check("magic-window mode 101 in scope",
                true, OrientationPolicy.inMaskScope(true, false, 101));

        // --- scope: system-governed multi-window keeps stock ------------
        check("split-screen out of scope",
                false, OrientationPolicy.inMaskScope(true, false, 3));
        check("split-screen secondary out of scope",
                false, OrientationPolicy.inMaskScope(true, false, 4));
        check("freeform out of scope",
                false, OrientationPolicy.inMaskScope(true, false, 5));
        check("pip out of scope",
                false, OrientationPolicy.inMaskScope(true, false, 2));

        // --- scope: reflection failures close conservatively -------------
        check("failure defaults close the scope",
                false, OrientationPolicy.inMaskScope(true, false, 0));

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
