package cetushome.hook;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Control panel for the MixUnfold LSPosed module.
 * Column count is a slider; landscape page count is a two-option spinner;
 * outer margins are governed by the "外侧边距优化" constraint switch —
 * users never enter pixel values by hand. All values are written via root
 * and take effect after "重启系统桌面".
 *
 * UI text colors are never hardcoded: they resolve from the activity theme
 * (textColorPrimary / textColorSecondary / colorAccent), then get a WCAG AA
 * contrast check (>= 4.5:1) against the resolved window background, so the
 * panel stays readable in both light and dark system themes.
 */
public class MainActivity extends Activity {

    private static final String KEY_COLUMNS = MainHook.KEY_COLUMNS;
    private static final String KEY_PAD_PORT = MainHook.KEY_PAD_PORT;
    private static final String KEY_PAD_LAND = MainHook.KEY_PAD_LAND;
    private static final String KEY_LAND_PAGES = MainHook.KEY_LAND_PAGES;
    private static final String KEY_PAD_OPT = MainHook.KEY_PAD_OPT;

    private int cols = 3, padPort = 120, padLand = 120, landPages = 3;
    private boolean padOpt = false;

    /** Theme-resolved text colors, contrast-checked against the window bg. */
    private int cPrimary, cSecondary, cAccent;

    private TextView colsVal, marginInfo, status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int bg = backgroundProxy();
        cPrimary = ensureContrast(themeColor(android.R.attr.textColorPrimary,
                0xFF1A1A1A), bg, 4.5);
        cSecondary = ensureContrast(themeColor(android.R.attr.textColorSecondary,
                0xFF616161), bg, 4.5);
        cAccent = ensureContrast(themeColor(android.R.attr.colorAccent,
                0xFF1976D2), bg, 4.5);

