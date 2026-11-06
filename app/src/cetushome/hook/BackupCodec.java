package cetushome.hook;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Module-settings backup codec: line-based plain text, format v1.
 *
 *  File layout (UTF-8, LF or CRLF, an optional BOM is tolerated; the first
 *  non-blank line must be the magic line):
 *
 *      MixUnfold config backup
 *      schema=1                     format gate: > SCHEMA rejects the file
 *      pkg=cetushome.rotationunlock origin gate: must match MODULE_PKG
 *      version=1.9.1                informational
 *      exportedAt=1699000000000     informational (millis)
 *      &lt;key&gt;=&lt;value&gt;              the eight config keys, LEGACY_KEYS order
 *
 *  Security model: the backup file is untrusted input (user-chosen via SAF,
 *  may be hand-edited or foreign). Structural damage (wrong magic, missing
 *  or unsupported schema, wrong pkg, a line without '=', a duplicate key,
 *  oversize) rejects the whole file; per-key damage (unknown key, non-numeric
 *  or out-of-domain int) drops that key with a counter and keeps the rest.
 *  Dropped-by-counter is the ExcludeCodec philosophy applied to files: the
 *  exclude value itself is passed through raw here and re-filtered by
 *  ExcludeCodec inside SettingsWriter at write time — no layer trusts its
 *  caller. KEY_MIGRATED is internal migration state and never part of a
 *  backup.
 *
 *  Import semantics live in the caller (MainActivity): only keys present in
 *  the file are written — present wins, the ensureMigrated rule that stored
 *  values are never silently replaced by defaults.
 *
 *  Pure java.util on purpose: the JVM regression suite compiles this class
 *  without android.jar. */
final class BackupCodec {

    static final int SCHEMA = 1;
    static final String MAGIC = "MixUnfold config backup";
    /** Whole-file size cap in chars; enforced here and on the input stream. */
    static final int MAX_CHARS = 64 * 1024;

    /** User-visible reject reasons (exact-match asserted by BackupCodecTest). */
    static final String REJECT_EMPTY = "备份文件为空";
    static final String REJECT_NOT_BACKUP = "不是 MixUnfold 配置备份";
    static final String REJECT_BAD_LINE = "备份文件损坏（存在不是「键=值」的行）";
    static final String REJECT_DUP_KEY = "备份文件损坏（存在重复键）";
    static final String REJECT_NO_SCHEMA = "备份文件损坏（缺少 schema）";
    static final String REJECT_SCHEMA_UNSUPPORTED = "备份格式版本不受支持";
    static final String REJECT_SCHEMA_NEWER = "备份来自更新版本的 MixUnfold，请先升级模块";
    static final String REJECT_NO_PKG = "备份文件损坏（缺少 pkg）";
    static final String REJECT_PKG_MISMATCH = "备份不是本模块的配置";
    static final String REJECT_TOO_LARGE = "备份文件过大";

    private BackupCodec() {
    }

    /** Serialize the config values. values maps key -> string form (ints in
     *  decimal); keys absent from the map are skipped so the file mirrors
     *  what the app actually stored. */
    static String encode(String pkg, String version, long exportedAt,
            Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append(MAGIC).append('\n');
        sb.append("schema=").append(SCHEMA).append('\n');
        sb.append("pkg=").append(pkg == null ? "" : pkg).append('\n');
        sb.append("version=").append(version == null ? "" : version).append('\n');
        sb.append("exportedAt=").append(exportedAt).append('\n');
        for (String key : PrefsKeys.LEGACY_KEYS) {
            String v = values.get(key);
            if (v != null) {
                sb.append(key).append('=').append(v).append('\n');
            }
        }
        return sb.toString();
    }

    /** Parse result: the whitelisted, type- and range-valid values (string
     *  form) plus drop counters, or rejectReason when the file is
     *  structurally invalid. */
    static final class Parsed {
        final LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
        int skippedUnknown;
        int skippedInvalid;
        String rejectReason;

        boolean ok() {
            return rejectReason == null;
        }
    }

    static Parsed parse(String text) {
        Parsed out = new Parsed();
        if (text == null || text.trim().isEmpty()) {
            out.rejectReason = REJECT_EMPTY;
            return out;
        }
        if (text.length() > MAX_CHARS) {
            out.rejectReason = REJECT_TOO_LARGE;
            return out;
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1); // tolerate a UTF-8 BOM from editors
        }
        String[] lines = text.split("\n", -1);

        int i = 0;
        while (i < lines.length && lines[i].trim().isEmpty()) {
            i++;
        }
        if (i >= lines.length || !MAGIC.equals(lines[i].trim())) {
            out.rejectReason = REJECT_NOT_BACKUP;
            return out;
        }
        i++;

        boolean schemaSeen = false;
        boolean pkgSeen = false;
        HashSet<String> seen = new HashSet<String>();
        for (; i < lines.length; i++) {
            String line = lines[i];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue; // blank lines are structural, not data
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                out.rejectReason = REJECT_BAD_LINE;
                return out;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!seen.add(key)) {
                out.rejectReason = REJECT_DUP_KEY;
                return out;
            }

            if ("schema".equals(key)) {
                schemaSeen = true;
                Integer s = parseIntOrNull(value);
                if (s == null || s.intValue() < SCHEMA) {
                    out.rejectReason = REJECT_SCHEMA_UNSUPPORTED;
                    return out;
                }
                if (s.intValue() > SCHEMA) {
                    out.rejectReason = REJECT_SCHEMA_NEWER;
                    return out;
                }
            } else if ("pkg".equals(key)) {
                pkgSeen = true;
                if (!PrefsKeys.MODULE_PKG.equals(value)) {
                    out.rejectReason = REJECT_PKG_MISMATCH;
                    return out;
                }
            } else if ("version".equals(key) || "exportedAt".equals(key)) {
                // informational: accepted verbatim, never applied
            } else if (PrefsKeys.isLegacyKey(key)) {
                if (PrefsKeys.KEY_ROT_EXCLUDE.equals(key)) {
                    // raw passthrough; SettingsWriter re-filters via
                    // ExcludeCodec on write
                    out.values.put(key, value);
                } else {
                    Integer v = parseIntOrNull(value);
                    if (v == null || !PrefsKeys.validInt(key, v.intValue())) {
                        out.skippedInvalid++;
                    } else {
                        out.values.put(key, String.valueOf(v.intValue()));
                    }
                }
            } else {
                // forward compatibility within schema 1: a newer module may
                // add keys; keep loading, let the report carry the drop
                out.skippedUnknown++;
            }
        }

        if (!schemaSeen) {
            out.rejectReason = REJECT_NO_SCHEMA;
        } else if (!pkgSeen) {
            out.rejectReason = REJECT_NO_PKG;
        }
        return out;
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.valueOf(Integer.parseInt(s));
        } catch (Throwable t) {
            return null;
        }
    }
}
