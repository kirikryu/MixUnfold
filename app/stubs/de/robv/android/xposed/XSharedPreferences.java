package de.robv.android.xposed;

import android.content.SharedPreferences;

import java.io.File;
import java.util.Set;

// compile-time stub; provided by LSPosed at runtime. Read-only API only on
// purpose: the hook processes must never be able to write module prefs.
public final class XSharedPreferences {

    public XSharedPreferences(String packageName, String prefFileName) {
    }

    public XSharedPreferences(File prefFile) {
    }

    public String getString(String key, String defValue) {
        return defValue;
    }

    public Set<String> getStringSet(String key, Set<String> defValues) {
        return defValues;
    }

    public int getInt(String key, int defValue) {
        return defValue;
    }

    public boolean getBoolean(String key, boolean defValue) {
        return defValue;
    }

    public boolean contains(String key) {
        return false;
    }

    public void reload() {
    }

    public void registerOnSharedPreferenceChangeListener(
            SharedPreferences.OnSharedPreferenceChangeListener listener) {
    }

    public void unregisterOnSharedPreferenceChangeListener(
            SharedPreferences.OnSharedPreferenceChangeListener listener) {
    }
}
