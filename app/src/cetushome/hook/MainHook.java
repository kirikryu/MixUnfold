package cetushome.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module for com.miui.home on MIX FOLD 1 (cetus).
 *
 * Behavior-only changes — the platform-signed APK is never touched, so all
 * signature-level privileges stay intact:
 *
 *  1. DeviceConfig.updateRotate: stock hard-excludes cetus, leaving the
 *     unfolded home rotation-locked. Re-derive sRotatable instead — from the
 *     live launcher display, not the application context (see 1b).
 *  1b. DeviceConfig.isRotatable: re-derive the fold state at every read.
 *     Stock caches a process-wide static from the application-context
 *     configuration, which does not track fold transitions; after unfolding,
 *     the cover screen inherited rotatable=true and rotated too. Consumed
 *     live from the display the launcher is on right now.
 *  2. LauncherCellCountCompatDevice.shouldUseDeviceValue: force true.
 *  3. Column getters: live single column count (settings-driven).
 *  4. setWorkspaceCellPadding: tunable bezel while unfolded; DeviceProfile
 *     receives B/2 so cellWidth = (pageW - B) / columns.
 *  5. CellLayout alternating alignment: even pages pad B on the left, odd
 *     pages pad 0 — pages interlock at the seam, so cross-page column pitch
 *     equals in-page pitch while the screen edges keep the full bezel.
 *  6/7. Landscape three-page mode (cetus_home_land_pages=3): page width
 *     becomes screen/3 at creation and on every size/orientation change, so
 *     the unfolded landscape home shows three cover-style pages.
 *  8. calcGridSize: in three-page mode the working width follows screen/3.
 *  9. DeviceConfig.getCellCountX: live column count. Stock caches the count
 *     at process start and never re-evaluates it on rotation.
 *  10. Application.onCreate: register an in-process receiver so the module
 *     UI can cleanly restart the launcher.
 *  11/12. Even-page policy: pad the page count to a multiple of the current
 *     snap unit so flipping always lands on a complete pair/triple.
 *  13b. Launcher.onConfigurationChanged / onResume: re-run the orientation
 *     request decision. The launcher declares every config change (never
 *     recreates) and stock RotationHelper.onConfigurationChanged is an
 *     empty stub — so a fold transition never re-issued the request and the
 *     sensor-following request from the unfolded session stayed active on
 *     the cover screen (outer-screen rotation, fixed by 1b + 13b together).
 *
 * A second target is hooked in its own process (com.android.systemui):
 * S1. NotificationShadeWindowControllerImpl.adjustScreenOrientation after —
 *     stock hard-codes cetus's keyguard window (bouncer / shade / dozing,
 *     both screens) to PORTRAIT; the "|| DEVICE == cetus" clause short-
 *     circuits the fold escape, so the result is rewritten to USER (follow
 *     auto-rotate) only when the shade State says inLargeScreen (inner
 *     screen unfolded). The folded cover lockscreen passes through
 *     untouched, and the unlocked path (UNSPECIFIED) is never modified.
 *     Governed by the "锁屏允许横屏" switch (cetus_home_lockscreen_rot).
 *
 * A third target is system_server (LSPosed scope 系统框架/"android"):
 * W1/W2/W3 inner-screen free rotation (see hookSystemServer). MIUI's fold
 * orientation engine ships in services.jar but is disabled on cetus
 * (ro.config.miui_orientation_enable unset — every call site is gated by
 * MiuiOrientationStub.isEnabled(), verified dead), so apps run on their
 * manifest orientation. Rather than that dead write point, the two live
 * read points are rewritten to the engine's effective output:
 * UNSPECIFIED (orientation preference) and ORIENTATION_UNDEFINED
 * (configuration orientation). The unfolded inner display then follows
 * auto-rotate for every registered app; the folded cover screen is never
 * touched (live per-record fold gate, failures closed to "no rewrite").
 *
 * Settings are read ONCE per process into a static cache and refreshed by
 * restarting the process (launcher from the module UI; SystemUI via
 * `pkill -f com.android.systemui`). The system_server side instead watches
 * its two keys with a ContentObserver — switch and exclusion list apply
 * immediately, no restart. Hook bodies must stay cheap because getCellCountX
 * and getOrientation sit on hot layout paths.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "CetusHomeHook";
    public static final String ACTION_RESTART = "cetushome.rotationunlock.RESTART";
    public static final String KEY_COLUMNS = "cetus_home_columns";
    public static final String KEY_PAD_PORT = "cetus_home_padding_side";
    public static final String KEY_PAD_LAND = "cetus_home_padding_side_land";
    public static final String KEY_LAND_PAGES = "cetus_home_land_pages";
    public static final String KEY_PAD_OPT = "cetus_home_pad_opt";
    public static final String KEY_LOCK_ROT = "cetus_home_lockscreen_rot";
    public static final String KEY_FREE_ROT = "cetus_free_rot";
    public static final String KEY_ROT_EXCLUDE = "cetus_free_rot_exclude";

    private static Class<?> dcCls;
    private static Class<?> appCls;
    private static Class<?> uiThreadCls;

    // process-wide config cache; refreshed by restarting the launcher
    private static volatile boolean cfgLoaded;
    private static int cfgColumns = 3;
    private static int cfgPadPort = 120;
    private static int cfgPadLand = 120;
    private static int cfgLandPages = 3;
    private static boolean cfgPadOpt;

    // SystemUI-side config: the keyguard switch, read once per SystemUI
    // process (default ON); disable + restart SystemUI restores stock.
    private static volatile boolean lockRotCfgLoaded;
    private static boolean cfgLockRot = true;

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    private static int readInt(Context ctx, String key, int def) {
        try {
            return android.provider.Settings.System.getInt(ctx.getContentResolver(), key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static void loadCfg(Context ctx) {
        if (cfgLoaded || ctx == null) {
            return;
        }
        cfgColumns = readInt(ctx, KEY_COLUMNS, 3);
        cfgPadPort = readInt(ctx, KEY_PAD_PORT, 120);
        cfgPadLand = readInt(ctx, KEY_PAD_LAND, 120);
        cfgLandPages = readInt(ctx, KEY_LAND_PAGES, 3);
        cfgPadOpt = readInt(ctx, KEY_PAD_OPT, 0) == 1;
        cfgLoaded = true;
        log("config loaded: columns=" + cfgColumns + " padPort=" + cfgPadPort
                + " padLand=" + cfgPadLand + " landPages=" + cfgLandPages
                + " padOpt=" + cfgPadOpt);
    }

    /** Keyguard switch for the SystemUI process, read once from the shade
     *  controller's mContext; the launcher's loadCfg is deliberately not
     *  touched (launcher keys are meaningless here and vice versa). */
    private static void loadLockRotCfg(Context ctx) {
        if (lockRotCfgLoaded || ctx == null) {
            return;
        }
        cfgLockRot = readInt(ctx, KEY_LOCK_ROT, 1) == 1;
        lockRotCfgLoaded = true;
        log("systemui config loaded: lockRot=" + cfgLockRot);
    }

    private static Context appContext() {
        try {
            return (Context) XposedHelpers.callStaticMethod(appCls, "getInstance");
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isLandscape(Context ctx) {
        return ctx != null && ctx.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    /** Authoritative orientation: which DeviceProfile is active right now. */
    private static boolean profileLandscape() {
        try {
            Object profile = XposedHelpers.getStaticObjectField(dcCls, "mActiveProfile");
            if (profile == null) {
                return false;
            }
            int w = (int) XposedHelpers.callMethod(profile, "getScreenWidth");
            int h = (int) XposedHelpers.callMethod(profile, "getScreenHeight");
            return w > h;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean unfoldedLarge(Context ctx) {
        try {
            return Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                    dcCls, "isInFoldDeviceLargeScreen", ctx));
        } catch (Throwable t) {
            return false;
        }
    }

    /** The live Launcher activity as a Context, or null (it is the one
     *  Context whose Resources always track the display home is on). */
    private static Context launcherContext() {
        try {
            return (Context) XposedHelpers.callStaticMethod(appCls, "getLauncher");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Fold state at decision time: is the display home is on right now the
     * large (inner) one? Stock derives sRotatable once per updateRotate call
     * from the application-context configuration, which goes stale across
     * fold transitions — after unfolding, the cover screen inherited
     * rotatable=true and rotated too. sm screenWidthDp is ground truth of
     * the live display config (cetus: inner ≈654dp, cover ≈393dp); MIUI's
     * own large-screen evaluator on the live context is the fallback.
     * Returns null when nothing can be evaluated yet — callers then keep
     * the stock value instead of guessing.
     */
    private static Boolean evaluateRotatable() {
        Context ctx = launcherContext();
        if (ctx == null) {
            ctx = appContext();
        }
        if (ctx == null) {
            return null;
        }
        boolean miui = unfoldedLarge(ctx);
        try {
            int sw = ctx.getResources().getConfiguration().smallestScreenWidthDp;
            if (sw > 0) {
                boolean large = sw >= 600;
                if (large != miui) {
                    log("fold-state disagree: miui=" + miui + " sw=" + sw + "dp -> " + large);
                }
                return large;
            }
        } catch (Throwable ignored) {
        }
        return miui;
    }

    /**
     * Fold-state gate for the page/padding/parity hooks, shared by every
     * call site so they cannot disagree. Uses the live launcher display
     * (see evaluateRotatable); when the launcher does not exist yet
     * (process-start paths) falls back to MIUI's evaluator on the given
     * context, preserving pre-1.6.4 behavior.
     */
    private static boolean onInnerScreen(Context fallbackCtx) {
        Boolean live = evaluateRotatable();
        if (live != null) {
            return live;
        }
        return unfoldedLarge(fallbackCtx);
    }

    /** RotationHelper.getScreenOrientationByRequest: 2 → LOCKED(14),
     *  3 → PORTRAIT, anything else → UNSPECIFIED. */
    private static int orientationForRequest(int request) {
        if (request == 2) {
            return 14;
        }
        if (request == 3) {
            return 1;
        }
        return -1;
    }

    /**
     * Re-run RotationHelper.notifyChange's decision for the given launcher.
     * notifyChange is private and every public re-entry point dedupes on
     * unchanged state, which a fold transition can leave untouched — so the
     * decision is replicated here exactly and the request re-issued only
     * when the resulting orientation actually changed:
     * rotatable → UNSPECIFIED (follow auto-rotate); otherwise whatever the
     * state machine has pending (idle → PORTRAIT, the stock cover behavior).
     *
     * The helper is reached by FIELD access only: enumerating Launcher's
     * methods (XposedHelpers.callMethod) resolves every method signature of
     * the class, and on HyperOS builds Launcher.getNewHomeView()'s return
     * type drags in the optional NewHomeView plugin classes →
     * NoClassDefFoundError on every call. Field enumeration resolves names
     * lazily and is safe.
     */
    private static void renotifyRotationRequest(Object launcher) {
        try {
            Boolean live = evaluateRotatable();
            if (live == null) {
                return; // cannot decide — leave the stock state machine alone
            }
            XposedHelpers.setStaticBooleanField(dcCls, "sRotatable", live);
            boolean rotatable = live;
            Object helper = XposedHelpers.getObjectField(launcher, "mRotationHelper");
            if (helper == null) {
                return;
            }
            int multi = XposedHelpers.getIntField(helper, "mMultiWindowRotationRequest");
            int trans = XposedHelpers.getIntField(helper, "mCurrentTransitionRequest");
            int state = XposedHelpers.getIntField(helper, "mCurrentStateRequest");
            int flag;
            if (rotatable) {
                flag = -1;
            } else if (multi != 0) {
                flag = orientationForRequest(multi);
            } else if (trans != 0) {
                flag = orientationForRequest(trans);
            } else if (state == 2) {
                flag = 14;
            } else if (state == 1) {
                flag = (Boolean) XposedHelpers.callStaticMethod(dcCls, "isInMultiWindowMode")
                        ? -1 : 3;
            } else {
                flag = 1;
            }
            int last = XposedHelpers.getIntField(helper, "mLastActivityFlags");
            if (flag != last) {
                XposedHelpers.setIntField(helper, "mLastActivityFlags", flag);
                XposedHelpers.callStaticMethod(uiThreadCls, "setOrientationAsync",
                        launcher, flag);
                log("renotify: flag " + last + " -> " + flag);
            }
        } catch (Throwable t) {
            log("renotify failed: " + t);
        }
    }

    /** Three cover-style pages on the unfolded landscape inner screen? */
    private static boolean threePagesLandscape(Context ctx) {
        loadCfg(ctx);
        return onInnerScreen(ctx) && isLandscape(ctx) && cfgLandPages == 3;
    }

    /**
     * Effective outer bezel for the live orientation, under the constraint
     * scheme: portrait locked at 120, landscape auto by page count
     * (2 pages = 280, 3 pages = 0 full-bleed). Callers gate on cfgPadOpt —
     * with "外侧边距优化" off the module touches no margins at all.
     */
    private static int bezelFor(Context ctx) {
        loadCfg(ctx);
        return isLandscape(ctx) ? (cfgLandPages == 3 ? 0 : 280) : 120;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if ("android".equals(lpp.packageName)) {
            hookSystemServer(lpp);
            return;
        }
        if ("com.android.systemui".equals(lpp.packageName)) {
            hookSystemUI(lpp);
            return;
        }
        if (!"com.miui.home".equals(lpp.packageName)) {
            return;
        }
        dcCls = XposedHelpers.findClass("com.miui.home.launcher.DeviceConfig", lpp.classLoader);
        appCls = XposedHelpers.findClass("com.miui.home.launcher.Application", lpp.classLoader);
        uiThreadCls = XposedHelpers.findClass(
                "com.miui.home.launcher.util.UiThreadHelper", lpp.classLoader);
        log("attached, classLoader=" + lpp.classLoader.getClass().getName());

        // 1. rotation unlock — keep the static itself consistent, derived
        // from the live launcher display (see 1b for why)
        try {
            XposedHelpers.findAndHookMethod(dcCls, "updateRotate",
                    Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                            try {
                                loadCfg((Context) param.args[0]);
                                Boolean live = evaluateRotatable();
                                boolean rotatable = live != null
                                        ? live
                                        : Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                                        dcCls, "isInFoldDeviceLargeScreen", param.args[0]));
                                XposedHelpers.setStaticBooleanField(dcCls, "sRotatable", rotatable);
                            } catch (Throwable t) {
                                log("updateRotate after-hook failed: " + t);
                            }
                        }
                    });
            log("hook 1 ok: DeviceConfig.updateRotate");
        } catch (Throwable t) {
            log("hook 1 FAILED: " + t);
        }

        // 1b. live fold state: re-derive at every read. The stock static is
        // computed once per updateRotate from the application context, whose
        // configuration does not track fold transitions — the consumption
        // point must see the display home is on right now.
        try {
            XposedHelpers.findAndHookMethod(dcCls, "isRotatable", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                    try {
                        Boolean live = evaluateRotatable();
                        if (live == null) {
                            return;
                        }
                        Object curObj = XposedHelpers.getStaticObjectField(dcCls, "sRotatable");
                        boolean cur = Boolean.TRUE.equals(curObj);
                        if (cur != live) {
                            log("rotatable " + cur + " -> " + live);
                        }
                        param.setResult(live);
                    } catch (Throwable t) {
                        log("isRotatable hook failed: " + t);
                    }
                }
            });
            log("hook 1b ok: DeviceConfig.isRotatable live fold state");
        } catch (Throwable t) {
            log("hook 1b FAILED: " + t);
        }

        // 2. always consult device grid values
        try {
            final Class<?> gridBase = XposedHelpers.findClass(
                    "com.miui.home.launcher.compat.LauncherCellCountCompatDevice", lpp.classLoader);
            XposedHelpers.findAndHookMethod(gridBase, "shouldUseDeviceValue",
                    Context.class, int.class, XC_MethodReplacement.returnConstant(true));
            log("hook 2 ok: shouldUseDeviceValue -> true");
        } catch (Throwable t) {
            log("hook 2 FAILED: " + t);
        }

        // 3. column getters (context variant, feeds loadCellsCountConfig)
        try {
            final Class<?> gridBase = XposedHelpers.findClass(
                    "com.miui.home.launcher.compat.LauncherCellCountCompatDevice", lpp.classLoader);
            XC_MethodHook columnsHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                    loadCfg((Context) param.args[0]);
                    param.setResult(cfgColumns);
                }
            };
            for (String name : new String[]{"getCellCountXDef", "getCellCountXMax", "getCellCountXMin"}) {
                XposedHelpers.findAndHookMethod(gridBase, name, Context.class, columnsHook);
            }
            log("hook 3 ok: base column getters");
        } catch (Throwable t) {
            log("hook 3 FAILED: " + t);
        }

        // 3b. subclass getters
        try {
            XC_MethodHook columnsHook2 = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                    loadCfg((Context) param.args[0]);
                    param.setResult(cfgColumns);
                }
            };
            for (String cls : new String[]{
                    "com.miui.home.launcher.compat.LauncherCellCountCompatDeviceFold",
                    "com.miui.home.launcher.compat.LauncherCellCountCompatDevicesCetus"}) {
                final Class<?> c = XposedHelpers.findClass(cls, lpp.classLoader);
                for (String name : new String[]{
                        "getCellCountXDefFromDevice", "getCellCountXMaxFromDevice",
                        "getCellCountXMinFromDevice"}) {
                    XposedHelpers.findAndHookMethod(c, name, Context.class, columnsHook2);
                }
            }
            log("hook 3b ok: subclass column getters");
        } catch (Throwable t) {
            log("hook 3b FAILED: " + t);
        }

        // 4. tunable bezel while unfolded (DeviceProfile gets B/2)
        try {
            XposedHelpers.findAndHookMethod(dcCls, "setWorkspaceCellPadding",
                    android.content.res.Resources.class, Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                            try {
                                Context ctx = (Context) param.args[1];
                                loadCfg(ctx);
                                // constraint scheme OFF -> stock padding stands,
                                // the module does not touch margins at all
                                if (cfgPadOpt && onInnerScreen(ctx)) {
                                    XposedHelpers.setStaticIntField(
                                            dcCls, "sWorkspaceCellPaddingSide", bezelFor(ctx) / 2);
                                }
                            } catch (Throwable t) {
                                log("padding hook failed: " + t);
                            }
                        }
                    });
            log("hook 4 ok: setWorkspaceCellPadding");
        } catch (Throwable t) {
            log("hook 4 FAILED: " + t);
        }

        // 5. alternating page alignment (unfolded only); modulo follows the
        // page count of the current orientation (2 portrait / 2-3 landscape)
        try {
            final Class<?> cellLayoutCls = XposedHelpers.findClass(
                    "com.miui.home.launcher.CellLayout", lpp.classLoader);
            XC_MethodHook alignHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    try {
                        View self = (View) param.thisObject;
                        Object cellScreen = XposedHelpers.callMethod(self, "getParent");
                        if (cellScreen == null) {
                            return;
                        }
                        Object workspace = XposedHelpers.callMethod(cellScreen, "getParent");
                        if (workspace == null) {
                            return;
                        }
                        Context app = appContext();
                        if (app == null) {
                            return;
                        }
                        loadCfg(app);
                        // alignment is part of the constraint scheme — off
                        // means stock padding and stock seams everywhere
                        if (!cfgPadOpt || !onInnerScreen(app)) {
                            return;
                        }
                        int b = bezelFor(app);
                        int modulo = isLandscape(app) ? cfgLandPages : 2;
                        int index = (int) XposedHelpers.callMethod(workspace, "indexOfChild", cellScreen);
                        XposedHelpers.setIntField(self, "mCellPaddingLeft",
                                index % modulo == 0 ? b : 0);
                    } catch (Throwable t) {
                        log("align failed: " + t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(cellLayoutCls, "onScreenOrientationChanged", alignHook);
            XposedHelpers.findAndHookMethod(cellLayoutCls, "onScreenSizeChanged", alignHook);
            log("hook 5 ok: CellLayout alternating alignment");
        } catch (Throwable t) {
            log("hook 5 FAILED: " + t);
        }

        // 6/7. page width: screen/2 stock, screen/3 in landscape three-page mode.
        // Stock never refreshes the width after creation; this also fixes that.
        try {
            final Class<?> cellScreenCls = XposedHelpers.findClass(
                    "com.miui.home.launcher.CellScreen", lpp.classLoader);
            XC_MethodHook widthHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    try {
                        View self = (View) (param.thisObject != null
                                ? param.thisObject : param.getResult());
                        Context app = appContext();
                        if (app == null || !onInnerScreen(app)) {
                            return;
                        }
                        loadCfg(app);
                        ViewGroup.LayoutParams lp = self.getLayoutParams();
                        if (lp == null) {
                            return;
                        }
                        int screen = (Integer) XposedHelpers.callStaticMethod(dcCls, "getScreenWidth");
                        boolean p3 = isLandscape(app) && cfgLandPages == 3;
                        int pages = p3 ? 3 : 2;
                        // Solve the page width against ScreenView's own snap
                        // formula: visibleRange = (content + gap) / (child + gap).
                        // A plain screen/pages width can round down to one page
                        // too few, which silently keeps paging in pairs.
                        int gap = 0;
                        try {
                            Object ws = self.getParent();
                            if (ws != null) {
                                gap = XposedHelpers.getIntField(ws, "mFixedGap");
                            }
                        } catch (Throwable t) {
                            gap = 0;
                        }
                        int w = (screen + gap) / pages - gap;
                        if (lp.width != w) {
                            lp.width = w;
                            self.requestLayout();
                            if (p3) {
                                try {
                                    Object ws = self.getParent();
                                    int vr = ws == null ? -1
                                            : XposedHelpers.getIntField(ws, "mVisibleRange");
                                    int cw = ws == null ? -1
                                            : XposedHelpers.getIntField(ws, "mChildScreenMeasureWidth");
                                    log("pages3 width=" + w + " gap=" + gap
                                            + " visibleRange=" + vr + " childW=" + cw);
                                } catch (Throwable t) {
                                    log("pages3 width=" + w + " gap=" + gap);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        log("page width hook failed: " + t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(cellScreenCls, "createCellScreen",
                    Context.class, widthHook);
            XposedHelpers.findAndHookMethod(cellScreenCls, "onScreenSizeChanged", widthHook);
            XposedHelpers.findAndHookMethod(cellScreenCls, "onScreenOrientationChanged", widthHook);
            log("hook 6/7 ok: CellScreen page width");
        } catch (Throwable t) {
            log("hook 6/7 FAILED: " + t);
        }

        // 8. three-page mode: grid working width follows screen/3
        try {
            XposedHelpers.findAndHookMethod(dcCls, "calcGridSize",
                    Context.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                            try {
                                Context ctx = (Context) param.args[0];
                                if (!threePagesLandscape(ctx)) {
                                    return;
                                }
                                Object profile = XposedHelpers.getStaticObjectField(
                                        dcCls, "mActiveProfile");
                                if (profile == null) {
                                    return;
                                }
                                // grid span must match the padding the layout
                                // actually uses (post-hook-4 value: B/2 when
                                // the constraint scheme is on, stock 83 else)
                                int pad = (Integer) XposedHelpers.callStaticMethod(
                                        dcCls, "getWorkspaceCellPaddingSide");
                                int bw = (Integer) XposedHelpers.callStaticMethod(
                                        dcCls, "getScreenWidth") / 3 - 2 * pad;
                                XposedHelpers.callMethod(profile, "setCellWorkingWidth", bw);
                                XposedHelpers.callMethod(profile, "calculateCellSize", ctx);
                            } catch (Throwable t) {
                                log("calcGridSize hook failed: " + t);
                            }
                        }
                    });
            log("hook 8 ok: calcGridSize three-page working width");
        } catch (Throwable t) {
            log("hook 8 FAILED: " + t);
        }

        // 9. live column count (see class doc, item 9)
        try {
            XposedHelpers.findAndHookMethod(dcCls, "getCellCountX", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                    loadCfg(appContext());
                    param.setResult(cfgColumns);
                }
            });
            log("hook 9 ok: getCellCountX live");
        } catch (Throwable t) {
            log("hook 9 FAILED: " + t);
        }

        // 10. in-process restart receiver (module UI -> clean launcher restart)
        try {
            XC_MethodHook regHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    try {
                        Context ctx0 = (Context) param.thisObject;
                        android.content.IntentFilter f =
                                new android.content.IntentFilter(ACTION_RESTART);
                        ctx0.registerReceiver(new android.content.BroadcastReceiver() {
                            @Override
                            public void onReceive(android.content.Context c, android.content.Intent i) {
                                log("restart requested, killing launcher process");
                                android.os.Process.killProcess(android.os.Process.myPid());
                            }
                        }, f);
                        log("restart receiver registered");
                    } catch (Throwable t) {
                        log("restart receiver FAILED: " + t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(appCls, "onCreate", regHook);
            log("hook 10 ok: Application.onCreate (restart receiver)");
        } catch (Throwable t) {
            log("hook 10 FAILED: " + t);
        }

        // 11/12. even-page policy: keep the page count a multiple of the
        // current orientation's snap unit so flipping always lands on a
        // complete pair/triple and no lone last page ever exists.
        try {
            final Class<?> launcherCls = XposedHelpers.findClass(
                    "com.miui.home.launcher.Launcher", lpp.classLoader);
            final Class<?> workspaceCls = XposedHelpers.findClass(
                    "com.miui.home.launcher.Workspace", lpp.classLoader);
            XC_MethodHook resumeHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    try {
                        // insurance for fold transitions that somehow skipped
                        // the config-change re-request (deduped, no-op when
                        // the orientation already matches)
                        renotifyRotationRequest(param.thisObject);
                    } catch (Throwable t) {
                        log("resume renotify FAILED: " + t);
                    }
                    try {
                        Object workspace = XposedHelpers.getObjectField(
                                param.thisObject, "mWorkspace");
                        if (workspace == null) {
                            return;
                        }
                        View wv = (View) workspace;
                        wv.post(new Runnable() {
                            @Override public void run() {
                                ensureEvenPages(workspace);
                            }
                        });
                    } catch (Throwable t) {
                        log("resume even-pages FAILED: " + t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(launcherCls, "onResume", resumeHook);
            XposedHelpers.findAndHookMethod(workspaceCls, "reorderScreens",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                            ensureEvenPages(param.thisObject);
                        }
                    });
            log("hook 11/12 ok: even-page policy");
        } catch (Throwable t) {
            log("hook 11/12 FAILED: " + t);
        }

        // 13. Launcher.checkDualScreen re-asserts stock page width
        // (screen/2) on every config change, overriding hook 6/7 — re-apply
        // the three-page width after it runs.
        //
        // 15/16 (same block below): veto hooks on Workspace.deleteScreen /
        // removeScreen — stock prunes trailing empty pages on edit-mode
        // entry/exit, which discarded our parity page.
        try {
            final Class<?> launcherCls2 = XposedHelpers.findClass(
                    "com.miui.home.launcher.Launcher", lpp.classLoader);
            XC_MethodHook dualHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    try {
                        Context ctx = (Context) param.thisObject;
                        if (!threePagesLandscape(ctx)) {
                            return;
                        }
                        Object workspace = XposedHelpers.getObjectField(
                                param.thisObject, "mWorkspace");
                        if (workspace == null) {
                            return;
                        }
                        int screen = (Integer) XposedHelpers.callStaticMethod(
                                dcCls, "getScreenWidth");
                        int count = (Integer) XposedHelpers.callMethod(
                                workspace, "getScreenCount");
                        for (int i = 0; i < count; i++) {
                            View page = (View) XposedHelpers.callMethod(
                                    workspace, "getChildAt", i);
                            ViewGroup.LayoutParams lp = page.getLayoutParams();
                            if (lp != null && lp.width != screen / 3) {
                                lp.width = screen / 3;
                                page.requestLayout();
                            }
                        }
                    } catch (Throwable t) {
                        log("dualScreen hook failed: " + t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(launcherCls2, "checkDualScreen", dualHook);
            log("hook 13 ok: checkDualScreen width re-apply");
        } catch (Throwable t) {
            log("hook 13 FAILED: " + t);
        }

        // 13b. re-issue the orientation request on config changes. The
        // launcher handles every config change itself (no recreation) and
        // stock RotationHelper.onConfigurationChanged is an empty stub —
        // after a fold transition nothing re-evaluated sRotatable, so the
        // sensor-following request from the unfolded session kept rotating
        // the cover screen.
        try {
            final Class<?> launcherCls3 = XposedHelpers.findClass(
                    "com.miui.home.launcher.Launcher", lpp.classLoader);
            XC_MethodHook renotifyHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    renotifyRotationRequest(param.thisObject);
                }
            };
            XposedHelpers.findAndHookMethod(launcherCls3, "onConfigurationChanged",
                    android.content.res.Configuration.class, renotifyHook);
            log("hook 13b ok: Launcher.onConfigurationChanged rotation re-request");
        } catch (Throwable t) {
            log("hook 13b FAILED: " + t);
        }

        // 14. triple snap: FoldScreenIndexCalculator hard-codes "% 2" pair
        // calibration (and even forces the last page back by one), which
        // fights three-page mode. In that mode snap to triple boundaries.
        try {
            final Class<?> calcCls = XposedHelpers.findClass(
                    "com.miui.home.launcher.util.FoldScreenIndexCalculator", lpp.classLoader);
            XposedHelpers.findAndHookMethod(calcCls, "obtainValidCurrentScreenIndex",
                    int.class, int.class, int.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                            try {
                                Context app = appContext();
                                if (!threePagesLandscape(app)) {
                                    return;
                                }
                                int idx = (Integer) param.args[0];
                                int count = (Integer) param.args[1];
                                int clamped = Math.max(0, Math.min(idx, count - 1));
                                param.setResult(clamped - clamped % 3);
                            } catch (Throwable t) {
                                log("foldIdx hook failed: " + t);
                            }
                        }
                    });
            log("hook 14 ok: FoldScreenIndexCalculator triple snap");
        } catch (Throwable t) {
            log("hook 14 FAILED: " + t);
        }

        // 15/16. even-page veto: block removal of the trailing EMPTY page
        // when removing it would break the snap-unit parity. Non-empty
        // pages (user content) always remain removable.
        try {
            final Class<?> workspaceCls2 = XposedHelpers.findClass(
                    "com.miui.home.launcher.Workspace", lpp.classLoader);
            XC_MethodHook vetoHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                    try {
                        Context app = appContext();
                        if (app == null || !onInnerScreen(app) || !cfgPadOpt) {
                            return;
                        }
                        loadCfg(app);
                        // parity lock is a portrait two-up feature; landscape
                        // three-page mode pages freely (no veto, no parity)
                        if (isLandscape(app) && cfgLandPages == 3) {
                            return;
                        }
                        Object ws = param.thisObject;
                        int count = (Integer) XposedHelpers.callMethod(ws, "getScreenCount");
                        if (count <= 1) {
                            return;
                        }
                        int modulo = isLandscape(app) ? cfgLandPages : 2;
                        int idx;
                        if (param.args[0] instanceof Long) {
                            idx = (Integer) XposedHelpers.callMethod(
                                    ws, "getScreenIndexById", (Long) param.args[0]);
                        } else {
                            idx = (Integer) param.args[0];
                        }
                        if (idx != count - 1 || (count - 1) % modulo == 0) {
                            return; // not the trailing page, or removal keeps parity
                        }
                        Object cellScreen = XposedHelpers.callMethod(ws, "getCellScreen", idx);
                        Object layout = cellScreen == null ? null
                                : XposedHelpers.callMethod(cellScreen, "getCellLayout");
                        int children = layout == null ? -1
                                : (Integer) XposedHelpers.callMethod(layout, "getChildCount");
                        if (children != 0) {
                            return; // non-empty: user content, allow removal
                        }
                        log("even-pages: vetoed removal of trailing parity page (count="
                                + count + ")");
                        param.setResult(null);
                    } catch (Throwable t) {
                        log("even-page veto failed: " + t);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(workspaceCls2, "deleteScreen",
                    long.class, vetoHook);
            XposedHelpers.findAndHookMethod(workspaceCls2, "removeScreen",
                    int.class, vetoHook);
            log("hook 15/16 ok: even-page removal veto");
        } catch (Throwable t) {
            log("hook 15/16 FAILED: " + t);
        }

        log("init complete");
    }

    /**
     * S1. SystemUI: unlock the unfolded (inner-screen) keyguard orientation.
     *
     * Stock adjustScreenOrientation hard-codes cetus's keyguard window
     * (bouncer / shade / dozing — both screens) to PORTRAIT: the fold
     * escape "(IS_FOLD && !inLargeScreen)" is short-circuited by the
     * "|| Build.DEVICE == cetus" clause, so neither the rotation-allowed
     * setting (KeyguardStateController path) nor inLargeScreen can ever
     * lift the lock — the method's result must be rewritten after it runs.
     * hookAllMethods avoids coupling to the State parameter type.
     *
     * The after-hook runs mid-apply(): applyWindowLayoutParams() later in
     * the same apply() copies mLpChanged into the live window params and
     * pushes them via updateViewLayout, so mutating mLpChanged here is
     * enough — no setAttributes of our own.
     *
     * Gates, in order (the folded cover screen must NEVER be touched):
     *  1. switch off -> no-op;
     *  2. stock result must be PORTRAIT — UNSPECIFIED (unlocked paths) and
     *     every other value pass through untouched, keeping post-unlock
     *     behavior identical to stock;
     *  3. State.inLargeScreen — only the unfolded inner screen is rewritten
     *     to USER (follow the system auto-rotate setting and user rotation);
     *     the folded cover lockscreen keeps its stock PORTRAIT request.
     *
     * Known risk (see README): MIUI likely locks cetus because the gen-1
     * fold landscape keyguard UI was never adapted. Landscape keyguard may
     * show layout issues (clock/date, wallpaper crop, swipe-up gesture area,
     * camera shortcut, notification shade) — verify both orientations live.
     * If AOD (the mDozing path) misbehaves in landscape, add a mDozing gate
     * here —
     *   if (XposedHelpers.getBooleanField(param.args[0], "mDozing")) return;
     * — which restores stock AOD while the lit lockscreen stays rotatable.
     * If landscape keyguard proves unusable overall, ship the switch
     * default-off and note the reason in the module description.
     */
    private static void hookSystemUI(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            final Class<?> nswcCls = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.NotificationShadeWindowControllerImpl",
                    lpp.classLoader);
            XposedBridge.hookAllMethods(nswcCls, "adjustScreenOrientation",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                            try {
                                loadLockRotCfg((Context) XposedHelpers.getObjectField(
                                        param.thisObject, "mContext"));
                                if (!cfgLockRot) {
                                    return; // switch off: stock behavior everywhere
                                }
                                WindowManager.LayoutParams lp =
                                        (WindowManager.LayoutParams) XposedHelpers.getObjectField(
                                                param.thisObject, "mLpChanged");
                                // 1 = SCREEN_ORIENTATION_PORTRAIT; the unlocked
                                // path writes -1 (UNSPECIFIED) and stays untouched
                                if (lp.screenOrientation != 1) {
                                    return;
                                }
                                // only the unfolded inner screen — the folded
                                // cover lockscreen request is never modified
                                if (!XposedHelpers.getBooleanField(
                                        param.args[0], "inLargeScreen")) {
                                    return;
                                }
                                lp.screenOrientation = 2; // SCREEN_ORIENTATION_USER
                                log("keyguard orientation PORTRAIT -> USER"
                                        + " (inLargeScreen=true)");
                            } catch (Throwable t) {
                                log("keyguard rotation hook failed: " + t);
                            }
                        }
                    });
            log("hook S1 ok: NotificationShadeWindowControllerImpl.adjustScreenOrientation");
        } catch (Throwable t) {
            log("hook S1 FAILED: " + t);
        }
    }

    // ================== system_server: inner-screen free rotation ============

    /** Stock camera apps, excluded in every state and never configurable —
     *  imaging direction follows the sensor (2026-10-31 `pm list packages`
     *  verified set). Embedded-config packages stay excluded too: unlocking
     *  them crashed MIUI's embedding extension on the landscape inner screen
     *  (Weibo -> MiuiSplitController "Invalid Task id"); the record-level
     *  isEmbedded() gate below keeps that lesson enforced. */
    private static final HashSet<String> CAMERA_PKGS = new HashSet<String>(Arrays.asList(
            "com.android.camera", "com.android.cameraextensions", "com.xiaomi.cameratools"));

    /**
     * Weak registry of activities whose locked orientation may be rewritten.
     * Key: the ActivityRecord instance itself — W1 registers param.thisObject
     * and W2/W3 query the very same reference, so lookups need no reflective
     * field reads; a token (IBinder) key would add one on each side with no
     * benefit. Synchronized wrapper: orientation evaluation runs under WMS
     * locks from several threads. Value: the stock requested orientation at
     * registration time — the source for restoring stock behavior when the
     * master switch goes off or the package is excluded.
     */
    private static final Map<Object, Integer> ROT_REG =
            Collections.synchronizedMap(new WeakHashMap<Object, Integer>());

    private static Class<?> actRecCls;
    private static Class<?> wcCls;
    private static Context sysCtx;
    private static HandlerThread cfgThread;
    private static Handler cfgHandler;
    private static volatile boolean sysCfgReady;
    private static volatile boolean cfgFreeRot = true;
    private static volatile HashSet<String> cfgExclude = new HashSet<String>();

    /** Rewrite logging capped per package — W2 sits on a recursive hot path
     *  and must not flood logcat during rotation. */
    private static final HashMap<String, Integer> REWRITE_LOGGED = new HashMap<String, Integer>();

    /** W4 state: whether the folded cover pin is currently applied (for
     *  transition logging only). */
    private static volatile boolean w4FoldedPinned;

    // ==================== engine mode ========================================
    //
    // The owner asked to "remove all cetus restrictions and treat the device
    // like later folds". The full MIUI fold orientation engine ships in
    // miui-services.jar (MiuiOrientationImpl) but is dormant: isEnabled()
    // = prop && MIUI-optimization && smart-rotation setting, all false on
    // cetus. IS_FOLD however is true (persist.sys.muiltdisplay_type=2) and
    // the impl is complete. Lighting it up replaces W1-W5 wholesale:
    //  - write-time request neutralization (info.screenOrientation=-1 +
    //    super(-1)) — starting windows are consistent by construction, the
    //    whole flash/pause cluster dissolves;
    //  - native per-display rotation-lock separation: outer pair LOCKED/
    //    portrait (free-rotating apps stay portrait on the cover), explicit
    //    landscape (video fullscreen) still rotates, inner screen free;
    //  - embedded/parallel-window records exempted natively (stock);
    //  - per-package allowlist persisted to a settings file.
    // The engine's inner unlock is allowlist-based, so this module bridges
    // its exclusion semantics onto it: user settings = all installed
    // packages - user exclusions - camera - launcher (the launcher keeps
    // its own rotation hooks). W1-W5 stay in the source as a fallback for
    // ROMs without the impl class but are not registered in engine mode. */
    private static volatile boolean engineSynced;
    private static final HashSet<String> engineSyncedExclude = new HashSet<String>();
    private static final Object ENGINE_SYNC_LOCK = new Object();
    private static boolean engineMode;
    private static volatile boolean engineDiagLogged;
    private static volatile boolean engineFirstCallLogged;
    /** system_server classloader captured in engine mode — the legacy
     *  actRecCls resolution never runs there, and systemContext() needs a
     *  loader that can see android.app.ActivityThread. */
    private static ClassLoader sysLoader;

    /** Engine mode: light up the dormant MIUI fold orientation engine and
     *  bridge the exclusion list onto its per-package allowlist. Returns
     *  false when the impl class is unavailable (legacy W1-W5 then apply). */
    private static boolean hookEngineMode(XC_LoadPackage.LoadPackageParam lpp) {
        final Class<?> implCls;
        try {
            implCls = XposedHelpers.findClass(
                    "com.android.server.wm.MiuiOrientationImpl", lpp.classLoader);
            sysLoader = lpp.classLoader;
        } catch (Throwable t) {
            log("engine mode unavailable: " + t);
            return false;
        }
        try {
            // diagnostic: which class does MiuiStubUtil actually serve for
            // the stub? A device-specific subclass would dodge the method
            // hook below.
            final Class<?> stubCls = XposedHelpers.findClass(
                    "com.android.server.wm.MiuiOrientationStub", lpp.classLoader);
            XposedBridge.hookAllMethods(stubCls, "get", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    if (param.getResult() != null && !engineDiagLogged) {
                        engineDiagLogged = true;
                        log("E0 diag: stub impl class = "
                                + param.getResult().getClass().getName());
                    }
                }
            });
        } catch (Throwable t) {
            log("E0 diag hook failed: " + t);
        }
        try {
            XposedBridge.hookAllMethods(implCls, "isEnabled", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                    try {
                        if (!engineFirstCallLogged) {
                            engineFirstCallLogged = true;
                            log("E0 first isEnabled invocation");
                        }
                        if (!freeRotOn()) {
                            return; // switch off: engine dormant, stock everywhere
                        }
                        param.setResult(true);
                        Object impl = param.thisObject;
                        boolean dirty;
                        synchronized (ENGINE_SYNC_LOCK) {
                            dirty = !engineSynced || !engineSyncedExclude.equals(cfgExclude);
                        }
                        if (dirty) {
                            scheduleEngineAllowlistSync(impl);
                        }
                    } catch (Throwable t) {
                        log("E0 failed: " + t);
                    }
                }
            });
            log("hook E0 ok: MiuiOrientationImpl.isEnabled (engine mode)");
            return true;
        } catch (Throwable t) {
            log("hook E0 FAILED: " + t);
            return false;
        }
    }

    private static void scheduleEngineAllowlistSync(final Object impl) {
        Handler h = cfgHandler;
        if (h == null) {
            return; // config thread not up yet — the next isEnabled retries
        }
        h.post(new Runnable() {
            @Override public void run() {
                syncEngineAllowlist(impl);
            }
        });
    }

    /** Rebuild the engine's user allowlist = all installed packages minus
     *  user exclusions, camera and the launcher (the launcher keeps its own
     *  rotation hooks; camera apps keep stock via the engine's own camera
     *  strategy). "nr" carries the FULL_LANDSCAPE enable bit; the engine
     *  persists the map to its settings file. Runs off the WMS locks. */
    private static void syncEngineAllowlist(Object impl) {
        synchronized (ENGINE_SYNC_LOCK) {
            if (engineSynced && engineSyncedExclude.equals(cfgExclude)) {
                return;
            }
        }
        try {
            Context ctx = sysCtx;
            PackageManager pm = ctx.getPackageManager();
            ArrayList<String> allow = new ArrayList<String>();
            for (PackageInfo pi : pm.getInstalledPackages(0)) {
                if (pi == null || pi.packageName == null) {
                    continue;
                }
                String pkg = pi.packageName;
                if (pkg.equals("com.miui.home")
                        || CAMERA_PKGS.contains(pkg)
                        || cfgExclude.contains(pkg)) {
                    continue;
                }
                allow.add(pkg);
            }
            java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.StringWriter());
            Object manager = XposedHelpers.callMethod(impl, "getPackageManager");
            XposedHelpers.callMethod(manager, "clearUserSettings");
            XposedHelpers.callMethod(impl, "setUserSettings", pw,
                    allow.toArray(new String[0]), "nr");
            synchronized (ENGINE_SYNC_LOCK) {
                engineSyncedExclude.clear();
                engineSyncedExclude.addAll(cfgExclude);
                engineSynced = true;
            }
            log("engine allowlist synced: " + allow.size() + " packages, excluded "
                    + cfgExclude.size());
        } catch (Throwable t) {
            log("engine allowlist sync failed: " + t);
        }
    }

    /**
     * W1/W2(/W3) — make every registered app follow rotation on the unfolded
     * inner display. Gate order below is part of the spec (cheapest first):
     *  a) requested/current ∉ {UNSPECIFIED(-1), UNSET(-2), BEHIND(3)} — the
     *     first two already follow or are invisible; BEHIND propagates
     *     "inherit the next activity" (WindowContainer aggregate walk) and
     *     must stay coupled to its host, e.g. dialog activities;
     *  b) member of ROT_REG (empty registry bails the whole W2 hot path);
     *  c) not a stock camera package (before any config dependency — holds
     *     even before the settings provider is up);
     *  d) master switch cetus_free_rot on;
     *  e) package not on the user exclusion list cetus_free_rot_exclude
     *     (semicolon-separated; only semantic: apps on the list keep stock
     *     orientation, all others follow);
     *  f) record lives on the unfolded inner display — live
     *     smallestScreenWidthDp >= 600 per evaluation; any failure closes to
     *     "no rewrite". Hard guarantee that the folded cover screen keeps
     *     stock behavior;
     *  g) not embedded/parallel-window, not multi-window, not MIUI
     *     size-compat (isEmbedded / inMultiWindowMode /
     *     inMiuiSizeCompatMode — all three verified in this ROM; a missing
     *     API closes to "no rewrite").
     */
    private static void hookSystemServer(XC_LoadPackage.LoadPackageParam lpp) {
        if (hookEngineMode(lpp)) {
            log("system_server: ENGINE MODE active (W1-W5 superseded, not registered)");
            return;
        }
        // ---- legacy W1-W5 below: fallback for ROMs without the impl class ----
        try {
            actRecCls = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", lpp.classLoader);
            wcCls = XposedHelpers.findClass(
                    "com.android.server.wm.WindowContainer", lpp.classLoader);
        } catch (Throwable t) {
            log("system_server class resolve FAILED: " + t);
            return;
        }
        log("attached to system_server");

        // W1. registration only — no stock field is ever written. The
        // 2-arg override is the only setOrientation declared on
        // ActivityRecord: the constructor's single-arg call funnels here
        // through WindowContainer's virtual dispatch, as does every
        // setRequestedOrientation path that is not intercepted by a MIUI
        // stub (those paths belong to embedded/freeform — excluded by g).
        try {
            XposedHelpers.findAndHookMethod(actRecCls, "setOrientation",
                    int.class, wcCls, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                            try {
                                int req = (Integer) param.args[0];
                                if (req == -1 || req == -2 || req == 3) {
                                    return;
                                }
                                if (XposedHelpers.getBooleanField(
                                        param.thisObject, "firstWindowDrawn")) {
                                    return; // construction-time requests only
                                }
                                String pkg = pkgOf(param.thisObject);
                                if (pkg == null || CAMERA_PKGS.contains(pkg)) {
                                    return;
                                }
                                ROT_REG.put(param.thisObject, req);
                                log("W1 registered: " + pkg);
                                // Neutralize the request itself — the
                                // disabled engine's FULL_LANDSCAPE case-1
                                // does exactly this (info.screenOrientation
                                // = -1 + super(-1)): the starting window
                                // (rendered from info) and the first layout
                                // then already follow rotation — no one-
                                // frame portrait flash. All gates must pass,
                                // otherwise the stock request stands and
                                // W2/W3 keep guarding the read points.
                                if (!freeRotOn()) {
                                    return; // d
                                }
                                if (cfgExclude.contains(pkg)) {
                                    return; // e
                                }
                                if (folded(param.thisObject)) {
                                    return; // f
                                }
                                if (!windowingAllowed(param.thisObject)) {
                                    return; // g
                                }
                                param.args[0] = -1;
                                setInfoOrientation(param.thisObject, -1);
                                logRewrite("W1", pkg);
                            } catch (Throwable t) {
                                log("W1 failed: " + t);
                            }
                        }
                    });
            log("hook W1 ok: ActivityRecord.setOrientation");
        } catch (Throwable t) {
            log("hook W1 FAILED: " + t);
        }

        // W2. the orientation preference read point. This ROM overrides
        // getOrientation(int) on ActivityRecord without calling super
        // (services.jar ~7206) — the WindowContainer base version never
        // fires for activities, hooking it was verified to be zero-trigger.
        // Result rewrite to UNSPECIFIED equals the disabled engine's
        // FULL_LANDSCAPE branch: the display follows auto-rotate and the
        // control-center rotation lock.
        try {
            XposedHelpers.findAndHookMethod(actRecCls, "getOrientation",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                            try {
                                if (ROT_REG.isEmpty() || !ROT_REG.containsKey(param.thisObject)) {
                                    return; // b (fast bail on the hot path)
                                }
                                String pkg = pkgOf(param.thisObject);
                                if (pkg == null) {
                                    return;
                                }
                                // d/e double as the restore gate and MUST run
                                // before predicate (a): a neutralized record
                                // stores -1, which (a) bails on — restore
                                // would be unreachable for exactly the
                                // records that need it (observed: exclusion
                                // and switch-off only applied after the app
                                // was killed from recents)
                                if (!freeRotOn()) {
                                    restoreStockOrientation(param.thisObject); // d
                                    return;
                                }
                                if (cfgExclude.contains(pkg)) {
                                    restoreStockOrientation(param.thisObject); // e
                                    return;
                                }
                                int cur = (Integer) param.getResult();
                                if (cur == -1 || cur == -2 || cur == 3) {
                                    return; // a: already follow / unset / behind
                                }
                                if (CAMERA_PKGS.contains(pkg)) {
                                    return; // c
                                }
                                if (folded(param.thisObject)) {
                                    return; // f
                                }
                                if (!windowingAllowed(param.thisObject)) {
                                    return; // g
                                }
                                param.setResult(-1); // SCREEN_ORIENTATION_UNSPECIFIED
                                logRewrite("W2", pkg);
                            } catch (Throwable t) {
                                // any failure keeps the stock result
                            }
                        }
                    });
            log("hook W2 ok: ActivityRecord.getOrientation");
        } catch (Throwable t) {
            log("hook W2 FAILED: " + t);
        }

        // W3 — stage 2: configuration-orientation read point. W1+W2 passed
        // on-device verification (acceptance 1 & 4 gates hold: rewrites only
        // on the unfolded inner display, sw676dp inner / sw320dp cover).
        hookW3();

        // W4 — the owner-requested per-display lock ("内外屏分别锁定旋转").
        // The ROM's own outer-portrait pair (mUserRotationModeOuter) is never
        // applied on cetus — every update path is gated by the disabled
        // engine — so with global auto-rotate on, freely-rotating apps turn
        // the folded cover landscape (stock behavior, confirmed on device).
        // That breaks the feature's outer-screen guarantee, so while folded
        // the default display's rotation decision is pinned to ROTATION_0.
        // Master-switch governed; the unfolded inner display is untouched.
        try {
            final Class<?> dispRotCls = XposedHelpers.findClass(
                    "com.android.server.wm.DisplayRotation", lpp.classLoader);
            XposedHelpers.findAndHookMethod(dispRotCls, "rotationForOrientation",
                    int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                            try {
                                if (!XposedHelpers.getBooleanField(
                                        param.thisObject, "isDefaultDisplay")) {
                                    return;
                                }
                                if (!freeRotOn()) {
                                    return; // switch off: stock everywhere
                                }
                                Object policy = XposedHelpers.getObjectField(
                                        XposedHelpers.getObjectField(
                                                param.thisObject, "mService"),
                                        "mPolicy");
                                if ((Boolean) XposedHelpers.callMethod(
                                        policy, "isDisplayFolded")) {
                                    int orient = (Integer) param.args[0];
                                    // explicit landscape requests (video
                                    // fullscreen: LANDSCAPE / SENSOR_LANDSCAPE
                                    // / REVERSE_LANDSCAPE) pass through so
                                    // cover-screen video keeps stock rotation;
                                    // everything else pins to portrait
                                    boolean explicitLandscape =
                                            orient == 0 || orient == 6 || orient == 8;
                                    if (!explicitLandscape) {
                                        if (!w4FoldedPinned) {
                                            w4FoldedPinned = true;
                                            log("W4: folded -> default display pinned ROTATION_0"
                                                    + " (orientation=" + orient + ")");
                                        }
                                        param.setResult(0); // ROTATION_0
                                    } else if (w4FoldedPinned) {
                                        w4FoldedPinned = false;
                                        log("W4: folded explicit landscape -> rotation released");
                                    }
                                } else if (w4FoldedPinned) {
                                    w4FoldedPinned = false;
                                    log("W4: unfolded -> rotation released");
                                }
                            } catch (Throwable t) {
                                // any failure keeps the stock decision
                            }
                        }
                    });
            log("hook W4 ok: DisplayRotation.rotationForOrientation (cover lock)");
        } catch (Throwable t) {
            log("hook W4 FAILED: " + t);
        }

        // W5. snapshot starting windows render the task's cached screenshot —
        // a persisted portrait snapshot (apps used on the cover) flashes one
        // portrait frame before the rotation-following window replaces it,
        // regardless of W1's info neutralization (the snapshot content is a
        // bitmap, not an orientation value) and regardless of registration
        // (natively-UNSPECIFIED apps like dialer/clock/calendar never enter
        // the registry). Gate: skip only snapshots whose rotation disagrees
        // with the record's display right now — matched previews stay.
        try {
            // hookAllMethods: the TaskSnapshot param class is not resolvable
            // from the system_server classloader in this ROM (verified:
            // ClassNotFoundError), and hooking by name avoids the coupling
            XposedBridge.hookAllMethods(actRecCls, "isSnapshotCompatible",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                            try {
                                if (!freeRotOn()) {
                                    return; // d
                                }
                                String pkg = pkgOf(param.thisObject);
                                if (pkg == null || CAMERA_PKGS.contains(pkg)
                                        || cfgExclude.contains(pkg)) {
                                    return; // c/e
                                }
                                if (folded(param.thisObject)) {
                                    return; // f
                                }
                                if (!windowingAllowed(param.thisObject)) {
                                    return; // g
                                }
                                // unconditional skip while the feature is on:
                                // the earlier rotation-mismatch variant never
                                // fired (the record has no reliable display
                                // attachment at starting-window decision
                                // time), which silently re-broke settings
                                param.setResult(false);
                            } catch (Throwable t) {
                                // stock decision on any failure
                            }
                        }
                    });
            log("hook W5 ok: isSnapshotCompatible (skip rotation-mismatched snapshots)");
        } catch (Throwable t) {
            log("hook W5 FAILED: " + t);
        }
    }

    /** W3. WindowContainer.getRequestedConfigurationOrientation(boolean)
     *  after: ActivityRecord does not override it (verified), so this is
     *  the dispatched implementation for activity records too. args[0]
     *  true is the display-level variant — never involves an activity.
     *  The stock result maps mOrientation to PORTRAIT/LANDSCAPE only when
     *  a fixed orientation is requested, which is exactly predicate (a)
     *  mapped into configuration space (UNDEFINED results pass through).
     *  Registered members are rewritten to ORIENTATION_UNDEFINED — skipping
     *  this point leaves "display already rotated, window still portrait"
     *  rendering mismatch. */
    private static void hookW3() {
        try {
            XposedHelpers.findAndHookMethod(wcCls, "getRequestedConfigurationOrientation",
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                            try {
                                if (!actRecCls.isInstance(param.thisObject)) {
                                    return;
                                }
                                if (Boolean.TRUE.equals(param.args[0])) {
                                    return; // display-level variant
                                }
                                if (ROT_REG.isEmpty() || !ROT_REG.containsKey(param.thisObject)) {
                                    return; // b
                                }
                                String pkg = pkgOf(param.thisObject);
                                if (pkg == null) {
                                    return;
                                }
                                // d/e before predicate (a) — same restore
                                // reachability reason as W2
                                if (!freeRotOn()) {
                                    restoreStockOrientation(param.thisObject); // d
                                    return;
                                }
                                if (cfgExclude.contains(pkg)) {
                                    restoreStockOrientation(param.thisObject); // e
                                    return;
                                }
                                int cur = (Integer) param.getResult();
                                if (cur != 1 && cur != 2) {
                                    return; // a mapped: ORIENTATION_PORTRAIT/LANDSCAPE only
                                }
                                if (CAMERA_PKGS.contains(pkg)) {
                                    return; // c
                                }
                                if (folded(param.thisObject)) {
                                    return; // f
                                }
                                if (!windowingAllowed(param.thisObject)) {
                                    return; // g
                                }
                                param.setResult(0); // Configuration.ORIENTATION_UNDEFINED
                                logRewrite("W3", pkg);
                            } catch (Throwable t) {
                                // any failure keeps the stock result
                            }
                        }
                    });
            log("hook W3 ok: WindowContainer.getRequestedConfigurationOrientation");
        } catch (Throwable t) {
            log("hook W3 FAILED: " + t);
        }
    }

    /** Package name of an ActivityRecord: mActivityComponent is assigned
     *  during construction, before any setOrientation can run; the
     *  packageName field is the fallback. */
    private static String pkgOf(Object r) {
        try {
            Object cn = XposedHelpers.getObjectField(r, "mActivityComponent");
            if (cn != null) {
                return ((ComponentName) cn).getPackageName();
            }
        } catch (Throwable ignored) {
        }
        try {
            return (String) XposedHelpers.getObjectField(r, "packageName");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Master switch (d) with the lazy-config red line: at
     *  handleLoadPackage("android") the settings provider is not up yet, so
     *  the first read is deferred to the first hook call; a failed read
     *  stays unloaded and is retried on later calls (defaults meanwhile:
     *  feature on, nothing excluded). A ContentObserver keeps both values
     *  live — switch and exclusion list apply without any restart. */
    private static boolean freeRotOn() {
        if (!sysCfgReady) {
            ensureSysCfg();
        }
        return cfgFreeRot;
    }

    private static synchronized void ensureSysCfg() {
        if (sysCfgReady) {
            return;
        }
        Context ctx = sysCtx;
        if (ctx == null) {
            ctx = systemContext();
            if (ctx == null) {
                return; // retry on the next hook call
            }
            sysCtx = ctx;
        }
        try {
            cfgFreeRot = Settings.System.getInt(ctx.getContentResolver(), KEY_FREE_ROT, 1) == 1;
        } catch (Throwable t) {
            return; // provider not up yet — retry later
        }
        cfgExclude = readExclude(ctx);
        try {
            if (cfgThread == null) {
                cfgThread = new HandlerThread("CetusFreeRotCfg");
                cfgThread.start();
                cfgHandler = new Handler(cfgThread.getLooper());
            }
            ContentObserver obs = new ContentObserver(new Handler(cfgThread.getLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    Context c = sysCtx;
                    if (c == null) {
                        return;
                    }
                    try {
                        cfgFreeRot = Settings.System.getInt(
                                c.getContentResolver(), KEY_FREE_ROT, 1) == 1;
                    } catch (Throwable ignored) {
                    }
                    cfgExclude = readExclude(c);
                    log("free-rot config reloaded: on=" + cfgFreeRot
                            + " excluded=" + cfgExclude);
                }
            };
            ctx.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(KEY_FREE_ROT), false, obs);
            ctx.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(KEY_ROT_EXCLUDE), false, obs);
        } catch (Throwable t) {
            log("free-rot observer FAILED: " + t);
        }
        sysCfgReady = true;
        log("free-rot config loaded: on=" + cfgFreeRot + " excluded=" + cfgExclude);
    }

    /** Exclusion list parser: semicolon-separated package names, blank
     *  segments dropped. Only semantic: on the list = keep stock behavior. */
    private static HashSet<String> readExclude(Context ctx) {
        HashSet<String> set = new HashSet<String>();
        try {
            String raw = Settings.System.getString(ctx.getContentResolver(), KEY_ROT_EXCLUDE);
            if (raw != null) {
                for (String s : raw.split(";")) {
                    s = s.trim();
                    if (!s.isEmpty()) {
                        set.add(s);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return set;
    }

    /** The system_server Context whose resolver serves settings. */
    private static Context systemContext() {
        try {
            ClassLoader cl = sysLoader != null
                    ? sysLoader : MainHook.class.getClassLoader();
            Object at = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                    "android.app.ActivityThread", cl),
                    "currentActivityThread");
            if (at == null) {
                return null;
            }
            return (Context) XposedHelpers.callMethod(at, "getSystemContext");
        } catch (Throwable t) {
            log("systemContext failed: " + t);
            return null;
        }
    }

    /** f) live fold check: first the ROM's own global fold state — the very
     *  call the MIUI orientation patch uses
     *  (ActivityRecord.mAtmService.mWindowManager.mPolicy.isDisplayFolded())
     *  — which closes the fold-transition window where a reparenting
     *  record's merged configuration still lags its new display; then the
     *  per-record discriminator smallestScreenWidthDp (cetus: inner 676dp,
     *  cover 320dp, threshold 600 — same as the launcher-side hooks).
     *  Anything that cannot be evaluated — including exceptions — closes to
     *  "folded", so a rewrite can never reach the cover screen. */
    private static boolean folded(Object r) {
        try {
            Object policy = XposedHelpers.getObjectField(
                    XposedHelpers.getObjectField(
                            XposedHelpers.getObjectField(r, "mAtmService"),
                            "mWindowManager"),
                    "mPolicy");
            if ((Boolean) XposedHelpers.callMethod(policy, "isDisplayFolded")) {
                return true;
            }
        } catch (Throwable ignored) {
            // policy unreachable — the per-record check still applies
        }
        try {
            android.content.res.Configuration cfg =
                    (android.content.res.Configuration) XposedHelpers.callMethod(
                            r, "getConfiguration");
            int sw = cfg.smallestScreenWidthDp;
            if (sw > 0) {
                return sw < 600;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** g) multi-window and MIUI size-compat records keep stock orientation —
     *  except MIUI parallel-window (embedded) records, which follow rotation
     *  on the owner's request once the historic crash was re-attributed to a
     *  third-party module's config conflict. APIs verified in this ROM:
     *  ConfigurationContainer#inMultiWindowMode() (inherited),
     *  ActivityRecord#inMiuiSizeCompatMode(), ActivityRecord
     *  #mMiuiEmbeddingEnabled (ctor-final per-package flag, ~340/~1826).
     *  Split-screen, freeform and every other multi-window kind stay stock.
     *  Any failure or missing API closes to "not allowed". */
    private static boolean windowingAllowed(Object r) {
        try {
            if ((Boolean) XposedHelpers.callMethod(r, "inMultiWindowMode")) {
                if (!(Boolean) XposedHelpers.getBooleanField(r, "mMiuiEmbeddingEnabled")) {
                    return false; // split-screen / freeform / pip: stock
                }
            }
            if ((Boolean) XposedHelpers.callMethod(r, "inMiuiSizeCompatMode")) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    /** info.screenOrientation writer (engine case-1 parity). */
    private static void setInfoOrientation(Object r, int value) {
        try {
            Object info = XposedHelpers.getObjectField(r, "info");
            XposedHelpers.setIntField(info, "screenOrientation", value);
        } catch (Throwable ignored) {
        }
    }

    /** Master-switch-off / excluded restore: undo W1's neutralization so
     *  the record returns to the stock manifest orientation it registered
     *  with. Records that were never neutralized (info still carries the
     *  stock value) pass through untouched. */
    private static void restoreStockOrientation(Object r) {
        try {
            Integer orig = ROT_REG.get(r);
            if (orig == null || orig == -1) {
                return;
            }
            Object info = XposedHelpers.getObjectField(r, "info");
            if ((Integer) XposedHelpers.getIntField(info, "screenOrientation") == -1) {
                XposedHelpers.setIntField(info, "screenOrientation", orig);
                // re-issue through the stock path (our W1 hook no-ops for
                // drawn records) so mOrientation and config propagation
                // follow the same route the engine's restore used
                XposedHelpers.callMethod(r, "setOrientation", orig, r);
                log("restored stock orientation for " + pkgOf(r));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void logRewrite(String point, String pkg) {
        synchronized (REWRITE_LOGGED) {
            Integer n = REWRITE_LOGGED.get(pkg);
            if (n != null && n >= 3) {
                return;
            }
            REWRITE_LOGGED.put(pkg, n == null ? 1 : n + 1);
        }
        log(point + ": " + pkg + " -> follow system");
    }

    /** Append empty pages until the count is a multiple of the snap unit.
     *  Enforced for portrait two-up only; landscape three-page mode pages
     *  freely (no parity lock), per design decision. */
    private static void ensureEvenPages(Object workspace) {
        try {
            Context app = appContext();
            if (app == null || !onInnerScreen(app)) {
                return;
            }
            loadCfg(app);
            if (isLandscape(app) && cfgLandPages == 3) {
                return; // three-page mode: free paging, no parity lock
            }
            if ((boolean) XposedHelpers.callMethod(workspace, "isInNormalEditingMode")) {
                return;
            }
            int modulo = 2;
            int count = (int) XposedHelpers.callMethod(workspace, "getScreenCount");
            int deficit = (modulo - count % modulo) % modulo;
            for (int i = 0; i < deficit; i++) {
                XposedHelpers.callMethod(workspace, "insertNewScreen", count + i);
            }
            if (deficit > 0) {
                log("even-pages: appended " + deficit + ", count " + count
                        + " -> " + (count + deficit));
            }
        } catch (Throwable t) {
            log("ensureEvenPages failed: " + t);
        }
    }

}
