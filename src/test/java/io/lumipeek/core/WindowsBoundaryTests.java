package io.lumipeek.core;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import static io.lumipeek.core.Failure.Code.*;

public final class WindowsBoundaryTests {
    private static int passed;
    private static final List<Path> junctions = new ArrayList<>();
    private static final Duration SECOND = Duration.ofSeconds(1);
    private WindowsBoundaryTests() {}
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static void check(boolean b) { if (!b) throw new AssertionError("boundary check failed"); passed++; }
    private static void denied(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("unsafe access accepted"); }
        catch (Failure ex) { check(Set.of(PATH_DENIED, LINK_DENIED).contains(ex.code())); }
    }
    private static void failure(Failure.Code code, Checked action) throws Exception {
        try { action.run(); throw new AssertionError("expected " + code); }
        catch (Failure ex) { check(ex.code() == code && ex.getCause() == null && ex.getMessage().equals(code.name())); }
    }
    private static byte[] png() throws IOException {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        check(java.awt.GraphicsEnvironment.isHeadless());
        WindowsFiles.initialize(Path.of(args[0]));
        Path sandbox = Files.createTempDirectory(Path.of("build"), "windows-").toAbsolutePath();
        check(WindowsFixtures.register0(sandbox.toString()));
        try {
            normalAndDenied(sandbox);
            races(sandbox);
            discoveryChanges(sandbox);
        } finally {
            // Remove known reparse fixtures first. Never recursively walk through a junction.
            for (Path p : junctions) if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) Files.delete(p);
            try (var paths = Files.walk(sandbox)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        System.out.println("PASS: Windows boundary " + passed + " assertions (local synthetic NTFS fixtures)");
        System.out.println("NOT VERIFIED: live OneDrive/SMB, filesystem filter drivers, hostile kernel, hard syscall deadlines, SDK25 package loading");
    }
    private static boolean junction(Path link, Path target) {
        junctions.add(link);
        return WindowsFixtures.junction0(link.toString(), target.toString());
    }
    private static void normalAndDenied(Path sandbox) throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("normal"));
        Path nested = Files.createDirectory(root.resolve("nested"));
        byte[] original = png();
        Path photo = Files.write(root.resolve("image.png"), original);
        Path nestedPhoto = Files.write(nested.resolve("한글 사진.png"), original);
        PhotoFolder folder = new PhotoFolder(root);
        check(Arrays.equals(folder.read(photo), original));
        check(Arrays.equals(folder.read(nestedPhoto), original));
        check(folder.discover(false, 100).equals(List.of(photo)));
        check(new HashSet<>(folder.discover(true, 100)).equals(Set.of(photo, nestedPhoto)));
        failure(FILE_LIMIT, () -> folder.discover(true, 1));
        failure(PATH_DENIED, () -> folder.read(root.resolve("missing.png")));
        Path fake = Files.writeString(root.resolve("fake.png"), "synthetic nonimage");
        failure(FORMAT_DENIED, () -> folder.read(fake));
        denied(() -> folder.read(sandbox.resolve("other.png")));
        denied(() -> folder.read(root.resolve("../other.png")));
        denied(() -> new PhotoFolder(Path.of("\\\\invalid.invalid\\share\\photos")));
        denied(() -> new PhotoFolder(Path.of("\\\\?\\C:\\photos")));
        denied(() -> new PhotoFolder(Path.of("C:\\")));
        denied(() -> new PhotoFolder(Path.of("relative")));
        Path hard = Files.createLink(root.resolve("hard.png"), photo);
        denied(() -> folder.read(photo)); denied(() -> folder.read(hard));
        check(!folder.discover(true, 100).contains(hard));
        Files.delete(hard);
        try (FileChannel writer = FileChannel.open(photo, StandardOpenOption.WRITE)) {
            check(writer.isOpen()); denied(() -> folder.read(photo));
        }
        check(Arrays.equals(folder.read(photo), original));
        AclFileAttributeView view = Files.getFileAttributeView(photo, AclFileAttributeView.class);
        List<AclEntry> saved = view.getAcl();
        AclEntry deny = AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(Files.getOwner(photo))
                .setPermissions(AclEntryPermission.READ_DATA).build();
        try {
            List<AclEntry> acl = new ArrayList<>(); acl.add(deny); acl.addAll(saved); view.setAcl(acl);
            denied(() -> folder.read(photo));
        } finally { view.setAcl(saved); }
        check(WindowsFixtures.offline0(photo.toString(), true));
        try { denied(() -> folder.read(photo)); }
        finally { check(WindowsFixtures.offline0(photo.toString(), false)); }
        check(Arrays.equals(Files.readAllBytes(photo), original));
        Path outside = Files.createDirectory(sandbox.resolve("outside"));
        Files.write(outside.resolve("outside.png"), original);
        Path alias = root.resolve("junction");
        if (junction(alias, outside)) {
            denied(() -> folder.read(alias.resolve("outside.png")));
            denied(() -> new PhotoFolder(alias));
            check(folder.discover(true, 100).stream().noneMatch(p -> p.startsWith(alias)));
        } else System.out.println("NOT VERIFIED: junction fixture creation unavailable");
        Path link = root.resolve("symlink.png");
        try {
            Files.createSymbolicLink(link, outside.resolve("outside.png"));
            denied(() -> folder.read(link));
            check(!folder.discover(true, 100).contains(link));
        } catch (FileSystemException | UnsupportedOperationException | SecurityException ex) {
            System.out.println("NOT VERIFIED: symlink privilege unavailable");
        }
        // Root replacement between operations must revoke the old grant.
        Path bind = Files.createDirectory(sandbox.resolve("bound"));
        Files.write(bind.resolve("photo.png"), original);
        PhotoFolder bound = new PhotoFolder(bind);
        Files.move(bind, sandbox.resolve("old-bound"));
        Files.createDirectory(bind); Files.write(bind.resolve("photo.png"), original);
        denied(() -> bound.read(bind.resolve("photo.png")));
        denied(() -> bound.discover(true, 100));
        check(Arrays.equals(new PhotoFolder(bind).read(bind.resolve("photo.png")), original));
        Thread.currentThread().interrupt();
        try {
            failure(CANCELLED, () -> folder.read(photo));
            failure(CANCELLED, () -> folder.discover(true, 100));
            failure(CANCELLED, () -> new PhotoFolder(root));
        } finally { Thread.interrupted(); }
    }
    private static void futureFailure(Future<?> future, Failure.Code code) throws Exception {
        try { future.get(3, TimeUnit.SECONDS); throw new AssertionError("expected asynchronous failure"); }
        catch (ExecutionException ex) { check(ex.getCause() instanceof Failure f && f.code() == code); }
    }
    private static void races(Path sandbox) throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("races"));
        Path child = Files.createDirectory(root.resolve("child"));
        Path photo = Files.write(child.resolve("photo.png"), png());
        PhotoFolder folder = new PhotoFolder(root);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            // A competing rename after the handle is opened must be denied by sharing rules.
            for (int stage : List.of(1, 2, 3)) {
                WindowsFixtures.arm0(stage);
                Future<byte[]> read = worker.submit(() -> folder.read(photo));
                try {
                    check(WindowsFixtures.await0());
                    Path pinned = stage == 1 ? root : stage == 2 ? child : photo;
                    try { Files.move(pinned, pinned.resolveSibling("renamed")); throw new AssertionError("pinned name moved"); }
                    catch (FileSystemException ex) { passed++; }
                    if (stage == 3) {
                        try (FileChannel ignored = FileChannel.open(photo, StandardOpenOption.WRITE)) {
                            check(!ignored.isOpen());
                        } catch (FileSystemException ex) { passed++; }
                        Path sibling = Files.writeString(sandbox.resolve("sibling.txt"), "synthetic");
                        Files.move(sibling, sandbox.resolve("sibling-moved.txt"));
                        check(Files.exists(sandbox.resolve("sibling-moved.txt")));
                    }
                } finally { WindowsFixtures.release0(); }
                check(Arrays.equals(read.get(3, TimeUnit.SECONDS), png()));
            }
            // Swap an unopened child to an outside junction while root is pinned.
            Path outside = Files.createDirectory(sandbox.resolve("race-outside"));
            Files.write(outside.resolve("photo.png"), png());
            WindowsFixtures.arm0(1);
            Future<byte[]> redirected = worker.submit(() -> folder.read(photo));
            boolean made = false, mutationBlocked = false;
            try {
                check(WindowsFixtures.await0());
                try {
                    Files.move(child, root.resolve("old-child"));
                    made = junction(child, outside);
                } catch (FileSystemException ex) { mutationBlocked = true; }
            } finally { WindowsFixtures.release0(); }
            check(!mutationBlocked); // A root pin must not block unrelated child-name mutations.
            if (mutationBlocked) {
                check(Arrays.equals(redirected.get(3, TimeUnit.SECONDS), png()));
            } else if (made) {
                try { redirected.get(3, TimeUnit.SECONDS); throw new AssertionError("junction race escaped"); }
                catch (ExecutionException ex) { check(ex.getCause() instanceof Failure); }
            } else {
                System.out.println("NOT VERIFIED: junction replacement race fixture unavailable");
                try { redirected.get(3, TimeUnit.SECONDS); } catch (ExecutionException ignored) { }
            }
            Path valid = mutationBlocked ? photo : root.resolve("old-child/photo.png");
            WindowsFixtures.arm0(3);
            Future<byte[]> timeout = worker.submit(() -> folder.read(valid, Duration.ofMillis(25)));
            try { check(WindowsFixtures.await0()); Thread.sleep(40); }
            finally { WindowsFixtures.release0(); }
            futureFailure(timeout, TIMEOUT);
            // Interrupt only our own test worker while it is inside the JNI call.
            WindowsFixtures.arm0(3);
            CompletableFuture<byte[]> answer = new CompletableFuture<>();
            Thread owned = new Thread(() -> {
                try { answer.complete(folder.read(valid, SECOND)); }
                catch (Failure ex) { answer.completeExceptionally(ex); }
            }, "lumipeek-native-cancel-test");
            owned.start();
            try { check(WindowsFixtures.await0()); owned.interrupt(); }
            finally { WindowsFixtures.release0(); }
            futureFailure(answer, CANCELLED);
            owned.join(2000); check(!owned.isAlive());
            // All handles must be gone after failure/cancellation.
            Files.move(valid, valid.resolveSibling("released.png"));
            check(Files.exists(valid.resolveSibling("released.png")));
        } finally {
            WindowsFixtures.release0(); worker.shutdownNow();
            check(worker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
    private static void discoveryChanges(Path sandbox) throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("discovery"));
        Path image = Files.write(root.resolve("photo.png"), png());
        PhotoFolder folder = new PhotoFolder(root);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            WindowsFixtures.arm0(5);
            Future<List<Path>> deletion = worker.submit(() -> folder.discover(false, 100));
            try { check(WindowsFixtures.await0()); Files.delete(image); }
            finally { WindowsFixtures.release0(); }
            check(deletion.get(3, TimeUnit.SECONDS).isEmpty());
            Files.write(image, png());
            Path outside = Files.write(sandbox.resolve("outside-hardlink.png"), png());
            WindowsFixtures.arm0(5);
            Future<List<Path>> replacement = worker.submit(() -> folder.discover(false, 100));
            try {
                check(WindowsFixtures.await0()); Files.delete(image); Files.createLink(image, outside);
            } finally { WindowsFixtures.release0(); }
            check(replacement.get(3, TimeUnit.SECONDS).isEmpty());
            denied(() -> folder.read(image));
            Files.delete(image); Files.write(image, png());
            WindowsFixtures.arm0(4);
            Future<List<Path>> timeout = worker.submit(() -> folder.discover(false, 100, Duration.ofMillis(25)));
            try { check(WindowsFixtures.await0()); Thread.sleep(40); }
            finally { WindowsFixtures.release0(); }
            futureFailure(timeout, TIMEOUT);
            WindowsFixtures.arm0(4);
            CompletableFuture<List<Path>> answer = new CompletableFuture<>();
            Thread owned = new Thread(() -> {
                try { answer.complete(folder.discover(false, 100)); }
                catch (Failure ex) { answer.completeExceptionally(ex); }
            }, "lumipeek-native-discovery-cancel-test");
            owned.start();
            try { check(WindowsFixtures.await0()); owned.interrupt(); }
            finally { WindowsFixtures.release0(); }
            futureFailure(answer, CANCELLED);
            owned.join(2000); check(!owned.isAlive());
            check(folder.discover(false, 100).equals(List.of(image)));
            // Grant-root locks must be released on successful and failed enumeration.
            Files.move(root, sandbox.resolve("discovery-released")); passed++;
        } finally {
            WindowsFixtures.release0(); worker.shutdownNow();
            check(worker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
