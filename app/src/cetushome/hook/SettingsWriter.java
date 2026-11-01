package cetushome.hook;

import android.content.Context;
import android.provider.Settings;

/** Settings.System writer: direct writes when the user has granted the
 *  WRITE_SETTINGS app-op (one toggle in system settings), falling back to
 *  the persistent su shell otherwise. */
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
        try {
            if (Settings.System.canWrite(ctx)) {
                Settings.System.putString(ctx.getContentResolver(), key, value);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return RootWriter.write("settings put system " + key + " " + value + "\n");
    }
}
