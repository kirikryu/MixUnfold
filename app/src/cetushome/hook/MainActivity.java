package cetushome.hook;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ScrollView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Main control panel for the MixUnfold LSPosed module.
 *
 * Single ScrollView page: the rotation master switch, the exclusion-list
 * entry (the app picker itself lives in AppPickerActivity — the inline list
 * made the main page unbearably long), and the legacy home-layout controls
 * with the launcher restart. Every change is written to the module's private
 * SharedPreferences immediately (debounced) — no system settings permission,
 * no shell. The rotation switch and exclusion list reach system_server within
 * 500 ms; the launcher layout keys still need "重启系统桌面".
 */
public class MainActivity extends Activity {

    private static final String KEY_COLUMNS = MainHook.KEY_COLUMNS;
    private static final String KEY_PAD_PORT = MainHook.KEY_PAD_PORT;
    private static final String KEY_PAD_LAND = MainHook.KEY_PAD_LAND;
    private static final String KEY_LAND_PAGES = MainHook.KEY_LAND_PAGES;
    private static final String KEY_PAD_OPT = MainHook.KEY_PAD_OPT;
    private static final String KEY_LOCK_ROT = MainHook.KEY_LOCK_ROT;
    private static final String KEY_FREE_ROT = MainHook.KEY_FREE_ROT;

    private int cols = 3, landPages = 3;
    private boolean padOpt = false;
    private boolean lockRot = true;
    private boolean freeRot = true;

    private int cPrimary, cSecondary, cAccent;

    private TextView colsVal, status;
    private Button excludeEntry;
    // promoted from buildPage locals: an imported backup repaints these
    private Switch rotSwitch, optSwitch, lockSwitch;
    private Spinner pagesSpinner;
    private SeekBar colsBar;
    /** Guard: refreshUiFromPrefs() repaints widgets without scheduleWrite(). */
    private boolean refreshing;

    private static final int REQ_EXPORT = 1001;
    private static final int REQ_IMPORT = 1002;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pendingWrite;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int[] pal = ThemeUtil.palette(this);
        cPrimary = pal[0];
        cSecondary = pal[1];
        cAccent = pal[2];

        // One-time snapshot of the 1.8.x Settings.System keys on an in-place
        // upgrade; a fresh install/reinstall gets defaults. Must run before
        // the first read below.
        MixPrefs.ensureMigrated(this);

        cols = read(KEY_COLUMNS, 3);
        landPages = read(KEY_LAND_PAGES, 3);
        padOpt = read(KEY_PAD_OPT, 0) == 1;
        lockRot = read(KEY_LOCK_ROT, 1) == 1;
        freeRot = read(KEY_FREE_ROT, 1) == 1;

