package cetushome.hook;

import java.util.Arrays;
import java.util.TreeSet;

/** JVM regression tests for the exclusion-list input validation that
 *  survived the 1.9.0 storage migration. The original cases were added with
 *  the Settings.System/su hardening (Mimosa scan
 *  scan-2026-11-04T13-06-48.048Z-2c5dbccc5a50); the shell layers are gone,
 *  but the whitelist is kept as defense in depth for values arriving from the
 *  legacy migration or a restored backup. Defensive only: asserts poisoning
 *  is dropped, never how to exploit. Run via tools/run-tests.sh. */
public final class ExcludeHardeningTest {

    private static int failures;

    public static void main(String[] args) {
        String payload = "a';id;echo '";

        // --- ExcludeCodec.parse: legal package names survive -------------
        check("legal segments kept",
                set("com.example.app", "a_b.c1"),
                new TreeSet<String>(ExcludeCodec.parse("com.example.app;a_b.c1")));
        check("trim and blank segments dropped",
                set("a"), new TreeSet<String>(ExcludeCodec.parse(" ; a ;")));
        check("null raw parses to empty set",
                0, ExcludeCodec.parse(null).size());

        // --- parse: quote-bearing segments dropped and logged ------------
        // "id" between the semicolons is legal-shaped and correctly survives
        // — as a package name it is inert; every '...'-bearing piece is gone.
        check("payload segments with quotes dropped",
                set("id"), new TreeSet<String>(ExcludeCodec.parse(payload)));
        check("illegal drop is logged",
                true, android.util.Log.WARNINGS.toString().contains("a'"));

        // --- ExcludeCodec.join: re-validation, never trusts upstream -----
        check("join keeps legal segments in order",
                "com.example.app;a_b.c1",
                ExcludeCodec.join(Arrays.asList("com.example.app", "a_b.c1")));
        check("join drops illegal segment",
                "ok.app", ExcludeCodec.join(Arrays.asList("ok.app", "a';id")));
        check("join skips blanks and nulls",
                "ok.app", ExcludeCodec.join(Arrays.asList("ok.app", "", null)));
        check("join output never contains a quote",
                true, ExcludeCodec.join(Arrays.asList("a'", "b`c", "d$e")).indexOf('\'') < 0);
        check("join empty collection is empty string",
                "", ExcludeCodec.join(new TreeSet<String>()));

        // --- segment grammar ---------------------------------------------
        check("isLegalSegment accepts package characters",
                true, ExcludeCodec.isLegalSegment("a_b.c1"));
        check("isLegalSegment rejects quote",
                false, ExcludeCodec.isLegalSegment("a'b"));
        check("isLegalSegment rejects semicolon",
                false, ExcludeCodec.isLegalSegment("a;b"));
        check("isLegalSegment rejects null",
                false, ExcludeCodec.isLegalSegment(null));

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static TreeSet<String> set(String... items) {
        return new TreeSet<String>(Arrays.asList(items));
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

    private ExcludeHardeningTest() {
    }
}
