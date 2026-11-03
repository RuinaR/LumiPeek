package io.lumipeek.core;

import java.nio.*;
import java.nio.file.*;

/** Trusted runtime paths only. This bridge creates and controls its own child exclusively. */
final class WorkerBridge {
    private static Path loaded;
    private WorkerBridge() {}
    static synchronized void initialize(Path dll) throws Failure {
        if (loaded != null) {
            if (!loaded.equals(dll)) throw new Failure(Failure.Code.WORK_FAILED);
            return;
        }
        if (!System.getProperty("os.name", "").startsWith("Windows")
                || !System.getProperty("os.arch", "").equals("amd64")
                || !dll.isAbsolute() || !dll.equals(dll.normalize())
                || !dll.getFileName().toString().equals("lumipeek-worker.dll")
                || !Files.isRegularFile(dll, LinkOption.NOFOLLOW_LINKS)) throw new Failure(Failure.Code.WORK_FAILED);
        try { System.load(dll.toString()); }
        catch (LinkageError | SecurityException ex) { throw new Failure(Failure.Code.WORK_FAILED); }
        loaded = dll;
    }
    record Result(long exit, long commitLimit, long peakCommit, long processLimit, byte[] bytes) {}
    static Result run(Path javaExecutable, Path classes, Path bridge, byte[] request, long ms, int fixture) throws Failure {
        byte[] data = run0(javaExecutable.toString(), classes.toString(), bridge.toString(), request, ms, fixture);
        if (data == null || data.length < 32 || data.length > Wire.MAX_REPLY + 32) throw new Failure(Failure.Code.WORK_FAILED);
        ByteBuffer frame = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long exit = frame.getLong(), limit = frame.getLong(), peak = frame.getLong(), processLimit = frame.getLong();
        byte[] response = new byte[frame.remaining()]; frame.get(response);
        java.util.Arrays.fill(data, (byte) 0);
        return new Result(exit, limit, peak, processLimit, response);
    }
    static native byte[] run0(String java, String classes, String dll, byte[] request, long ms, int fixture) throws Failure;
    static native byte[] request0(long handle) throws Failure;
    static native int liveHandles0();
    static native long[] available0();
}
