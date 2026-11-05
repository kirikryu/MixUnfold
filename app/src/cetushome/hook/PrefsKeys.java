package cetushome.hook;

/** Central key/default table for the module-private config store.
 *
 *  Names are frozen to the 1.8.x Settings.System keys so the 1.9.0 migration
 *  is a pure storage move (no semantic rename). Keep LEGACY_KEYS in sync with
 *  the keys the hook used to read; PrefsMigrationTest pins the exact strings.
 *
 *  Pure constants on purpose: the JVM regression suite compiles this class
 *  without android.jar. */
final class PrefsKeys {

    static final String PREFS_NAME = "mixunfold_prefs";
    /** Written by the app once the legacy snapshot has been handled. */
    static final String KEY_MIGRATED = "mixunfold_migrated_v1";

    static final String KEY_COLUMNS = "cetus_home_columns";
    static final String KEY_PAD_PORT = "cetus_home_padding_side";
    static final String KEY_PAD_LAND = "cetus_home_padding_side_land";
    static final String KEY_LAND_PAGES = "cetus_home_land_pages";
    static final String KEY_PAD_OPT = "cetus_home_pad_opt";
    static final String KEY_LOCK_ROT = "cetus_home_lockscreen_rot";
    static final String KEY_FREE_ROT = "cetus_free_rot";
    static final String KEY_ROT_EXCLUDE = "cetus_free_rot_exclude";

    static final int DEF_COLUMNS = 3;
    static final int DEF_PAD = 120;
    static final int DEF_LAND_PAGES = 3;
    static final int DEF_PAD_OPT = 0;
    static final int DEF_LOCK_ROT = 1;
    static final int DEF_FREE_ROT = 1;
    static final String DEF_ROT_EXCLUDE = "";

    /** Every key the 1.8.x hook read from Settings.System, in a stable order.
     *  Migration and the read-only fallback share this list. */
    static final String[] LEGACY_KEYS = {
            KEY_COLUMNS,
            KEY_PAD_PORT,
            KEY_PAD_LAND,
            KEY_LAND_PAGES,
            KEY_PAD_OPT,
            KEY_LOCK_ROT,
            KEY_FREE_ROT,
            KEY_ROT_EXCLUDE,
    };

    static boolean isLegacyKey(String key) {
        for (String k : LEGACY_KEYS) {
            if (k.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** Default for the six int-typed keys; -1 for the string key (callers
     *  must use DEF_ROT_EXCLUDE there). */
    static int defaultInt(String key) {
        if (KEY_COLUMNS.equals(key)) {
            return DEF_COLUMNS;
        }
        if (KEY_PAD_PORT.equals(key) || KEY_PAD_LAND.equals(key)) {
            return DEF_PAD;
        }
        if (KEY_LAND_PAGES.equals(key)) {
            return DEF_LAND_PAGES;
        }
        if (KEY_PAD_OPT.equals(key)) {
            return DEF_PAD_OPT;
        }
        if (KEY_LOCK_ROT.equals(key)) {
            return DEF_LOCK_ROT;
        }
        if (KEY_FREE_ROT.equals(key)) {
            return DEF_FREE_ROT;
        }
        return -1;
    }

    private PrefsKeys() {
    }
}
