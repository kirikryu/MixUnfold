package cetushome.hook;

import java.util.LinkedHashMap;
import java.util.Map;

/** JVM regression tests for the settings-backup codec (format v1): round
 *  trip, structural rejects, per-key drops and the PrefsKeys.validInt import
 *  guard. The backup file is untrusted input, so these cases pin the defense
 *  contract documented on BackupCodec — poisoning is rejected/dropped, never
 *  how to exploit. Run via tools/run-tests.sh. */
public final class BackupCodecTest {

    private static int failures;

    private static final String HEADER =
            BackupCodec.MAGIC + "\nschema=1\npkg=" + PrefsKeys.MODULE_PKG
                    + "\nversion=1.9.1\nexportedAt=1700000000000\n";

    private static final String[] KV8 = {
            PrefsKeys.KEY_COLUMNS + "=3",
            PrefsKeys.KEY_PAD_PORT + "=120",
            PrefsKeys.KEY_PAD_LAND + "=280",
            PrefsKeys.KEY_LAND_PAGES + "=3",
            PrefsKeys.KEY_PAD_OPT + "=0",
            PrefsKeys.KEY_LOCK_ROT + "=1",
            PrefsKeys.KEY_FREE_ROT + "=1",
            PrefsKeys.KEY_ROT_EXCLUDE + "=com.a;com.b",
    };

