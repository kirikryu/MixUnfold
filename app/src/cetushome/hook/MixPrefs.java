package cetushome.hook;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.provider.Settings;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Module-private config store: the single writer side (module app process).
 *
 *  History: 1.8.x kept the eight keys in Settings.System, which any app with
 *  the WRITE_SETTINGS app-op could rewrite; the module app then mirrored
 *  changes with a su fallback. 1.9.0 moves the source of truth to this
 *  private SharedPreferences file. The hook processes only ever read it
 *  (HookPrefs); nothing outside the module can write it.
 *
 *  Legacy Settings.System is read-only here and only for an in-place upgrade
 *  whose prefs key is still missing (the user upgraded but has not opened the
 *  app yet). A fresh install or an uninstall+reinstall ignores the old
 *  namespace entirely, so reinstall starts from defaults.
 *
 *  Storage context: credential-protected by default (matches HookPrefs'
 *  /data/user/<user>/<pkg>/shared_prefs file path). Device-protected storage
 *  is wired but disabled until it is verified on the target device; flipping
 *  USE_DEVICE_PROTECTED switches both the app directory and HookPrefs' path.
 */
final class MixPrefs {

    private static final String TAG = "CetusHomeHook";
    private static final String MODULE_PKG = "cetushome.rotationunlock";

    /** See class doc. Flip only after a device check of both app write and
     *  HookPrefs read from the /data/user_de path. */
    static final boolean USE_DEVICE_PROTECTED = false;

    private static final int UNKNOWN = -1;
    private static volatile int sInPlaceUpgrade = UNKNOWN;
    /** Mode probe result; the SecurityException must not repeat per call. */
    private static boolean sModeProbed;
    private static boolean sWorldReadableOk;

    private MixPrefs() {
    }

    static SharedPreferences prefs(Context ctx) {
        Context c = ctx;
        if (USE_DEVICE_PROTECTED) {
            try {
                Context de = ctx.createDeviceProtectedStorageContext();
                if (de != null) {
                    c = de;
                }
            } catch (Throwable t) {
                Log.w(TAG, "device-protected storage unavailable: " + t);
            }
        }
        if (!sModeProbed) {
            try {
                c.getSharedPreferences(PrefsKeys.PREFS_NAME,
                        Context.MODE_WORLD_READABLE).getString("mode_probe", null);
                sWorldReadableOk = true;
            } catch (Throwable t) {
                // Stock Android N+ rejects the mode; LSPosed's prefs hook
                // (xposedsharedprefs meta) permits it. Either way the hook
                // side reads through lspd (root-served), not via the DAC bit.
                Log.w(TAG, "MODE_WORLD_READABLE rejected, prefs stay private: " + t);
                sWorldReadableOk = false;
            }
            sModeProbed = true;
        }
        return c.getSharedPreferences(PrefsKeys.PREFS_NAME,
                sWorldReadableOk
                        ? Context.MODE_WORLD_READABLE
                        : Context.MODE_PRIVATE);
    }