        cols = read(KEY_COLUMNS, 3);
        padPort = read(KEY_PAD_PORT, 120);
        padLand = read(KEY_PAD_LAND, 120);
        landPages = read(KEY_LAND_PAGES, 3);
        padOpt = read(KEY_PAD_OPT, 0) == 1;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(28), pad, pad);

        TextView title = new TextView(this);
        title.setText("MixUnfold");
        title.setTextSize(24);
        title.setTextColor(cPrimary);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("MIX FOLD 1 桌面增强 · LSPosed 模块");
        sub.setTextSize(13);
        sub.setTextColor(cSecondary);
        root.addView(sub);

        root.addView(makeGap());

        colsVal = addSeekBar(root, "桌面单页布局列数", cols, 2, 6, new OnChange() {
            @Override public void on(int v) { cols = v; }
        });

        TextView pagesName = new TextView(this);
        pagesName.setText("横屏页面组配置");
        pagesName.setTextColor(cPrimary);
        pagesName.setTextSize(15);
        root.addView(pagesName);

        Spinner pagesSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"两页", "三页"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pagesSpinner.setAdapter(adapter);
        pagesSpinner.setSelection(landPages == 3 ? 1 : 0);
        pagesSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                landPages = pos + 2;
                updateMarginInfo();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(pagesSpinner);

        TextView pagesNote = new TextView(this);
        pagesNote.setTextSize(13);
        pagesNote.setTextColor(cSecondary);
        pagesNote.setPadding(dp(8), dp(2), 0, 0);
        pagesNote.setText("桌面不足 5 页时选三页，末组可能不满——预期行为。");
        root.addView(pagesNote);

        root.addView(makeGap());

        final Switch optSwitch = new Switch(this);
        optSwitch.setText("屏幕外侧边距优化");
        optSwitch.setTextSize(15);
        optSwitch.setTextColor(cPrimary);
        optSwitch.setChecked(padOpt);
        optSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                padOpt = checked;
                updateMarginInfo();
            }
        });
        root.addView(optSwitch);

        TextView optDesc = new TextView(this);
        optDesc.setTextSize(13);
        optDesc.setTextColor(cSecondary);
        optDesc.setPadding(dp(8), dp(2), 0, 0);
        optDesc.setText("开启：竖屏外侧边距固定 120px；横屏按页面组适配——两页 280px、三页 0px（三页铺满、页间无缝）。\n"
                + "关闭：采用小米原版边距方案（原生边距与页缝保留）。");
        root.addView(optDesc);

        marginInfo = new TextView(this);
        marginInfo.setTextSize(14);
        marginInfo.setTextColor(cAccent);
        marginInfo.setPadding(dp(8), dp(4), 0, 0);
        root.addView(marginInfo);

        root.addView(makeGap());

        Button apply = makeButton("应用设置（root 写入）");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { apply(); }
        });
        root.addView(apply);

        Button restart = makeButton("重启系统桌面");
        restart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { restartLauncher(); }
        });
        root.addView(restart);

        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(cSecondary);
        status.setPadding(0, dp(16), 0, 0);
        status.setText("改完数值点“应用设置”，再点“重启系统桌面”生效。\n"
                + "重启桌面后，建议横竖屏切换几次，让优化完全应用。\n"
                + "首次应用会弹出 root 授权，请选择允许。");
        root.addView(status);

        updateMarginInfo();
        setContentView(root);
    }

    /** Effective margins under the constraint scheme; users never enter px. */
    private int effectivePadPort() {
        return padOpt ? 120 : 120; // constrained scheme locks portrait at 120
    }

    private int effectivePadLand() {
        if (padOpt) {
            return landPages == 3 ? 0 : 280;
        }
        return 120;
    }

    private void updateMarginInfo() {
        if (marginInfo == null) {
            return;
        }
        if (padOpt) {
            marginInfo.setText("竖屏外侧边距：120px（固定）\n横屏外侧边距："
                    + effectivePadLand() + "px（" + landPages + " 页自动）");
        } else {
            marginInfo.setText("优化未开启：竖屏 120px · 横屏 120px（默认）");
        }
    }

    private int read(String key, int def) {
        return Settings.System.getInt(getContentResolver(), key, def);
    }

    // --- theme-adaptive text colors (no hardcoded palette) ----------------------

    /** Resolve a theme color attribute to a concrete ARGB value, or {@code def}. */
    private int themeColor(int attr, int def) {
        TypedValue tv = new TypedValue();
        if (!getTheme().resolveAttribute(attr, tv, true)) {
            return def;
        }
        if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data;
        }
        if (tv.resourceId != 0) {
            try {
                // textColorPrimary/Secondary are usually color state lists;
                // the default state is what plain text renders with.
                ColorStateList csl =
                        getResources().getColorStateList(tv.resourceId, getTheme());
                return csl.getDefaultColor();
            } catch (Resources.NotFoundException e) {
                // attribute points at a drawable or other non-color resource
            }
        }
        return def;
    }

    /**
     * Best-effort window background color. When the theme paints the window
     * with a drawable instead of a flat color, invert the primary-text
     * lightness: the platform guarantees primary text contrasts with the
     * background, so dark text implies a light window and vice versa.
     */
    private int backgroundProxy() {
        int bg = themeColor(android.R.attr.windowBackground, 0);
        if (bg != 0) {
            return bg;
        }
        int primary = themeColor(android.R.attr.textColorPrimary, Color.BLACK);
        return luminance(primary) > 0.5 ? Color.BLACK : Color.WHITE;
    }

    /** Composite fg over bg — theme secondary colors carry alpha. */
    private static int flatten(int fg, int bg) {
        int a = Color.alpha(fg);
        if (a == 255) {
            return fg;
        }
        int r = (Color.red(fg) * a + Color.red(bg) * (255 - a)) / 255;
        int g = (Color.green(fg) * a + Color.green(bg) * (255 - a)) / 255;
        int b = (Color.blue(fg) * a + Color.blue(bg) * (255 - a)) / 255;
        return Color.rgb(r, g, b);
    }

    /** WCAG 2.x relative luminance. */
    private static double luminance(int c) {
        double r = Color.red(c) / 255.0;
        double g = Color.green(c) / 255.0;
        double b = Color.blue(c) / 255.0;
        r = r <= 0.03928 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.03928 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.03928 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** WCAG contrast ratio between two ARGB colors (1.0 – 21.0). */
    private static double contrastRatio(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /**
     * Nudge a foreground color until it reaches {@code minRatio} against the
     * background, keeping its hue. Lowering HSV value scales luminance down
     * monotonically, so the light-background branch always converges; the
     * dark branch raises value, then drains saturation toward white (blues
     * at full value still cannot reach AA on black). Returns black/white if
     * the hue is hopeless.
     */
    private static int ensureContrast(int fg, int bg, double minRatio) {
        fg = flatten(fg, bg);
        if (contrastRatio(fg, bg) >= minRatio) {
            return fg;
        }
        float[] hsv = new float[3];
        Color.colorToHSV(fg, hsv);
        if (luminance(bg) >= luminance(fg)) {
            while (hsv[2] > 0f && contrastRatio(fg, bg) < minRatio) {
                hsv[2] = Math.max(0f, hsv[2] - 0.02f);
                fg = Color.HSVToColor(hsv);
            }
            return contrastRatio(fg, bg) >= minRatio ? fg : Color.BLACK;
        }
        while (hsv[2] < 1f && contrastRatio(fg, bg) < minRatio) {
            hsv[2] = Math.min(1f, hsv[2] + 0.02f);
            fg = Color.HSVToColor(hsv);
        }
        while (hsv[1] > 0f && contrastRatio(fg, bg) < minRatio) {
            hsv[1] = Math.max(0f, hsv[1] - 0.02f);
            fg = Color.HSVToColor(hsv);
        }
        return contrastRatio(fg, bg) >= minRatio ? fg : Color.WHITE;
    }

    private void apply() {
        final int port = effectivePadPort();
        final int land = effectivePadLand();
        status.setText("正在通过 root 写入…");
        final String cmd = "settings put system " + KEY_COLUMNS + " " + cols
                + " && settings put system " + KEY_PAD_OPT + " " + (padOpt ? 1 : 0)
                + " && settings put system " + KEY_PAD_PORT + " " + port
                + " && settings put system " + KEY_PAD_LAND + " " + land
                + " && settings put system " + KEY_LAND_PAGES + " " + landPages;
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                String err = null;
                try {
                    Process p = Runtime.getRuntime().exec("su");
                    java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
                    os.writeBytes(cmd + "\n");
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
                            Toast.makeText(MainActivity.this, "已保存，重启桌面后生效",
                                    Toast.LENGTH_SHORT).show();
                            status.setText("已保存：桌面 " + cols + " 列；横屏 " + landPages
                                    + " 页；边距竖 " + port + "px / 横 " + land + "px。");
                        } else {
                            status.setText("写入失败（root 未授权？）" + (errF == null ? "" : "：" + errF));
                        }
                    }
                });
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
