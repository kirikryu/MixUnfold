package cetushome.hook;

import android.content.Context;

/** Module-config writer used by the two activities. The historical name is
 *  kept so the UI call sites stay unchanged: writes now go to the module's
 *  private SharedPreferences (MixPrefs), never to Settings.System and never
 *  through a shell. */
final class SettingsWriter {

    private SettingsWriter() {
    }

    static boolean put(Context ctx, String key, int value) {
        return MixPrefs.putInt(ctx, key, value);
    }

    static boolean put(Context ctx, String key, String value) {
        // The exclusion list is a package-name list. Re-validate on write even
        // though only this app can write the prefs file: the value may have
        // come from the legacy Settings.System migration or a restored backup.
        String safe = ExcludeCodec.join(ExcludeCodec.parse(value));
        return MixPrefs.putString(ctx, key, safe);
    }
}
