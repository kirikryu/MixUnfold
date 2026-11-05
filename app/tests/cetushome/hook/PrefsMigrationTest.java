package cetushome.hook;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** JVM regression tests for the 1.9.0 private-prefs migration rules.
 *
 *  The dangerous bug class is "missing key vs default value": a stored 0 or
 *  false must never be treated as absent and overwritten by the legacy
 *  Settings.System value. The decision is contains()-based, and legacy data
 *  is only a source for an in-place upgrade (firstInstallTime <
 *  lastUpdateTime); a fresh install or uninstall+reinstall ignores it so the
 *  module starts from defaults. Run via tools/run-tests.sh. */
public final class PrefsMigrationTest {

    private static int failures;

    public static void main(String[] args) {
        // --- shouldReadLegacy truth table --------------------------------
        check("upgrade + missing reads legacy",
                true, PrefsMigration.shouldReadLegacy(true, false));
        check("upgrade + present keeps prefs",
                false, PrefsMigration.shouldReadLegacy(true, true));
        check("fresh/reinstall + missing uses default",
                false, PrefsMigration.shouldReadLegacy(false, false));
        check("fresh/reinstall + present keeps prefs",
                false, PrefsMigration.shouldReadLegacy(false, true));

        // A stored false/0 is a present key: the caller must pass
        // contains()==true, never compare against a default.
        Set<String> presentWithZero = new HashSet<String>(
                Arrays.asList(PrefsKeys.KEY_PAD_OPT));
        check("stored 0/false counts as present",
                false, PrefsMigration.shouldReadLegacy(true,
                        presentWithZero.contains(PrefsKeys.KEY_PAD_OPT)));

        // --- missingKeys: stable order, no false positives ---------------
        check("all eight keys missing",
                Arrays.asList(PrefsKeys.LEGACY_KEYS),
                PrefsMigration.missingKeys(new HashSet<String>()));
        check("null set means all missing",
                8, PrefsMigration.missingKeys(null).size());

        Set<String> onePresent = new HashSet<String>(
                Arrays.asList(PrefsKeys.KEY_COLUMNS));
        List<String> missing = PrefsMigration.missingKeys(onePresent);
        check("present key is not missing",
                false, missing.contains(PrefsKeys.KEY_COLUMNS));
        check("remaining count after one present",
                7, missing.size());

        // --- key table exactness (frozen 1.8.x names) --------------------
        check("legacy key names are frozen",
                Arrays.asList(
                        "cetus_home_columns",
                        "cetus_home_padding_side",
                        "cetus_home_padding_side_land",
                        "cetus_home_land_pages",
                        "cetus_home_pad_opt",
                        "cetus_home_lockscreen_rot",
                        "cetus_free_rot",
                        "cetus_free_rot_exclude"),
                Arrays.asList(PrefsKeys.LEGACY_KEYS));
        check("no duplicate legacy keys",
                PrefsKeys.LEGACY_KEYS.length,
                new TreeSet<String>(Arrays.asList(PrefsKeys.LEGACY_KEYS)).size());
        check("migration marker is not a legacy key",
                false, PrefsKeys.isLegacyKey(PrefsKeys.KEY_MIGRATED));

        // --- defaults match the 1.8.4 read-time defaults -----------------
        check("columns default", 3, PrefsKeys.defaultInt(PrefsKeys.KEY_COLUMNS));
        check("padding default", 120, PrefsKeys.defaultInt(PrefsKeys.KEY_PAD_LAND));
        check("land pages default", 3, PrefsKeys.defaultInt(PrefsKeys.KEY_LAND_PAGES));
        check("pad-opt default", 0, PrefsKeys.defaultInt(PrefsKeys.KEY_PAD_OPT));
        check("lock-rot default", 1, PrefsKeys.defaultInt(PrefsKeys.KEY_LOCK_ROT));
        check("free-rot default", 1, PrefsKeys.defaultInt(PrefsKeys.KEY_FREE_ROT));
        check("string key has no int default",
                -1, PrefsKeys.defaultInt(PrefsKeys.KEY_ROT_EXCLUDE));
        check("empty exclusion default",
                "", PrefsKeys.DEF_ROT_EXCLUDE);

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

    private PrefsMigrationTest() {
    }
}