    static int getInt(Context ctx, String key, int def) {
        try {
            return prefs(ctx).getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    static boolean getBoolean(Context ctx, String key, boolean def) {
        try {
            return prefs(ctx).getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    static String getString(Context ctx, String key, String def) {
        try {
            return prefs(ctx).getString(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    static Set<String> getStringSet(Context ctx, String key, Set<String> def) {
        try {
            return prefs(ctx).getStringSet(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    static boolean contains(Context ctx, String key) {
        try {
            return prefs(ctx).contains(key);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean putInt(Context ctx, String key, int value) {
        try {
            return prefs(ctx).edit().putInt(key, value).commit();
        } catch (Throwable t) {
            Log.w(TAG, "prefs write failed for " + key + ": " + t);
            return false;
        }
    }

    static boolean putString(Context ctx, String key, String value) {
        try {
            return prefs(ctx).edit().putString(key, value).commit();
        } catch (Throwable t) {
            Log.w(TAG, "prefs write failed for " + key + ": " + t);
            return false;
        }
    }

    /** Fill every missing key once, then mark the migration done. Called by
     *  the module app before any UI read. Never overwrites an existing key:
     *  a stored 0/false is a deliberate value, not "absent". */
    static synchronized void ensureMigrated(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            SharedPreferences p = prefs(ctx);
            boolean upgrade = isInPlaceUpgrade(ctx);

            // contains()-based: a stored 0/false is present and wins. The
            // pure helpers are the regression-tested definition of the rule.
            HashSet<String> present = new HashSet<String>();
            for (String key : PrefsKeys.LEGACY_KEYS) {
                if (p.contains(key)) {
                    present.add(key);
                }
            }
            List<String> missing = PrefsMigration.missingKeys(present);

            SharedPreferences.Editor e = p.edit();
            boolean changed = false;
            int filled = 0;
            int legacyCopied = 0;

            for (String key : missing) {
                boolean fromLegacy = PrefsMigration.shouldReadLegacy(
                        upgrade, present.contains(key));
                if (PrefsKeys.KEY_ROT_EXCLUDE.equals(key)) {
                    String raw = fromLegacy ? legacyString(ctx, key) : null;
                    e.putString(key, ExcludeCodec.join(ExcludeCodec.parse(raw)));
                    if (raw != null) {
                        legacyCopied++;
                    }
                } else {
                    int def = PrefsKeys.defaultInt(key);
                    Integer old = fromLegacy ? legacyInt(ctx, key) : null;
                    if (old != null) {
                        def = old;
                        legacyCopied++;
                    }
                    e.putInt(key, def);
                }
                filled++;
                changed = true;
            }

            if (!p.getBoolean(PrefsKeys.KEY_MIGRATED, false)) {
                e.putBoolean(PrefsKeys.KEY_MIGRATED, true);
                changed = true;
            }
            if (!changed) {
                return;
            }
            if (e.commit()) {
                Log.i(TAG, "prefs migrated: upgrade=" + upgrade + " filled=" + filled
                        + " legacyCopied=" + legacyCopied);
            } else {
                Log.w(TAG, "prefs migration commit failed");
            }
        } catch (Throwable t) {
            Log.w(TAG, "prefs migration failed: " + t);
        }
    }

    /** True when this installation replaced an earlier one in place.
     *  firstInstallTime == lastUpdateTime for a fresh install or a
     *  reinstall after uninstall, which is exactly the "ignore legacy" case.
     *  On a PackageManager failure assume upgrade so an older user's config
     *  is not silently lost. */
    static boolean isInPlaceUpgrade(Context ctx) {
        int cached = sInPlaceUpgrade;
        if (cached != UNKNOWN) {
            return cached == 1;
        }
        if (ctx == null) {
            return true;
        }
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(MODULE_PKG, 0);
            boolean upgrade = pi.firstInstallTime < pi.lastUpdateTime;
            sInPlaceUpgrade = upgrade ? 1 : 0;
            return upgrade;
        } catch (Throwable t) {
            Log.w(TAG, "install-time check failed, assuming upgrade: " + t);
            return true;
        }
    }

    /** Read-only legacy fallback for the hook: only an in-place upgrade whose
     *  prefs key is still missing. Never writes Settings.System. */
    static int legacyIntIfUpgrade(Context ctx, String key, int def) {
        if (!isInPlaceUpgrade(ctx)) {
            return def;
        }
        Integer old = legacyInt(ctx, key);
        return old == null ? def : old;
    }

    static String legacyStringIfUpgrade(Context ctx, String key) {
        if (!isInPlaceUpgrade(ctx)) {
            return null;
        }
        return legacyString(ctx, key);
    }

    /** system_server's lazy-load red line: before the provider is up, a
     *  legacy read throws and the caller must retry instead of latching the
     *  defaults. Only meaningful for an in-place upgrade whose prefs keys are
     *  still missing (the normal post-migration path never reads legacy). */
    static boolean legacyReadable(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            Settings.System.getString(ctx.getContentResolver(), PrefsKeys.KEY_FREE_ROT);
            Settings.System.getString(ctx.getContentResolver(), PrefsKeys.KEY_ROT_EXCLUDE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Public-API equivalent of Settings.System.contains: a Settings value is
     *  never stored as null, so null means the key is absent. Reading the
     *  string once also avoids a second provider round-trip. */
    private static Integer legacyInt(Context ctx, String key) {
        try {
            String raw = Settings.System.getString(ctx.getContentResolver(), key);
            return raw == null ? null : Integer.valueOf(Integer.parseInt(raw));
        } catch (Throwable t) {
            return null; // missing, unparsable, or provider not up yet
        }
    }

    private static String legacyString(Context ctx, String key) {
        try {
            return Settings.System.getString(ctx.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }
}
