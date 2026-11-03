package io.lumipeek.core;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import static io.lumipeek.core.Failure.Code.*;

/** Synthetic inputs only; no SDK backend, network, model, windows or desktop input. */
public final class CoreTests {
    private static int passed;
    private static final Duration SECOND = Duration.ofSeconds(1);
    private CoreTests() {}
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static void check(boolean b) { if (!b) throw new AssertionError("check failed"); passed++; }
    private static void failure(Failure.Code code, Checked action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + code); }
        catch (Failure ex) { check(ex.code() == code && ex.getMessage().equals(code.name()) && ex.getCause() == null); }
    }
    private static byte[] image(String format, int w, int h) throws IOException {
        var output = new ByteArrayOutputStream();
        check(ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), format, output));
        return output.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        check(java.awt.GraphicsEnvironment.isHeadless());
        Path sandbox = Files.createTempDirectory(Path.of("build"), "synthetic-").toAbsolutePath();
        try {
            folders(sandbox);
            decoding();
            reactions();
            queue();
        } finally {
            // Delete only the uniquely created synthetic test subtree, without following links.
            try (var paths = Files.walk(sandbox)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        System.out.println("PASS: " + passed + " assertions (headless, synthetic data)");
    }
    private static void folders(Path temp) throws Exception {
        Path root = Files.createDirectory(temp.resolve("granted"));
        Path outside = temp.resolve("outside.png");
        byte[] original = image("png", 20, 10);
        Files.write(outside, original);
        Path photo = Files.write(root.resolve("one.PNG"), original);
        Files.writeString(root.resolve("secret.txt"), "synthetic");
        Files.write(Files.createDirectory(root.resolve("nested")).resolve("two.jpg"), image("jpeg", 8, 8));
        if (Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey() == null) {
            failure(PATH_DENIED, () -> new PhotoFolder(root));
            failure(PATH_DENIED, () -> new PhotoFolder(Path.of("relative")));
            System.out.println("BLOCKED: filesystem supplies no stable fileKey; folder access fails closed");
            System.out.println("NOT VERIFIED: traversal/read/link fixtures require a supported filesystem");
            return;
        }
        PhotoFolder folder = new PhotoFolder(root);
        check(folder.discover(false, 20).equals(List.of(photo)));
        check(folder.discover(true, 20).size() == 2);
        check(Arrays.equals(original, folder.read(photo)));
        check(Arrays.equals(original, Files.readAllBytes(photo)));
        failure(PATH_DENIED, () -> folder.read(outside));
        failure(PATH_DENIED, () -> folder.read(root.resolve("../outside.png")));
        failure(PATH_DENIED, () -> folder.read(Path.of("one.PNG")));
        failure(PATH_DENIED, () -> new PhotoFolder(Path.of("relative")));
        failure(FORMAT_DENIED, () -> folder.read(root.resolve("secret.txt")));
        failure(FILE_LIMIT, () -> folder.discover(true, 1));
        Path large = root.resolve("huge.jpg");
        try (var file = new RandomAccessFile(large.toFile(), "rw")) { file.setLength(PhotoFolder.MAX_BYTES + 1L); }
        failure(FILE_LIMIT, () -> folder.read(large));
        Files.delete(large);
        Path link = root.resolve("link.png");
        try {
            Files.createSymbolicLink(link, outside);
            failure(LINK_DENIED, () -> folder.read(link));
            check(!folder.discover(true, 20).contains(link));
            Path dirLink = root.resolve("linked-dir");
            Files.createSymbolicLink(dirLink, temp);
            failure(LINK_DENIED, () -> new PhotoFolder(dirLink));
            check(folder.discover(true, 20).size() == 2);
        } catch (UnsupportedOperationException | FileSystemException | SecurityException ex) {
            System.out.println("NOT VERIFIED: symbolic-link creation unavailable; junction/mapped-drive checks need platform fixtures");
        }
        Files.delete(photo);
        failure(PATH_DENIED, () -> folder.read(photo));
        Files.move(root, temp.resolve("moved"));
        Files.createDirectory(root);
        Files.write(root.resolve("one.PNG"), original);
        failure(PATH_DENIED, () -> folder.read(root.resolve("one.PNG")));
    }
    private static void decoding() throws Exception {
        PhotoDecoder d = new PhotoDecoder();
        byte[] png = image("png", 1600, 800);
        byte[] clean = d.decode(png, "png", SECOND.multipliedBy(5));
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(clean));
        check(output.getWidth() <= 1024 && output.getHeight() <= 1024);
        check(output.getWidth() == output.getHeight() * 2);
        check(d.decode(image("jpeg", 30, 20), "jpg", SECOND).length > 0);
        PhotoIdentity identity = new PhotoIdentity();
        check(identity.identify(png).equals(identity.identify(png.clone())));
        check(!identity.identify(png).equals(identity.identify(clean)));
        check(!identity.identify(png).equals(new PhotoIdentity().identify(png)));
        failure(FORMAT_DENIED, () -> d.decode(png, "jpg", SECOND));
        failure(FORMAT_DENIED, () -> d.decode(new byte[]{1, 2, 3}, "png", SECOND));
        failure(FORMAT_DENIED, () -> d.decode(png, "webp", SECOND));
        failure(FILE_LIMIT, () -> d.decode(new byte[0], "png", SECOND));
        failure(DECODE_FAILED, () -> d.decode(Arrays.copyOf(png, 12), "png", SECOND));
        failure(TIMEOUT, () -> d.decode(png, "png", Duration.ofNanos(1)));
        Thread.currentThread().interrupt();
        try { failure(CANCELLED, () -> d.decode(png, "png", SECOND)); }
        finally { Thread.interrupted(); }
        // Patch only the synthetic PNG IHDR, before any raster allocation.
        byte[] oversized = png.clone();
        java.nio.ByteBuffer.wrap(oversized, 16, 8).putInt(6000).putInt(6000);
        failure(PIXEL_LIMIT, () -> d.decode(oversized, "png", SECOND));
    }
    private static String json(String text, boolean safe) {
        return "{\"reaction\":\"" + text + "\",\"observation\":\"작은 꽃이 보여\",\"tone\":\"calm\",\"safeToShow\":" + safe + "}";
    }
    private static void reactions() throws Exception {
        check(Reaction.parse(json("예쁜 꽃이네", true)).orElseThrow().safeToShow());
        check(Reaction.parse(json("", false)).isEmpty());
        for (String invalid : List.of("{}", json("ok", true) + " extra", "```" + json("ok", true),
                json("x".repeat(121), true), json("<html>", true), json("https://example.invalid", true),
                json("line\\nline", true), json("\\ud800", true),
                json("ok", true).replace("\"calm\"", "\"angry\""),
                json("ok", true).replace("\"safeToShow\":true", "\"safeToShow\":\"true\""),
                json("ok", true).replace("\"observation\"", "\"reaction\""),
                json("ok", true).replace("}", ",\"extra\":true}"))) {
            failure(RESPONSE_INVALID, () -> Reaction.parse(invalid));
        }
        failure(RESPONSE_INVALID, () -> Reaction.parse(null));
        failure(RESPONSE_INVALID, () -> Reaction.parse("x".repeat(4097)));
        check(Reaction.SYSTEM_PROMPT.contains("untrusted"));
    }
    private static void queueFailure(CompletableFuture<?> f, Failure.Code code) throws Exception {
        try { f.get(2, TimeUnit.SECONDS); throw new AssertionError("expected worker failure"); }
        catch (ExecutionException ex) { check(ex.getCause() instanceof Failure failure && failure.code() == code); }
    }
    private static void idle(AnalysisQueue q) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (q.busy() && System.nanoTime() < end) Thread.sleep(1);
        check(!q.busy());
    }
    private static void queue() throws Exception {
        try (AnalysisQueue q = new AnalysisQueue(Duration.ofMinutes(1))) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            UUID id = UUID.randomUUID();
            var first = q.submit(id, () -> { started.countDown(); release.await(); return "ok"; }, SECOND).orElseThrow();
            check(started.await(1, TimeUnit.SECONDS));
            check(q.submit(UUID.randomUUID(), () -> "other", SECOND).isEmpty());
            release.countDown(); check(first.get(2, TimeUnit.SECONDS).equals("ok")); idle(q);
            check(q.submit(id, () -> "duplicate", SECOND).isEmpty());
            queueFailure(q.submit(UUID.randomUUID(), () -> { throw new IOException("private synthetic path"); }, SECOND).orElseThrow(), WORK_FAILED);
            idle(q);
            queueFailure(q.submit(UUID.randomUUID(), () -> { throw new AssertionError("synthetic worker error"); }, SECOND).orElseThrow(), WORK_FAILED);
            idle(q);
            CountDownLatch timeoutStarted = new CountDownLatch(1), unblock = new CountDownLatch(1);
            var timed = q.submit(UUID.randomUUID(), () -> {
                timeoutStarted.countDown();
                // Emulate an uncooperative decoder: no replacement job may start.
                for (;;) { try { unblock.await(); break; } catch (InterruptedException ignored) { } }
                return "late";
            }, Duration.ofMillis(100)).orElseThrow();
            check(timeoutStarted.await(1, TimeUnit.SECONDS)); queueFailure(timed, TIMEOUT);
            check(q.busy()); check(q.submit(UUID.randomUUID(), () -> "overlap", SECOND).isEmpty());
            unblock.countDown(); idle(q); queueFailure(timed, TIMEOUT);
            CountDownLatch cancelStarted = new CountDownLatch(1);
            var cancelled = q.submit(UUID.randomUUID(), () -> { cancelStarted.countDown(); new CountDownLatch(1).await(); return "late"; }, SECOND).orElseThrow();
            check(cancelStarted.await(1, TimeUnit.SECONDS)); q.cancel(); queueFailure(cancelled, CANCELLED); idle(q);
            check(q.submit(UUID.randomUUID(), () -> "recovered", SECOND).orElseThrow().get(2, TimeUnit.SECONDS).equals("recovered"));
            idle(q);
        }
        AnalysisQueue closed = new AnalysisQueue(Duration.ZERO);
        closed.close(); closed.close();
        check(closed.submit(UUID.randomUUID(), () -> "closed", SECOND).isEmpty());
    }
}
