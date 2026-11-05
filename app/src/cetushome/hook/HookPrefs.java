package cetushome.hook;

import android.content.SharedPreferences;
import android.os.Process;

import java.io.File;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/** Read-only bridge to the module's private prefs, used from the hooked
 *  launcher / SystemUI / system_server processes.
 *
 *  Classic XSharedPreferences is read-only by construction (edit() throws),
 *  so the hook side can never become a writer. The module app owns writes.
 *
 *  Change detection: legacy XSharedPreferences does not reliably deliver
 *  listener callbacks (the watcher needs the new-module path), so the
 *  system_server caller polls reload() behind a 500 ms debounce. A listener
 *  is still registered opportunistically; when it works it only sets a dirty
 *  flag that skips the debounce. */
final class HookPrefs {

    private static final String TAG = "CetusHomeHook";
    private static final String MODULE_PKG = "cetushome.rotationunlock";

    private static XSharedPreferences xsp;
    private static boolean initTried;
    private static boolean ready;
    private static boolean listenerTried;
    /** Strong reference: XSharedPreferences does not keep one, and a
     *  collected listener would silently disable the fast path. */
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;
    private static boolean readErrorLogged;
    private static volatile boolean dirty;

    private HookPrefs() {
    }

    /** Construct the bridge once. Returns false if the platform cannot
     *  provide XSharedPreferences (callers then retry later / fall back to
     *  the legacy read-only path). */
    static synchronized boolean ensureReady() {
        if (initTried) {
            return ready;
        }
        initTried = true;
        try {
            if (MixPrefs.USE_DEVICE_PROTECTED) {
                // AID_USER_OFFSET (100000) layout; UserHandle.getUserId is a
                // hidden API and not available against the public SDK jar.
                int userId = Process.myUid() / 100000;
                File f = new File("/data/user_de/" + userId + "/" + MODULE_PKG
                        + "/shared_prefs/" + PrefsKeys.PREFS_NAME + ".xml");
                xsp = new XSharedPreferences(f);
            } else {
                // xposedsharedprefs meta is declared, so LSPosed resolves the
                // (package, name) pair to the readable prefs location it
                // serves — the direct /data/data path is SELinux-blocked for
                // hooked processes (verified on LSPosed 2.2.1).
                xsp = new XSharedPreferences(MODULE_PKG, PrefsKeys.PREFS_NAME);
            }
            ready = true;
            // contains() waits for the initial async load: one real
            // readability probe in the readiness line
            log("prefs bridge ready, readable="
                    + xsp.contains(PrefsKeys.KEY_FREE_ROT));
        } catch (Throwable t) {
            log("prefs bridge unavailable: " + t);
            ready = false;
        }
        return ready;
    }

    static boolean contains(String key) {
        if (!ensureReady()) {
            return false;
        }
        try {
            return xsp.contains(key);
        } catch (Throwable t) {
            logReadError(t);
            return false;
        }
    }

    static int getInt(String key, int def) {
        if (!ensureReady()) {
            return def;
        }
        try {
            return xsp.getInt(key, def);
        } catch (Throwable t) {
            logReadError(t);
            return def;
        }
    }

    static boolean getBoolean(String key, boolean def) {
        if (!ensureReady()) {
            return def;
        }
        try {
            return xsp.getBoolean(key, def);
        } catch (Throwable t) {
            logReadError(t);
            return def;
        }
    }

    static String getString(String key, String def) {
        if (!ensureReady()) {
            return def;
        }
        try {
            return xsp.getString(key, def);
        } catch (Throwable t) {
            logReadError(t);
            return def;
        }
    }

    static Set<String> getStringSet(String key, Set<String> def) {
        if (!ensureReady()) {
            return def;
        }
        try {
            return xsp.getStringSet(key, def);
        } catch (Throwable t) {
            logReadError(t);
            return def;
        }
    }

    static void reload() {
        if (!ensureReady()) {
            return;
        }
        try {
            xsp.reload();
        } catch (Throwable t) {
            logReadError(t);
        }
    }

    /** Optional fast path: if the platform supports the file watcher the
     *  callback sets dirty and the next hook call reloads immediately.
     *  Otherwise the 500 ms poll in MainHook covers the change. */
    static void registerChangeListener() {
        if (!ensureReady() || listenerTried) {
            return;
        }
        listenerTried = true;
        try {
            listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    dirty = true;
                }
            };
            xsp.registerOnSharedPreferenceChangeListener(listener);
            log("prefs change listener registered");
        } catch (Throwable t) {
            log("prefs change listener unavailable (500ms polling): " + t);
        }
    }

    /** Consume the listener's dirty flag (one-shot). */
    static boolean takeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    private static void logReadError(Throwable t) {
        if (!readErrorLogged) {
            readErrorLogged = true;
            log("prefs read failed: " + t);
        }
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }
}
