package cetushome.hook;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
 * Settings are read ONCE per process into a static cache and refreshed by
 * restarting the process (launcher from the module UI; SystemUI via
 * `pkill -f com.android.systemui`) — hook bodies must stay cheap because
 * getCellCountX sits on hot layout paths.
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
