package cetushome.hook;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Second-level exclusion picker: search box + checkable app list, opened from
 * the main panel. The whole page is ONE scroll container (a ListView whose
 * short header holds search / count / clear) so the cover screen can reach
 * the bottom without nested-scroll conflicts. Checking a row writes the
 * exclusion settings key immediately through the shared root shell — the
 * system_server side reloads it live, no restart.
 */
public class AppPickerActivity extends Activity {

    private int cPrimary, cSecondary, cAccent;
    private TextView excludeCount;
    private Button grantBtn;
    private AppAdapter apps;
    private final HashSet<String> excluded = new HashSet<String>();
    /** Serialized write+verify tasks; writeSeq guards stale verifications. */
    private final java.util.concurrent.ExecutorService writeExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile int writeSeq;
    /** Loader generation: only the newest query may repaint the list. */
    private volatile int loadSeq;
    private BroadcastReceiver pkgObs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int[] pal = ThemeUtil.palette(this);
        cPrimary = pal[0];
        cSecondary = pal[1];
        cAccent = pal[2];

        String raw = Settings.System.getString(getContentResolver(),
                MainHook.KEY_ROT_EXCLUDE);
        excluded.addAll(parseExclude(raw));

        apps = new AppAdapter();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(12), pad, 0);

        TextView name = new TextView(this);
        name.setText("排除应用（勾选 = 该应用不旋转）");
        name.setTextColor(cPrimary);
        name.setTextSize(16);
        root.addView(name);

        TextView note = new TextView(this);
        note.setTextSize(12);
        note.setTextColor(cSecondary);
        note.setPadding(dp(2), dp(2), 0, 0);
        note.setText("清单内的应用保持原厂方向行为，其余跟随旋转；勾选即时生效，无需重启。");
        root.addView(note);

        grantBtn = new Button(this);
        grantBtn.setAllCaps(false);
        grantBtn.setTextSize(14);
        grantBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS));
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
        root.addView(grantBtn);
        refreshGrantUi();

        final EditText search = new EditText(this);
        search.setHint("搜索应用名或包名");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(cPrimary);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                apps.setQuery(s.toString());
            }
        });
        root.addView(search);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        excludeCount = new TextView(this);
        excludeCount.setTextSize(13);
        excludeCount.setTextColor(cSecondary);
        excludeCount.setPadding(dp(4), 0, dp(12), 0);
        row.addView(excludeCount, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button clearBtn = new Button(this);
        clearBtn.setText("清空排除");
        clearBtn.setAllCaps(false);
        clearBtn.setTextSize(13);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                excluded.clear();
                apps.notifyDataSetChanged();
                updateCount();
                writeExclude();
            }
        });
        row.addView(clearBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(row);

        root.addView(makeGap());

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setAdapter(apps);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateCount();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshGrantUi();
        // reload on every return: the picker often survives in background
        // while new apps are installed, and onCreate ran only once
        loadAppsAsync();
        if (pkgObs == null) {
            // install/replace/remove finishing while the page is open
            pkgObs = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    loadAppsAsync();
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_REPLACED);
            f.addAction(Intent.ACTION_PACKAGE_REMOVED);
            f.addDataScheme("package");
            registerReceiver(pkgObs, f);
        }
    }

    @Override
    protected void onPause() {
        if (pkgObs != null) {
            unregisterReceiver(pkgObs);
            pkgObs = null;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        writeExec.shutdownNow();
    }

    private void refreshGrantUi() {
        if (grantBtn == null) {
            return;
        }
        boolean ok = Settings.System.canWrite(this);
        grantBtn.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (!ok) {
            grantBtn.setText("① 点此授予「修改系统设置」权限（一次性，勾选即可保存）");
        }
    }

    private void loadAppsAsync() {
        final int seq = ++loadSeq;
        new Thread(new Runnable() {
            @Override public void run() {
                final ArrayList<AppItem> loaded = new ArrayList<AppItem>();
                PackageManager pm = getPackageManager();
                try {
                    for (PackageInfo pi : pm.getInstalledPackages(0)) {
                        if (pi == null || pi.applicationInfo == null) {
                            continue;
                        }
                        loaded.add(new AppItem(
                                String.valueOf(pi.applicationInfo.loadLabel(pm)),
                                pi.packageName));
                    }
                } catch (Throwable t) {
                    // keep whatever resolved — the picker still works
                }
                final Collator col = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
                java.util.Collections.sort(loaded, new java.util.Comparator<AppItem>() {
                    @Override public int compare(AppItem a, AppItem b) {
                        int c = col.compare(a.label, b.label);
                        return c != 0 ? c : a.pkg.compareTo(b.pkg);
                    }
                });
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (seq != loadSeq) {
                            return; // a newer load already decided the list
                        }
                        apps.setData(loaded);
                        if (loaded.isEmpty()) {
                            // MIUI 「读取应用列表」denied returns a near-empty set
                            Toast.makeText(AppPickerActivity.this,
                                    "未读取到应用列表，请检查系统的「读取应用列表」权限",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void toggleExclude(String pkg, CheckBox cb) {
        if (!excluded.remove(pkg)) {
            excluded.add(pkg);
        }
        cb.setChecked(excluded.contains(pkg));
        updateCount();
        writeExclude();
    }

    private void updateCount() {
        if (excludeCount != null) {
            excludeCount.setText("已排除 " + excluded.size() + " 个应用");
        }
    }

    private void writeExclude() {
        final String value = joinExcluded();
        final int seq = ++writeSeq;
        writeExec.execute(new Runnable() {
            @Override public void run() {
                verifyWrite(seq, value);
            }
        });
    }

    /** Verify one exclusion write. The root-shell path lands the key
     *  asynchronously (su spawn + `settings` CLI startup, tens to hundreds
     *  of ms) while this thread reads back in ~1 ms — a single immediate
     *  read-back raced every write and reverted each toggle, so checking or
     *  unchecking never stuck. Poll the provider until it reflects the
     *  write, retry once (a cold `su` can stall on the superuser prompt),
     *  and only surface a mismatch for the newest write. */
    private void verifyWrite(int seq, String value) {
        boolean direct = SettingsWriter.put(this, MainHook.KEY_ROT_EXCLUDE, value);
        HashSet<String> want = parseExclude(value);
        HashSet<String> stored = settleRead(want, direct);
        if (stored != null && !stored.equals(want) && seq == writeSeq) {
            SettingsWriter.put(this, MainHook.KEY_ROT_EXCLUDE, value);
            stored = settleRead(want, false);
        }
        final HashSet<String> storedF = stored;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                // what you see is what is stored; a newer toggle decides the
                // state on its own, so a stale verification must not revert it
                if (storedF == null || storedF.equals(excluded) || seq != writeSeq) {
                    return;
                }
                excluded.clear();
                excluded.addAll(storedF);
                apps.notifyDataSetChanged();
                updateCount();
                Toast.makeText(AppPickerActivity.this,
                        "写入未生效，请授予「修改系统设置」权限或检查 root 授权",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Read back until the provider shows `want` (direct provider writes
     *  land synchronously; shell writes need a few rounds) or retries run
     *  out — the last read is returned either way, null on interruption. */
    private HashSet<String> settleRead(HashSet<String> want, boolean direct) {
        int rounds = direct ? 2 : 10;
        HashSet<String> now = null;
        for (int i = 0; i < rounds; i++) {
            if (i > 0) {
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    return null;
                }
            }
            now = readStoredExclude();
            if (now.equals(want)) {
                break;
            }
        }
        return now;
    }

    private String joinExcluded() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String p : excluded) {
            if (!first) {
                sb.append(';');
            }
            sb.append(p);
            first = false;
        }
        return sb.toString();
    }

    /** Exclusion key parser shared with MainHook's readExclude: semicolon-
     *  separated package names, blank segments dropped. */
    private static HashSet<String> parseExclude(String raw) {
        HashSet<String> set = new HashSet<String>();
        if (raw != null) {
            for (String s : raw.split(";")) {
                s = s.trim();
                if (!s.isEmpty()) {
                    set.add(s);
                }
            }
        }
        return set;
    }

    private HashSet<String> readStoredExclude() {
        try {
            return parseExclude(Settings.System.getString(getContentResolver(),
                    MainHook.KEY_ROT_EXCLUDE));
        } catch (Throwable ignored) {
            return new HashSet<String>();
        }
    }

    private static class AppItem {
        final String label;
        final String pkg;

        AppItem(String label, String pkg) {
            this.label = label;
            this.pkg = pkg;
        }
    }

    /** App picker rows: app name (primary) + package name (secondary) + a
     *  checkbox synced with the exclusion key. Filtering by name/package
     *  happens in-memory; rows recycle through a ViewHolder. */
    private class AppAdapter extends BaseAdapter {
        private final ArrayList<AppItem> all = new ArrayList<AppItem>();
        private final ArrayList<AppItem> shown = new ArrayList<AppItem>();
        private String query = "";

        void setData(List<AppItem> data) {
            all.clear();
            all.addAll(data);
            refilter();
        }

        void setQuery(String q) {
            query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
            refilter();
        }

        private void refilter() {
            shown.clear();
            for (AppItem it : all) {
                if (query.isEmpty()
                        || it.label.toLowerCase(Locale.ROOT).contains(query)
                        || it.pkg.toLowerCase(Locale.ROOT).contains(query)) {
                    shown.add(it);
                }
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() {
            return shown.size();
        }

        @Override public Object getItem(int position) {
            return shown.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row;
            if (convertView == null) {
                row = new Row();
                row.root = new LinearLayout(AppPickerActivity.this);
                row.root.setOrientation(LinearLayout.HORIZONTAL);
                row.root.setGravity(Gravity.CENTER_VERTICAL);
                row.root.setPadding(dp(12), dp(8), dp(12), dp(8));
                row.cb = new CheckBox(AppPickerActivity.this);
                row.cb.setFocusable(false);
                row.cb.setClickable(false);
                row.root.addView(row.cb, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout text = new LinearLayout(AppPickerActivity.this);
                text.setOrientation(LinearLayout.VERTICAL);
                row.name = new TextView(AppPickerActivity.this);
                row.name.setTextColor(cPrimary);
                row.name.setTextSize(15);
                row.pkgView = new TextView(AppPickerActivity.this);
                row.pkgView.setTextColor(cSecondary);
                row.pkgView.setTextSize(11);
                text.addView(row.name);
                text.addView(row.pkgView);
                row.root.addView(text, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.root.setTag(row);
                row.root.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Row r = (Row) v.getTag();
                        toggleExclude(r.pkg, r.cb);
                    }
                });
            } else {
                row = (Row) convertView.getTag();
            }
            AppItem it = shown.get(position);
            row.pkg = it.pkg;
            row.name.setText(it.label);
            row.pkgView.setText(it.pkg);
            row.cb.setChecked(excluded.contains(it.pkg));
            return row.root;
        }
    }

    private static class Row {
        LinearLayout root;
        CheckBox cb;
        TextView name;
        TextView pkgView;
        String pkg;
    }

    private View makeGap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