    public static void main(String[] args) {
        roundTrip();
        structuralRejects();
        perKeyDrops();
        rangeBounds();
        tolerance();
        validIntGuard();

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    // --- encode -> parse round trip ----------------------------------------

    private static void roundTrip() {
        BackupCodec.Parsed p = BackupCodec.parse(encodeAll8());
        check("round trip ok", true, p.ok());
        check("round trip 8 values", all8(), p.values);
        check("round trip no unknown dropped", 0, p.skippedUnknown);
        check("round trip no invalid dropped", 0, p.skippedInvalid);

        // keys in LEGACY_KEYS order, one per line, trailing newline
        StringBuilder want = new StringBuilder(HEADER);
        for (String kv : KV8) {
            want.append(kv).append('\n');
        }
        check("encode emits LEGACY_KEYS order", want.toString(), encodeAll8());

        // missing keys are skipped, not written as defaults
        Map<String, String> partial = new LinkedHashMap<String, String>();
        partial.put(PrefsKeys.KEY_FREE_ROT, "0");
        String text = BackupCodec.encode(
                PrefsKeys.MODULE_PKG, "1.9.1", 1L, partial);
        check("encode skips absent keys",
                BackupCodec.MAGIC + "\nschema=1\npkg=" + PrefsKeys.MODULE_PKG
                        + "\nversion=1.9.1\nexportedAt=1\n"
                        + PrefsKeys.KEY_FREE_ROT + "=0\n",
                text);
    }

    // --- structural rejects: the whole file -------------------------------

    private static void structuralRejects() {
        check("null text rejected", BackupCodec.REJECT_EMPTY,
                BackupCodec.parse(null).rejectReason);
        check("empty text rejected", BackupCodec.REJECT_EMPTY,
                BackupCodec.parse("").rejectReason);
        check("wrong magic rejected", BackupCodec.REJECT_NOT_BACKUP,
                BackupCodec.parse("MixUnfold backup\nschema=1\n").rejectReason);
        check("kv without magic rejected", BackupCodec.REJECT_NOT_BACKUP,
                BackupCodec.parse(KV8[0]).rejectReason);

        StringBuilder noSchema = new StringBuilder(BackupCodec.MAGIC).append('\n')
                .append("pkg=").append(PrefsKeys.MODULE_PKG).append('\n')
                .append(KV8[0]).append('\n');
        check("missing schema rejected", BackupCodec.REJECT_NO_SCHEMA,
                BackupCodec.parse(noSchema.toString()).rejectReason);

        check("newer schema rejected", BackupCodec.REJECT_SCHEMA_NEWER,
                BackupCodec.parse(
                        swap(backup(KV8), "schema=1", "schema=2")).rejectReason);
        check("older schema rejected", BackupCodec.REJECT_SCHEMA_UNSUPPORTED,
                BackupCodec.parse(
                        swap(backup(KV8), "schema=1", "schema=0")).rejectReason);
        check("garbage schema rejected", BackupCodec.REJECT_SCHEMA_UNSUPPORTED,
                BackupCodec.parse(
                        swap(backup(KV8), "schema=1", "schema=x")).rejectReason);

        StringBuilder noPkg = new StringBuilder(BackupCodec.MAGIC).append('\n')
                .append("schema=1\n").append(KV8[0]).append('\n');
        check("missing pkg rejected", BackupCodec.REJECT_NO_PKG,
                BackupCodec.parse(noPkg.toString()).rejectReason);
        check("foreign pkg rejected", BackupCodec.REJECT_PKG_MISMATCH,
                BackupCodec.parse(
                        swap(backup(KV8), "pkg=" + PrefsKeys.MODULE_PKG,
                                "pkg=com.other.module")).rejectReason);

        check("line without '=' rejected", BackupCodec.REJECT_BAD_LINE,
                BackupCodec.parse(backup(KV8) + "garbage line\n").rejectReason);

        check("duplicate config key rejected", BackupCodec.REJECT_DUP_KEY,
                BackupCodec.parse(backup(KV8).concat(KV8[0] + "\n")).rejectReason);
        check("duplicate header key rejected", BackupCodec.REJECT_DUP_KEY,
                BackupCodec.parse(
                        swap(backup(KV8), "schema=1\npkg=",
                                "schema=1\nschema=1\npkg=")).rejectReason);

        String big = new String(new char[BackupCodec.MAX_CHARS + 1])
                .replace('\0', 'a');
        check("oversize rejected", BackupCodec.REJECT_TOO_LARGE,
                BackupCodec.parse(big).rejectReason);
    }

    // --- per-key drops: file still loads -----------------------------------

    private static void perKeyDrops() {
        // unknown key (a future schema-1 module may add keys) is skipped
        BackupCodec.Parsed p = BackupCodec.parse(
                backup(KV8).concat("cetus_future_thing=7\n"));
        check("unknown key skipped, file loads", true, p.ok());
        check("unknown key counted", 1, p.skippedUnknown);
        check("unknown key not applied", false,
                p.values.containsKey("cetus_future_thing"));
        check("valid keys survive unknown key", all8(), p.values);

        // non-numeric int
        p = BackupCodec.parse(swap(backup(KV8),
                PrefsKeys.KEY_COLUMNS + "=3", PrefsKeys.KEY_COLUMNS + "=abc"));
        check("non-numeric int dropped", true, p.ok());
        check("non-numeric int counted", 1, p.skippedInvalid);
        check("non-numeric int not applied", false,
                p.values.containsKey(PrefsKeys.KEY_COLUMNS));

        // out-of-range int
        p = BackupCodec.parse(swap(backup(KV8),
                PrefsKeys.KEY_COLUMNS + "=3", PrefsKeys.KEY_COLUMNS + "=9"));
        check("out-of-range int dropped", true, p.ok());
        check("out-of-range int counted", 1, p.skippedInvalid);
        check("out-of-range int not applied", false,
                p.values.containsKey(PrefsKeys.KEY_COLUMNS));

        // the exclude value passes through raw; ExcludeCodec re-filters it
        // inside SettingsWriter at write time
        p = BackupCodec.parse(swap(backup(KV8),
                PrefsKeys.KEY_ROT_EXCLUDE + "=com.a;com.b",
                PrefsKeys.KEY_ROT_EXCLUDE + "=a=b;c';id;"));
        check("exclude passes through raw", "a=b;c';id;",
                p.values.get(PrefsKeys.KEY_ROT_EXCLUDE));
    }

    // --- validInt bounds (the import range guard) ---------------------------

    private static void rangeBounds() {
        String c = PrefsKeys.KEY_COLUMNS;
        check("columns 2 ok", true, PrefsKeys.validInt(c, 2));
        check("columns 6 ok", true, PrefsKeys.validInt(c, 6));
        check("columns 1 invalid", false, PrefsKeys.validInt(c, 1));
        check("columns 7 invalid", false, PrefsKeys.validInt(c, 7));

        String lp = PrefsKeys.KEY_LAND_PAGES;
        check("land pages 2 ok", true, PrefsKeys.validInt(lp, 2));
        check("land pages 3 ok", true, PrefsKeys.validInt(lp, 3));
        check("land pages 4 invalid", false, PrefsKeys.validInt(lp, 4));

        String b = PrefsKeys.KEY_FREE_ROT;
        check("boolean 0 ok", true, PrefsKeys.validInt(b, 0));
        check("boolean 1 ok", true, PrefsKeys.validInt(b, 1));
        check("boolean 2 invalid", false, PrefsKeys.validInt(b, 2));

        String pad = PrefsKeys.KEY_PAD_PORT;
        check("pad 0 ok", true, PrefsKeys.validInt(pad, 0));
        check("pad 10000 ok", true, PrefsKeys.validInt(pad, 10000));
        check("pad -1 invalid", false, PrefsKeys.validInt(pad, -1));
        check("pad 10001 invalid", false, PrefsKeys.validInt(pad, 10001));

        check("null key invalid", false, PrefsKeys.validInt(null, 3));
        check("foreign key invalid", false,
                PrefsKeys.validInt("cetus_future_thing", 3));
        check("string key not an int key", false,
                PrefsKeys.validInt(PrefsKeys.KEY_ROT_EXCLUDE, 1));
    }

    // --- tolerance: editors and platforms ------------------------------------

    private static void tolerance() {
        check("CRLF tolerated", true,
                BackupCodec.parse(backup(KV8).replace("\n", "\r\n")).ok());
        check("BOM tolerated", true,
                BackupCodec.parse("\uFEFF" + backup(KV8)).ok());
        check("leading/middle/trailing blank lines tolerated", true,
                BackupCodec.parse("\n\n" + backup(KV8) + "\n\n\n").ok());
        check("spaces around '=' tolerated", "3",
                BackupCodec.parse(backup(KV8).replace(
                        PrefsKeys.KEY_COLUMNS + "=3",
                        PrefsKeys.KEY_COLUMNS + " = 3 "))
                        .values.get(PrefsKeys.KEY_COLUMNS));
        check("key order after magic is free", true,
                BackupCodec.parse(BackupCodec.MAGIC + "\n" + KV8[0] + "\n"
                        + "schema=1\npkg=" + PrefsKeys.MODULE_PKG + "\n").ok());
        check("informational fields accepted verbatim", true,
                BackupCodec.parse(swap(backup(KV8),
                        "exportedAt=1700000000000", "exportedAt=whenever")).ok());
    }

    private static void validIntGuard() {
        // covered in rangeBounds(); kept as a named section for the runner
    }

    // --- helpers -------------------------------------------------------------

    private static String encodeAll8() {
        return BackupCodec.encode(PrefsKeys.MODULE_PKG, "1.9.1",
                1700000000000L, all8());
    }

    private static Map<String, String> all8() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put(PrefsKeys.KEY_COLUMNS, "3");
        m.put(PrefsKeys.KEY_PAD_PORT, "120");
        m.put(PrefsKeys.KEY_PAD_LAND, "280");
        m.put(PrefsKeys.KEY_LAND_PAGES, "3");
        m.put(PrefsKeys.KEY_PAD_OPT, "0");
        m.put(PrefsKeys.KEY_LOCK_ROT, "1");
        m.put(PrefsKeys.KEY_FREE_ROT, "1");
        m.put(PrefsKeys.KEY_ROT_EXCLUDE, "com.a;com.b");
        return m;
    }

    private static String backup(String... kvLines) {
        StringBuilder sb = new StringBuilder(HEADER);
        for (String kv : kvLines) {
            sb.append(kv).append('\n');
        }
        return sb.toString();
    }

    private static String swap(String text, String from, String to) {
        int i = text.indexOf(from);
        if (i < 0) {
            throw new IllegalArgumentException("swap source not found: " + from);
        }
        return text.substring(0, i) + to + text.substring(i + from.length());
    }

    private static void check(String name, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name + "\n  want: " + want + "\n  got:  " + got);
        } else {
            System.out.println("pass " + name);
        }
    }

    private BackupCodecTest() {
    }
}
