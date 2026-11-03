package io.lumipeek.core;

import java.nio.file.*;
import static io.lumipeek.core.Failure.Code.PATH_DENIED;

/** Explicit JNI boundary. No extraction, download, PATH search or Java fallback. */
public final class WindowsFiles {
    private static volatile boolean ready;
    private WindowsFiles() {}
    /** Caller supplies the SDK-protected executable copy's absolute DLL path.
     * Never obtain it from photo settings. Tests supply a freshly built project DLL. */
    public static synchronized void initialize(Path trustedLibrary) throws Failure {
        if (ready) return;
        if (!System.getProperty("os.name", "").startsWith("Windows")
                || !System.getProperty("os.arch", "").equals("amd64")) throw new Failure(PATH_DENIED);
        try {
            if (!trustedLibrary.isAbsolute() || !trustedLibrary.equals(trustedLibrary.normalize())
                    || !trustedLibrary.getFileName().toString().equals("lumipeek-files.dll")
                    || !Files.isRegularFile(trustedLibrary, LinkOption.NOFOLLOW_LINKS)) throw new Failure(PATH_DENIED);
            System.load(trustedLibrary.toString());
            if (abi() != 1) throw new Failure(PATH_DENIED);
            ready = true;
        } catch (UnsatisfiedLinkError | SecurityException ex) { throw new Failure(PATH_DENIED); }
    }
    private static void requireReady() throws Failure { if (!ready) throw new Failure(PATH_DENIED); }
    static long[] identify(String root, long ms) throws Failure { requireReady(); return identify0(root, ms); }
    static String[] discover(String root, long[] id, boolean recursive, int limit, long ms) throws Failure {
        requireReady(); return discover0(root, id, recursive, limit, ms);
    }
    static byte[] read(String root, long[] id, String relative, long ms) throws Failure {
        requireReady(); return read0(root, id, relative, ms);
    }
    private static native int abi();
    private static native long[] identify0(String root, long ms) throws Failure;
    private static native String[] discover0(String root, long[] id, boolean recursive, int limit, long ms) throws Failure;
    private static native byte[] read0(String root, long[] id, String relative, long ms) throws Failure;
}
