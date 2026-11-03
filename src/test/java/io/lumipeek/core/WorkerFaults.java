package io.lumipeek.core;

import java.nio.file.Path;

/** Test classpath only. Fault opcodes are never accepted by PhotoWorkerMain. */
public final class WorkerFaults {
    private WorkerFaults() {}
    public static void main(String[] args) throws Exception {
        WorkerBridge.initialize(Path.of(args[1]));
        byte[] request = WorkerBridge.request0(Long.parseUnsignedLong(args[0]));
        switch (request[0]) {
            case 1 -> { for (;;) { try { Thread.sleep(1000); } catch (InterruptedException ignored) { } } }
            case 2 -> { byte[] huge = new byte[256 * 1024 * 1024]; System.out.write(huge[0]); }
            case 3 -> { System.out.write(commit0()); System.out.flush(); }
            case 4 -> { for (int i = 0; i < 160; i++) System.out.write(new byte[65536]); System.out.flush(); }
            case 5 -> { System.out.write(new byte[]{1, 2, 3}); System.out.flush(); }
            case 6 -> System.exit(7);
            case 7 -> { Thread.sleep(1000); System.out.write(42); System.out.flush(); }
            case 8 -> { System.out.write(126); System.out.flush(); block0(); }
            case 9 -> abort0();
            default -> throw new IllegalArgumentException();
        }
    }
    private static native int commit0();
    private static native void block0();
    private static native void abort0();
}
