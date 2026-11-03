package io.lumipeek.core;

import java.nio.file.*;
import java.util.concurrent.*;
import static io.lumipeek.core.Failure.Code.*;

public final class WorkerFaultTests {
    private static int passed;
    private WorkerFaultTests() {}
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static void check(boolean b) { if (!b) throw new AssertionError("worker fault check failed"); passed++; }
    private static void failure(Failure.Code code, Checked action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + code); }
        catch (Failure e) { check(e.code() == code); }
        check(WorkerBridge.liveHandles0() == 0);
    }
    public static void main(String[] args) throws Exception {
        Path java = Path.of(args[0]), classes = Path.of(args[1]), dll = Path.of(args[2]);
        WorkerBridge.initialize(dll);
        for (int i = 0; i < 3; i++) {
            failure(TIMEOUT, () -> WorkerBridge.run(java, classes, dll, new byte[]{1}, 1000, 1));
            failure(TIMEOUT, () -> WorkerBridge.run(java, classes, dll, new byte[]{7}, 500, 1));
        }
        failure(TIMEOUT, () -> WorkerBridge.run(java, classes, dll, new byte[]{8}, 1000, 1));
        check(lastOutput0() == 1); // confirms the native wait was reached, not merely slow JVM startup
        var oom = WorkerBridge.run(java, classes, dll, new byte[]{2}, 5000, 1);
        check(oom.exit() != 0 && oom.peakCommit() <= oom.commitLimit());
        check(WorkerBridge.liveHandles0() == 0);
        long[] available = WorkerBridge.available0();
        if (available[0] >= 1536L * 1024 * 1024 && available[1] >= 1536L * 1024 * 1024) {
            var memory = WorkerBridge.run(java, classes, dll, new byte[]{3}, 5000, 1);
            check(memory.exit() == 0 && memory.bytes().length == 1 && memory.bytes()[0] > 0);
            check(memory.commitLimit() == 384L * 1024 * 1024);
            check(memory.processLimit() == 256L * 1024 * 1024);
            System.out.println("MEASURED: job limit=" + memory.commitLimit() + " peak=" + memory.peakCommit() + " native blocks=" + memory.bytes()[0]);
            check(memory.peakCommit() > 240L * 1024 * 1024 && memory.peakCommit() <= memory.commitLimit());
        } else System.out.println("BLOCKED: native commit-pressure fixture needs 1.5GiB physical and commit headroom");
        failure(WORK_FAILED, () -> WorkerBridge.run(java, classes, dll, new byte[]{4}, 5000, 1));
        var malformed = WorkerBridge.run(java, classes, dll, new byte[]{5}, 5000, 1);
        check(malformed.bytes().length == 3 && malformed.exit() == 0);
        var crash = WorkerBridge.run(java, classes, dll, new byte[]{6}, 5000, 1);
        check(crash.exit() == 7 && WorkerBridge.liveHandles0() == 0);
        var abrupt = WorkerBridge.run(java, classes, dll, new byte[]{9}, 5000, 1);
        check(abrupt.exit() == 9 && WorkerBridge.liveHandles0() == 0);
        // Interrupt a thread owned by this test; the worker never cooperates with Java interrupt.
        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try { WorkerBridge.run(java, classes, dll, new byte[]{1}, 5000, 1); done.completeExceptionally(new AssertionError()); }
            catch (Failure e) { if (e.code() == CANCELLED) done.complete(null); else done.completeExceptionally(e); }
        }, "lumipeek-job-cancel-test");
        thread.start();
        long started = System.nanoTime();
        while (WorkerBridge.liveHandles0() < 3 && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2)) Thread.sleep(1);
        check(WorkerBridge.liveHandles0() >= 3);
        try {
            WorkerBridge.run(java, classes, dll, new byte[]{5}, 5000, 1);
            throw new AssertionError("concurrent worker admitted");
        } catch (Failure ex) { check(ex.code() == BUSY); }
        thread.interrupt(); done.get(4, TimeUnit.SECONDS); thread.join(2000);
        check(!thread.isAlive() && WorkerBridge.liveHandles0() == 0);
        System.out.println("PASS: worker fault/OS-limit " + passed + " assertions");
    }
    private static native long lastOutput0();
}
