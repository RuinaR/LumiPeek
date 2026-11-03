package io.lumipeek.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import static io.lumipeek.core.Failure.Code.*;

/** Explicitly granted local folder; never follows links or writes source files. */
public final class PhotoFolder {
    public static final int MAX_BYTES = 25 * 1024 * 1024;
    private final Path root;
    private final Object rootKey;

    public PhotoFolder(Path granted) throws Failure {
        try {
            if (!granted.isAbsolute() || !granted.equals(granted.normalize())) throw new Failure(PATH_DENIED);
            root = granted;
            checkChain(root);
            BasicFileAttributes attrs = attrs(root);
            if (!attrs.isDirectory() || attrs.fileKey() == null) throw new Failure(PATH_DENIED);
            rootKey = attrs.fileKey();
        } catch (IOException | SecurityException ex) { throw new Failure(PATH_DENIED); }
    }

    private static BasicFileAttributes attrs(Path p) throws IOException {
        return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static void checkChain(Path path) throws IOException, Failure {
        // UNC, mapped network drives, junctions and mount crossings fail closed.
        if (!path.getFileSystem().equals(FileSystems.getDefault()) || path.toString().startsWith("\\\\"))
            throw new Failure(PATH_DENIED);
        Path current = path.getRoot();
        FileStore base = Files.getFileStore(current);
        if (Set.of("smb", "smbfs", "cifs", "nfs", "nfs4").contains(base.type().toLowerCase(Locale.ROOT)))
            throw new Failure(PATH_DENIED);
        for (Path part : path) {
            current = current.resolve(part);
            BasicFileAttributes a = attrs(current);
            if (a.isSymbolicLink() || a.isOther() || !current.toRealPath().equals(current))
                throw new Failure(LINK_DENIED);
            if (!base.equals(Files.getFileStore(current))) throw new Failure(PATH_DENIED);
        }
    }

    private BasicFileAttributes validate(Path p) throws IOException, Failure {
        if (!p.isAbsolute() || !p.equals(p.normalize()) || !p.startsWith(root) || p.equals(root))
            throw new Failure(PATH_DENIED);
        checkChain(p);
        if (!Objects.equals(rootKey, attrs(root).fileKey())) throw new Failure(PATH_DENIED);
        BasicFileAttributes a = attrs(p);
        if (!a.isRegularFile() || a.fileKey() == null) throw new Failure(PATH_DENIED);
        if (!supported(p)) throw new Failure(FORMAT_DENIED);
        if (a.size() <= 0 || a.size() > MAX_BYTES) throw new Failure(FILE_LIMIT);
        return a;
    }

    private static boolean supported(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    /** Bounded traversal; no partial result if enumeration exceeds the budget. */
    public List<Path> discover(boolean recursive, int maxEntries) throws Failure {
        if (maxEntries < 1 || maxEntries > 100_000) throw new IllegalArgumentException("entry budget");
        try {
            checkChain(root);
            if (!Objects.equals(rootKey, attrs(root).fileKey())) throw new Failure(PATH_DENIED);
            List<Path> found = new ArrayList<>();
            Deque<Path> pending = new ArrayDeque<>();
            pending.add(root);
            int visited = 0;
            while (!pending.isEmpty()) {
                Path dir = pending.removeFirst();
                checkChain(dir);
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                    for (Path p : entries) {
                        if (Thread.currentThread().isInterrupted()) throw new Failure(CANCELLED);
                        if (++visited > maxEntries) throw new Failure(FILE_LIMIT);
                        BasicFileAttributes a = attrs(p);
                        if (a.isSymbolicLink() || a.isOther()) continue;
                        checkChain(p);
                        if (a.isDirectory() && recursive) pending.addLast(p);
                        else if (a.isRegularFile() && supported(p) && a.size() > 0 && a.size() <= MAX_BYTES)
                            found.add(p);
                    }
                }
            }
            return List.copyOf(found);
        } catch (IOException | SecurityException ex) { throw new Failure(PATH_DENIED); }
    }

    /**
     * Checks before/after opening and reads a bounded snapshot. Parent-directory
     * replacement races cannot be eliminated by portable Java on Windows. This
     * is a P1 prototype, not a hardened filesystem sandbox (see README).
     */
    public byte[] read(Path p) throws Failure {
        try {
            BasicFileAttributes before = validate(p);
            try (SeekableByteChannel channel = Files.newByteChannel(p,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                BasicFileAttributes opened = validate(p);
                if (!same(before, opened) || channel.size() != before.size()) throw new Failure(PATH_DENIED);
                ByteBuffer data = ByteBuffer.allocate((int) before.size());
                while (data.hasRemaining()) {
                    if (Thread.currentThread().isInterrupted()) throw new Failure(CANCELLED);
                    if (channel.read(data) < 0) throw new Failure(FILE_LIMIT);
                }
                if (channel.read(ByteBuffer.allocate(1)) != -1 || !same(before, validate(p)))
                    throw new Failure(PATH_DENIED);
                return data.array();
            }
        } catch (IOException | SecurityException | UnsupportedOperationException ex) {
            throw new Failure(PATH_DENIED);
        }
    }

    private static boolean same(BasicFileAttributes a, BasicFileAttributes b) {
        return Objects.equals(a.fileKey(), b.fileKey()) && a.size() == b.size()
                && a.lastModifiedTime().equals(b.lastModifiedTime());
    }
}
