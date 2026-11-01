package cetushome.hook;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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

/**
 * Main control panel for the MixUnfold LSPosed module.
 *
 * Single ScrollView page: the rotation master switch, the exclusion-list
 * entry (the app picker itself lives in AppPickerActivity — the inline list
 * made the main page unbearably long), and the legacy home-layout controls
 * with the launcher restart. Every change is written to Settings.System
 * immediately (debounced) through the shared persistent su shell — the
 * rotation switch applies live via the system_server ContentObserver, while
 * the launcher layout keys still need "重启系统桌面".
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
    private Button excludeEntry, grantBtn;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pendingWrite;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int[] pal = ThemeUtil.palette(this);
        cPrimary = pal[0];
        cSecondary = pal[1];
        cAccent = pal[2];

        cols = read(KEY_COLUMNS, 3);
        landPages = read(KEY_LAND_PAGES, 3);
        padOpt = read(KEY_PAD_OPT, 0) == 1;
        lockRot = read(KEY_LOCK_ROT, 1) == 1;
        freeRot = read(KEY_FREE_ROT, 1) == 1;

        setContentView(buildPage());
        updateExcludeCount();
    }

    private void refreshGrantUi() {
        if (grantBtn == null) {
            return;
        }
        boolean ok = Settings.System.canWrite(this);
        grantBtn.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (!ok) {
            grantBtn.setText("① 点此授予「修改系统设置」权限（一次性，之后改动自动保存）");
        }
    }

    private void launchWriteSettingsPage() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // the picker may have changed the exclusion key; the grant banner
        // may need to disappear after the user flipped the app-op
        refreshGrantUi();
        updateExcludeCount();
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

        grantBtn = new Button(this);
        grantBtn.setAllCaps(false);
        grantBtn.setTextSize(14);
        grantBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                launchWriteSettingsPage();
            }
        });
        h.addView(grantBtn);
        refreshGrantUi();

        h.addView(makeGap());

        // ---- 内屏自由旋转 ------------------------------------------------
        TextView rotName = new TextView(this);
        rotName.setText("内屏自由旋转");
        rotName.setTextColor(cPrimary);
        rotName.setTextSize(15);
        h.addView(rotName);

        final Switch rotSwitch = new Switch(this);
        rotSwitch.setText("总开关");
        rotSwitch.setTextSize(15);
        rotSwitch.setTextColor(cPrimary);
        rotSwitch.setChecked(freeRot);
        rotSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
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

        Spinner pagesSpinner = new Spinner(this);
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

        final Switch optSwitch = new Switch(this);
        optSwitch.setText("屏幕外侧边距优化");
        optSwitch.setTextSize(15);
        optSwitch.setTextColor(cPrimary);
        optSwitch.setChecked(padOpt);
        optSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
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

        final Switch lockSwitch = new Switch(this);
        lockSwitch.setText("锁屏允许横屏");
        lockSwitch.setTextSize(15);
        lockSwitch.setTextColor(cPrimary);
        lockSwitch.setChecked(lockRot);
        lockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
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
        status.setText("旋转开关与排除清单改动即时生效（首次改动会请求 root 授权）。\n"
                + "桌面布局项改动后需点「重启系统桌面」。");
        h.addView(status);

        return scroll;
    }

    // ---- main-page keys persistence -----------------------------------------

    private void updateExcludeCount() {
        int n = 0;
        String raw = readString(MainHook.KEY_ROT_EXCLUDE);
        if (raw != null) {
            for (String s : raw.split(";")) {
                if (!s.trim().isEmpty()) {
                    n++;
                }
            }
        }
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
                                status.setText("写入失败 — 请授予「修改系统设置」权限或检查 root 授权");
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void restartLauncher() {
        status.setText("正在重启系统桌面…");
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                String err = null;
                try {
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
        return Settings.System.getInt(getContentResolver(), key, def);
    }

    private String readString(String key) {
        try {
            return Settings.System.getString(getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
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
