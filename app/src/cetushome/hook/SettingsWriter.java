package cetushome.hook;

import android.content.Context;
import android.provider.Settings;

/** Settings.System writer: direct writes when the user has granted the
 *  WRITE_SETTINGS app-op (one toggle in system settings), falling back to the
 *  persistent su shell otherwise. */
final class SettingsWriter {

    private SettingsWriter() {
    }

    static boolean put(Context ctx, String key, int value) {
        try {
            if (Settings.System.canWrite(ctx)) {
                Settings.System.putInt(ctx.getContentResolver(), key, value);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return RootWriter.write("settings put system " + key + " " + value + "\n");
    }

    static boolean put(Context ctx, String key, String value) {
        // This key is attacker-controllable storage (any app with the
        // WRITE_SETTINGS app-op can write it), so persist only package-name-
        // shaped segments — a poisoned value must never be stored by us, and
        // never reach the su-shell line below. This is the only String path
        // into RootWriter's su shell; the second layer is settingShellLine's
        // shellQuote — either one alone would stop the poisoned value.
        String safe = ExcludeCodec.join(ExcludeCodec.parse(value));
        try {
            if (Settings.System.canWrite(ctx)) {
                Settings.System.putString(ctx.getContentResolver(), key, safe);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return RootWriter.write(settingShellLine(key, safe));
    }

    /** One `settings put` line for the persistent su shell: key and value go
     *  through RootWriter.shellQuote, so no string can close the quoting and
     *  inject shell syntax. */
    static String settingShellLine(String key, String value) {
        return "settings put system " + RootWriter.shellQuote(key) + " "
                + RootWriter.shellQuote(value) + "\n";
    }
}
