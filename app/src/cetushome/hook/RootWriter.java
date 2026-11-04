package cetushome.hook;

import java.io.DataOutputStream;

/** One persistent su shell per app process: commands stream into it without
 *  paying a process spawn per change. A dead shell is respawned once inside
 *  the same write; `settings put` is silent on success, so no output
 *  draining is needed for these few-hundred-byte batches.
 *
 *  Shell parsing cannot be removed by switching to an argument array: su
 *  itself runs its input through a shell, whatever API spawned it. The last
 *  line of defense is shellQuote() — every string crossing into the shell
 *  goes through single-quote escaping, on top of the upstream ExcludeCodec
 *  whitelist (see SettingsWriter). */
final class RootWriter {

    private static Process suProc;
    private static DataOutputStream suOut;

    private RootWriter() {
    }

    /** POSIX single-quote escaping: wrap in '...', turning every embedded '
     *  into '\''. The result can never close the quoting, so the caller's
     *  string always arrives as one literal word. */
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** The module's one and only shell sink: reached solely from
     *  SettingsWriter (both overloads), never directly from a hook callback
     *  (static advisors keep flagging this line — see DESIGN.md §13
     *  advisory disposition). Contract: every variable string inside cmds
     *  has already crossed ExcludeCodec's whitelist and shellQuote;
     *  regression-pinned in tools/run-tests.sh. */
    static synchronized boolean write(String cmds) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (suProc == null) {
                    suProc = Runtime.getRuntime().exec("su");
                    suOut = new DataOutputStream(suProc.getOutputStream());
                }
                suOut.writeBytes(cmds);
                suOut.flush();
                return true;
            } catch (Throwable t) {
                try {
                    if (suProc != null) {
                        suProc.destroy();
                    }
                } catch (Throwable ignored) {
                }
                suProc = null;
                suOut = null;
            }
        }
        return false;
    }
}