        setContentView(buildPage());
        updateExcludeCount();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // the picker may have changed the exclusion key
        updateExcludeCount();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return; // user cancelled the picker
        }
        final Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            exportTo(uri);
        } else if (requestCode == REQ_IMPORT) {
            importFrom(uri);
        }
    }

    private View buildPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        h.setPadding(pad, dp(24), pad, dp(24));
        scroll.addView(h);

        TextView title = new TextView(this);
        title.setText("MixUnfold");
        title.setTextSize(24);
        title.setTextColor(cPrimary);
        h.addView(title);

        TextView sub = new TextView(this);
        sub.setText("MIX FOLD 1 桌面增强 · LSPosed 模块");
        sub.setTextSize(13);
        sub.setTextColor(cSecondary);
        h.addView(sub);

        h.addView(makeGap());

        // ---- 内屏自由旋转 ------------------------------------------------
        TextView rotName = new TextView(this);
        rotName.setText("内屏自由旋转");
        rotName.setTextColor(cPrimary);
        rotName.setTextSize(15);
        h.addView(rotName);

        rotSwitch = new Switch(this);
        rotSwitch.setText("总开关");
        rotSwitch.setTextSize(15);
        rotSwitch.setTextColor(cPrimary);
        rotSwitch.setChecked(freeRot);
        rotSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (refreshing) {
                    return;
                }
                freeRot = checked;
                scheduleWrite();
            }
        });
        h.addView(rotSwitch);

        TextView rotDesc = new TextView(this);
        rotDesc.setTextSize(13);
        rotDesc.setTextColor(cSecondary);
        rotDesc.setPadding(dp(8), dp(2), 0, 0);
        rotDesc.setText("开启：内屏展开时所有应用跟随系统自动旋转"
                + "（含原生锁定方向的应用），变更即时生效，无需重启；"
                + "合盖后外屏强制竖屏。\n"
                + "相机类应用始终不参与；排除清单中的应用保持原厂方向。\n"
                + "关闭后全机恢复原厂方向行为。");
        h.addView(rotDesc);

        h.addView(makeGap());

        // ---- 排除清单入口 ------------------------------------------------
        excludeEntry = new Button(this);
        excludeEntry.setAllCaps(false);
        excludeEntry.setTextSize(15);
        excludeEntry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, AppPickerActivity.class));
            }
        });
        h.addView(excludeEntry);

        TextView exNote = new TextView(this);
        exNote.setTextSize(13);
        exNote.setTextColor(cSecondary);
        exNote.setPadding(dp(8), 0, 0, 0);
        exNote.setText("勾选 = 该应用不旋转（保持原厂方向），带搜索，即时生效。");
        h.addView(exNote);

        h.addView(makeGap());

        // ---- 桌面布局（原有功能，改动需重启桌面）--------------------------
        TextView homeName = new TextView(this);
        homeName.setText("桌面布局（原有功能）");
        homeName.setTextColor(cPrimary);
        homeName.setTextSize(15);
        h.addView(homeName);

        colsVal = addSeekBar(h, "桌面单页布局列数", cols, 2, 6, new OnChange() {
            @Override public void on(int v) {
                cols = v;
                scheduleWrite();
            }
        });

        TextView pagesName = new TextView(this);
        pagesName.setText("横屏页面组配置");
        pagesName.setTextColor(cPrimary);
        pagesName.setTextSize(15);
        h.addView(pagesName);

        pagesSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"两页", "三页"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pagesSpinner.setAdapter(adapter);
        pagesSpinner.setSelection(landPages == 3 ? 1 : 0);
        pagesSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (landPages != pos + 2) {
                    landPages = pos + 2;
                    scheduleWrite();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        h.addView(pagesSpinner);

        TextView pagesNote = new TextView(this);
        pagesNote.setTextSize(13);
        pagesNote.setTextColor(cSecondary);
        pagesNote.setPadding(dp(8), dp(2), 0, 0);
        pagesNote.setText("桌面不足 5 页时选三页，末组可能不满——预期行为。");
        h.addView(pagesNote);

        optSwitch = new Switch(this);
        optSwitch.setText("屏幕外侧边距优化");
        optSwitch.setTextSize(15);
        optSwitch.setTextColor(cPrimary);
        optSwitch.setChecked(padOpt);
        optSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (refreshing) {
                    return;
                }
                padOpt = checked;
                scheduleWrite();
            }
        });
        h.addView(optSwitch);

        TextView optDesc = new TextView(this);
        optDesc.setTextSize(13);
        optDesc.setTextColor(cSecondary);
        optDesc.setPadding(dp(8), dp(2), 0, 0);
        optDesc.setText("开启：竖屏外侧边距固定 120px；横屏按页面组适配——两页 280px、"
                + "三页 0px（三页铺满、页间无缝）。\n"
                + "关闭：采用小米原版边距方案（原生边距与页缝保留）。");
        h.addView(optDesc);

        lockSwitch = new Switch(this);
        lockSwitch.setText("锁屏允许横屏");
        lockSwitch.setTextSize(15);
        lockSwitch.setTextColor(cPrimary);
        lockSwitch.setChecked(lockRot);
        lockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (refreshing) {
                    return;
                }
                lockRot = checked;
                scheduleWrite();
            }
        });
        h.addView(lockSwitch);

        TextView lockDesc = new TextView(this);
        lockDesc.setTextSize(13);
        lockDesc.setTextColor(cSecondary);
        lockDesc.setPadding(dp(8), dp(2), 0, 0);
        lockDesc.setText("开启：内屏展开态的锁屏跟随系统自动旋转。\n"
                + "小米未适配 cetus 横屏锁屏，横屏锁屏可能出现布局异常；"
                + "关闭并重启系统界面即恢复原版。\n"
                + "折叠态外屏锁屏始终为竖屏，不受此开关影响。");
        h.addView(lockDesc);

        h.addView(makeGap());

        // ---- 备份与恢复 ------------------------------------------------------
        TextView backupName = new TextView(this);
        backupName.setText("备份与恢复");
        backupName.setTextColor(cPrimary);
        backupName.setTextSize(15);
        h.addView(backupName);

        Button exportBtn = makeButton("导出配置");
        exportBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startExport();
            }
        });
        h.addView(exportBtn);

        Button importBtn = makeButton("导入配置");
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startImport();
            }
        });
        h.addView(importBtn);

        TextView backupDesc = new TextView(this);
        backupDesc.setTextSize(13);
        backupDesc.setTextColor(cSecondary);
        backupDesc.setPadding(dp(8), dp(2), 0, 0);
        backupDesc.setText("导出全部配置为文本备份文件，用于卸载重装或换机前保存；"
                + "导入只覆盖备份中出现的项。旋转与排除清单导入后 ≤0.5 秒生效，"
                + "桌面布局项需点「重启系统桌面」。");
        h.addView(backupDesc);

        h.addView(makeGap());

        Button restart = makeButton("重启系统桌面");
        restart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                restartLauncher();
            }
        });
        h.addView(restart);

        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(cSecondary);
        status.setPadding(0, dp(16), 0, 0);
        status.setText("旋转开关与排除清单改动 ≤0.5 秒内生效。\n"
                + "桌面布局项改动后需点「重启系统桌面」。");
        h.addView(status);

        return scroll;
    }

    // ---- main-page keys persistence -----------------------------------------

    private void updateExcludeCount() {
        // parse through the same whitelist the hook uses, so the count never
        // overstates the effective list
        int n = ExcludeCodec.parse(readString(MainHook.KEY_ROT_EXCLUDE)).size();
        if (excludeEntry != null) {
            excludeEntry.setText("排除应用（已排除 " + n + " 个）▸");
        }
    }

    private void scheduleWrite() {
        if (pendingWrite != null) {
            ui.removeCallbacks(pendingWrite);
        }
        pendingWrite = new Runnable() {
            @Override public void run() {
                pendingWrite = null;
                writeAllAsync();
            }
        };
        ui.postDelayed(pendingWrite, 400);
    }

    private void writeAllAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                int padLand = padOpt ? (landPages == 3 ? 0 : 280) : 120;
                // the exclusion key is owned by AppPickerActivity, never here
                boolean ok = SettingsWriter.put(MainActivity.this, KEY_COLUMNS, cols)
                        & SettingsWriter.put(MainActivity.this, KEY_PAD_OPT, padOpt ? 1 : 0)
                        & SettingsWriter.put(MainActivity.this, KEY_PAD_PORT, 120)
                        & SettingsWriter.put(MainActivity.this, KEY_PAD_LAND, padLand)
                        & SettingsWriter.put(MainActivity.this, KEY_LAND_PAGES, landPages)
                        & SettingsWriter.put(MainActivity.this, KEY_LOCK_ROT, lockRot ? 1 : 0)
                        & SettingsWriter.put(MainActivity.this, KEY_FREE_ROT, freeRot ? 1 : 0);
                if (!ok) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (status != null) {
                                status.setText("保存失败 — 请检查应用存储空间");
                            }
                        }
                    });
                }
            }
        }).start();
    }

    // ---- backup: SAF export / import ------------------------------------------
    //
    // The backup file is untrusted input: BackupCodec whitelists keys, type-
    // and range-checks ints, and the exclude value is re-filtered by
    // ExcludeCodec inside SettingsWriter. Import writes only keys present in
    // the file (present wins — never replaces a stored value with a default).

    private void startExport() {
        String date = new java.text.SimpleDateFormat("yyyyMMdd",
                java.util.Locale.US).format(new java.util.Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE,
                "MixUnfold-backup-" + selfVersion() + "-" + date + ".txt");
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Throwable t) {
            // no documents provider on this device
            status.setText("无法打开文件选择器：" + t);
        }
    }

    private void startImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*"); // some providers mislabel .txt; the content validates
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            status.setText("无法打开文件选择器：" + t);
        }
    }

    private void exportTo(final Uri uri) {
        new Thread(new Runnable() {
            @Override public void run() {
                final String err = doExport(uri);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (status != null) {
                            status.setText(err == null
                                    ? "已导出配置备份。"
                                    : "导出失败：" + err);
                        }
                    }
                });
            }
        }).start();
    }

    private String doExport(Uri uri) {
        try {
            Map<String, String> values = new LinkedHashMap<String, String>();
            for (String key : PrefsKeys.LEGACY_KEYS) {
                if (PrefsKeys.KEY_ROT_EXCLUDE.equals(key)) {
                    values.put(key, MixPrefs.getString(this, key,
                            PrefsKeys.DEF_ROT_EXCLUDE));
                } else {
                    values.put(key, String.valueOf(
                            MixPrefs.getInt(this, key, PrefsKeys.defaultInt(key))));
                }
            }
            String text = BackupCodec.encode(PrefsKeys.MODULE_PKG, selfVersion(),
                    System.currentTimeMillis(), values);
            java.io.OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) {
                return "无法打开目标文件";
            }
            try {
                os.write(text.getBytes("UTF-8"));
                os.flush();
            } finally {
                os.close();
            }
            return null;
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    private void importFrom(final Uri uri) {
        new Thread(new Runnable() {
            @Override public void run() {
                final ImportResult r = doImport(uri);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (status == null) {
                            return;
                        }
                        if (r.error != null) {
                            status.setText("导入失败：" + r.error);
                            Toast.makeText(MainActivity.this,
                                    "导入失败：" + r.error, Toast.LENGTH_LONG).show();
                            return;
                        }
                        // a pending debounced write would overwrite the just-
                        // imported values with the pre-import UI state
                        if (pendingWrite != null) {
                            ui.removeCallbacks(pendingWrite);
                            pendingWrite = null;
                        }
                        refreshUiFromPrefs();
                        int skipped = r.skippedUnknown + r.skippedInvalid;
                        status.setText("已导入 " + r.applied + " 项"
                                + (skipped > 0
                                        ? "（跳过未知 " + r.skippedUnknown + " 项、"
                                        + "非法 " + r.skippedInvalid + " 项）"
                                        : "")
                                + "。旋转与排除清单 ≤0.5 秒生效；"
                                + "桌面布局项需重启系统桌面。");
                        Toast.makeText(MainActivity.this,
                                "已导入 " + r.applied + " 项",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private static final class ImportResult {
        String error;
        int applied;
        int skippedUnknown;
        int skippedInvalid;
    }

    private ImportResult doImport(Uri uri) {
        ImportResult r = new ImportResult();
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                r.error = "无法打开所选文件";
                return r;
            }
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            try {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > BackupCodec.MAX_CHARS) {
                        r.error = BackupCodec.REJECT_TOO_LARGE;
                        return r;
                    }
                }
            } finally {
                in.close();
            }
            BackupCodec.Parsed p = BackupCodec.parse(
                    new String(buf.toByteArray(), "UTF-8"));
            if (!p.ok()) {
                r.error = p.rejectReason;
                return r;
            }
            r.skippedUnknown = p.skippedUnknown;
            r.skippedInvalid = p.skippedInvalid;
            for (Map.Entry<String, String> e : p.values.entrySet()) {
                boolean ok;
                if (PrefsKeys.KEY_ROT_EXCLUDE.equals(e.getKey())) {
                    ok = SettingsWriter.put(this, e.getKey(), e.getValue());
                } else {
                    ok = SettingsWriter.put(this, e.getKey(),
                            Integer.parseInt(e.getValue()));
                }
                if (!ok) {
                    r.error = "写入存储失败 — 请检查应用存储空间";
                    return r;
                }
                r.applied++;
            }
            return r;
        } catch (Throwable t) {
            r.error = String.valueOf(t);
            return r;
        }
    }

    /** Repaint every control from the (possibly just-imported) stored values.
     *  The refreshing guard keeps listeners from scheduling a write; the
     *  spinner's own equality check covers its async callback. */
    private void refreshUiFromPrefs() {
        cols = read(KEY_COLUMNS, 3);
        landPages = read(KEY_LAND_PAGES, 3);
        padOpt = read(KEY_PAD_OPT, 0) == 1;
        lockRot = read(KEY_LOCK_ROT, 1) == 1;
        freeRot = read(KEY_FREE_ROT, 1) == 1;
        refreshing = true;
        try {
            if (colsBar != null) {
                colsBar.setProgress(cols - 2);
            }
            if (colsVal != null) {
                colsVal.setText(String.valueOf(cols));
            }
            if (pagesSpinner != null) {
                pagesSpinner.setSelection(landPages == 3 ? 1 : 0);
            }
            if (rotSwitch != null) {
                rotSwitch.setChecked(freeRot);
            }
            if (optSwitch != null) {
                optSwitch.setChecked(padOpt);
            }
            if (lockSwitch != null) {
                lockSwitch.setChecked(lockRot);
            }
            updateExcludeCount();
        } finally {
            refreshing = false;
        }
    }

    private String selfVersion() {
        try {
            String v = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "unknown" : v;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private void restartLauncher() {
        status.setText("正在重启系统桌面…");
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                String err = null;
                try {
                    // The only remaining shell call in the module: fixed
                    // commands, no config/user data, user-initiated. Config
                    // writes themselves never touch su (private prefs only).
                    //
                    // Clean package-level stop + explicit relaunch. A raw
                    // Process.killProcess() inside the launcher leaves AMS
                    // state stale (skips fold/orientation callbacks) and
                    // breaks rotation until a full reboot — force-stop is
                    // the path that always initializes correctly.
                    Process p = Runtime.getRuntime().exec("su");
                    java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
                    os.writeBytes("am force-stop com.miui.home\n");
                    os.flush();
                    // User-identical path: force-stop (same as app-info) then
                    // a real HOME key event — the system resolves the default
                    // home itself and reattaches to the native home task.
                    // Never use `am start -n` here: it creates a task outside
                    // the home role, which shows up as a stray 系统桌面 card
                    // in recents.
                    os.writeBytes("sleep 0.3\n");
                    os.flush();
                    os.writeBytes("input keyevent KEYCODE_HOME\n");
                    os.flush();
                    os.writeBytes("exit\n");
                    os.flush();
                    ok = p.waitFor() == 0;
                } catch (Throwable t) {
                    err = String.valueOf(t);
                }
                final boolean okF = ok;
                final String errF = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (okF) {
                            Toast.makeText(MainActivity.this, "系统桌面已重启",
                                    Toast.LENGTH_SHORT).show();
                            status.setText("已干净重启（等价于重启手机后的桌面初始化）。");
                        } else {
                            status.setText("重启失败（root 未授权？）" + (errF == null ? "" : "：" + errF));
                        }
                    }
                });
            }
        }).start();
    }

    // ---- settings reads & small builders --------------------------------------

    private int read(String key, int def) {
        return MixPrefs.getInt(this, key, def);
    }

    private String readString(String key) {
        return MixPrefs.getString(this, key, null);
    }

    private interface OnChange {
        void on(int v);
    }

    private TextView addSeekBar(LinearLayout root, String label, int value, int min, int max, OnChange cb) {
        TextView name = new TextView(this);
        name.setTextColor(cPrimary);
        name.setTextSize(15);
        root.addView(name);

        final TextView val = new TextView(this);
        val.setTextColor(cAccent);
        val.setTextSize(14);

        SeekBar bar = new SeekBar(this);
        colsBar = bar; // single caller: the columns slider needs import repaint
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                val.setText(String.valueOf(p + min));
                cb.on(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(bar);
        val.setGravity(Gravity.END);
        root.addView(val);
        name.setText(label);
        val.setText(String.valueOf(value));
        return val;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private View makeGap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
