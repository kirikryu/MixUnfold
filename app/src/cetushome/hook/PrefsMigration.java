package cetushome.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure migration decisions, kept free of Android types so the 1.9.0
 *  "missing key vs default value" semantics are regression-tested on the JVM.
 *
 *  The rule is contains()-based: a stored false/0 is still a present key and
 *  must never be overwritten by the legacy value. Legacy Settings.System is
 *  only a source when this install is an in-place upgrade and the key is
 *  absent; a fresh/reinstalled module ignores the old namespace entirely. */
final class PrefsMigration {

    static boolean shouldReadLegacy(boolean inPlaceUpgrade, boolean prefsContainsKey) {
        return inPlaceUpgrade && !prefsContainsKey;
    }

    static List<String> missingKeys(Set<String> presentKeys) {
        ArrayList<String> out = new ArrayList<String>();
        for (String key : PrefsKeys.LEGACY_KEYS) {
            if (presentKeys == null || !presentKeys.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    private PrefsMigration() {
    }
}
