package cetushome.hook;

import android.util.Log;
import java.util.HashSet;
import java.util.regex.Pattern;

/** Exclusion-list codec shared by the picker UI, the hook's config reader
 *  and the settings writer. Segments are ';'-joined Android package names.
 *
 *  Security: Settings.System is writable by any app holding the user-granted
 *  WRITE_SETTINGS app-op, so a stored value cannot be trusted. Every decode
 *  (parse) and encode (join) pass keeps only [A-Za-z0-9_.]+ segments — a
 *  poisoned segment (e.g. a';id;echo ') must never survive into a root-shell
 *  write (RootWriter). Filtering runs at parse, at join and again inside
 *  SettingsWriter: defense in depth, no layer trusts its caller. */
final class ExcludeCodec {

    private static final String TAG = "CetusHomeHook";
    private static final Pattern LEGAL_SEGMENT = Pattern.compile("[A-Za-z0-9_.]+");

    private ExcludeCodec() {
    }

    /** Package-name-shaped segment: letters, digits, underscore, dot. */
    static boolean isLegalSegment(String s) {
        return s != null && LEGAL_SEGMENT.matcher(s).matches();
    }

    /** split(";") + trim(), keeping legal segments only; illegal segments
     *  are dropped and logged. Blank segments are structural, not attacks,
     *  and stay silent (same as the pre-hardening parsers). */
    static HashSet<String> parse(String raw) {
        HashSet<String> set = new HashSet<String>();
        if (raw == null) {
            return set;
        }
        for (String s : raw.split(";")) {
            s = s.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (isLegalSegment(s)) {
                set.add(s);
            } else {
                Log.w(TAG, "dropped illegal exclude segment: " + s);
            }
        }
        return set;
    }

    /** Join with ';', re-validating every segment — never trusts an
     *  already-sanitized collection. */
    static String join(Iterable<String> segments) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String p : segments) {
            if (p == null) {
                continue;
            }
            p = p.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (!isLegalSegment(p)) {
                Log.w(TAG, "dropped illegal exclude segment: " + p);
                continue;
            }
            if (!first) {
                sb.append(';');
            }
            sb.append(p);
            first = false;
        }
        return sb.toString();
    }
}
