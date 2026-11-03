package io.lumipeek.core;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import static io.lumipeek.core.Failure.Code.*;

public final class IsolationTests {
    private static int passed;
    private static final Duration FIVE = Duration.ofSeconds(5);
    private IsolationTests() {}
    private static void check(boolean b) { if (!b) throw new AssertionError("isolation check failed"); passed++; }
    private static void failure(CompletableFuture<?> f, Failure.Code code) throws Exception {
        try { f.get(8, TimeUnit.SECONDS); throw new AssertionError("expected " + code); }
        catch (ExecutionException ex) { check(ex.getCause() instanceof Failure e && e.code() == code && e.getCause() == null); }
    }
    private static void idle(IsolatedPhotos photos) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (photos.busy() && System.nanoTime() < end) Thread.sleep(1);
        check(!photos.busy());
    }
    public static void main(String[] args) throws Exception {
        Path java = Path.of(args[0]), classes = Path.of(args[1]), production = Path.of(args[2]), test = Path.of(args[3]);
        Path root = Files.createTempDirectory(Path.of("build"), "isolation-").toAbsolutePath();
        Path photo = root.resolve("합성 사진.png");
        try (var output = Files.newOutputStream(photo)) {
            ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), "png", output);
        }
        byte[] original = Files.readAllBytes(photo);
        try {
            IsolatedPhotos photos = new IsolatedPhotos(java, classes, production.resolve("lumipeek-worker.dll"), production.resolve("lumipeek-files.dll"));
            try (photos) {
                IsolatedPhotos.Grant grant = photos.grant(root, FIVE).get(8, TimeUnit.SECONDS); idle(photos);
                check(grant.discover(true, 100, FIVE).get(8, TimeUnit.SECONDS).equals(List.of(photo))); idle(photos);
                PreparedPhoto prepared = grant.analyze(photo, FIVE).get(8, TimeUnit.SECONDS); idle(photos);
                check(prepared.width() == 40 && prepared.height() == 20);
                check(prepared.copyRgb().length == 40 * 20 * 3 && prepared.copyPng().length > 8);
                prepared.close(); prepared.close();
                try { prepared.copyRgb(); throw new AssertionError("released pixels accessible"); }
                catch (IllegalStateException expected) { passed++; }
                check(Arrays.equals(original, Files.readAllBytes(photo)));
                Path invalid = Files.writeString(root.resolve("bad.png"), "synthetic");
                failure(grant.analyze(invalid, FIVE), FORMAT_DENIED); idle(photos);
                for (int i = 0; i < 3; i++) {
                    failure(grant.analyze(photo, Duration.ofMillis(1)), TIMEOUT); idle(photos);
                    check(WorkerBridge.liveHandles0() == 0);
                }
                CompletableFuture<PreparedPhoto> cancelled = grant.analyze(photo, FIVE);
                check(grant.analyze(photo, FIVE).isCompletedExceptionally());
                cancelled.cancel(false); idle(photos);
                check(cancelled.isCancelled() && WorkerBridge.liveHandles0() == 0);
                try (PreparedPhoto recovered = grant.analyze(photo, FIVE).get(8, TimeUnit.SECONDS)) { check(recovered.width() == 40); }
                idle(photos);
            }
            check(photos.awaitStopped(Duration.ofSeconds(3)));
            check(WorkerBridge.liveHandles0() == 0);
            // Stop during startup/processing; no new work and no surviving child/owned handles.
            IsolatedPhotos stopped = new IsolatedPhotos(java, classes, production.resolve("lumipeek-worker.dll"), production.resolve("lumipeek-files.dll"));
            var pending = stopped.grant(root, FIVE); stopped.close(); stopped.close();
            check(stopped.awaitStopped(Duration.ofSeconds(4)));
            check(pending.isDone() && WorkerBridge.liveHandles0() == 0);
            failure(stopped.grant(root, FIVE), CANCELLED);
            // The test-only DLL is loaded in a fresh process to avoid mixed native library state.
            check(Arrays.equals(original, Files.readAllBytes(photo)));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        System.out.println("PASS: isolated production pipeline " + passed + " assertions");
    }
}
