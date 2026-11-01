package cetushome.hook;

import java.io.DataOutputStream;

/** One persistent su shell per app process: commands stream into it without
 *  paying a process spawn per change. A dead shell is respawned once inside
 *  the same write; `settings put` is silent on success, so no output
 *  draining is needed for these few-hundred-byte batches. */
final class RootWriter {

    private static Process suProc;
    private static DataOutputStream suOut;

    private RootWriter() {
    }

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
