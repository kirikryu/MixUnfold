package cetushome.hook;

import java.util.Arrays;
import java.util.TreeSet;

/** JVM regression tests for the exclude-list / root-shell hardening
 *  (Mimosa scan scan-2026-11-04T13-06-48.048Z-2c5dbccc5a50). Defensive
 *  only: asserts poisoning is dropped/escaped, never how to exploit.
 *  No shell is ever executed — shellQuote is verified by a strict POSIX
 *  single-quote grammar parser (shellUnquote) instead. Run via
 *  tools/run-tests.sh. */
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

        // --- RootWriter.shellQuote: exact POSIX single-quote escaping ----
        check("shellQuote exact escaping",
                "'a'\\'';id;echo '\\'''", RootWriter.shellQuote(payload));
        check("shellQuote empty string",
                "''", RootWriter.shellQuote(""));

        // --- end to end: su-shell line for a poisoned stored value -------
        String line = SettingsWriter.settingShellLine("cetus_free_rot_exclude",
                ExcludeCodec.join(ExcludeCodec.parse(payload)));
        check("shell line neutralized",
                "settings put system 'cetus_free_rot_exclude' 'id'\n", line);

        // --- shellQuote cannot be closed: a strict parser of the POSIX
        //     single-quote grammar must decode the output back to the exact
        //     input, and any quote closure without the '\'' splice is a
        //     structural failure (that splice is the only legal way out) --
        check("quote grammar decodes payload back",
                payload, shellUnquote(RootWriter.shellQuote(payload)));
        check("quote grammar holds for clean values",
                "ok.app", shellUnquote(RootWriter.shellQuote("ok.app")));
        check("quote grammar holds for empty value",
                "", shellUnquote(RootWriter.shellQuote("")));
        boolean rejected = false;
        try {
            shellUnquote("'a';id");            // closed quote, no splice
        } catch (AssertionError expected) {
            rejected = true;
        }
        check("unescaped quote closure rejected by grammar",
                true, rejected);

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    /** Strict parser for what RootWriter.shellQuote emits: quoted runs
     *  spliced by '\''. Decodes the single word a POSIX shell would pass;
     *  throws on any text outside quotes or any closure that is not the
     *  escaped splice — i.e. on anything closable. */
    private static String shellUnquote(String q) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = q.length();
        while (i < n) {
            if (q.charAt(i) != '\'') {
                throw new AssertionError("unquoted text at " + i + ": " + q);
            }
            i++;                               // opening '
            while (true) {
                while (i < n && q.charAt(i) != '\'') {
                    out.append(q.charAt(i));
                    i++;
                }
                if (i >= n) {
                    throw new AssertionError("unterminated quote: " + q);
                }
                i++;                           // closing '
                if (i >= n) {
                    break;                     // clean end of string
                }
                // mid-string closure must be the '\'' splice; its third
                // quote reopens, so literals continue in the same loop
                if (q.charAt(i) != '\\' || i + 2 >= n
                        || q.charAt(i + 1) != '\'' || q.charAt(i + 2) != '\'') {
                    throw new AssertionError("unescaped quote closure at " + i + ": " + q);
                }
                out.append('\'');
                i += 3;
            }
        }
        return out.toString();
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
