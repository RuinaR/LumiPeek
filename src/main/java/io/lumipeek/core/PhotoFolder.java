package io.lumipeek.core;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static io.lumipeek.core.Failure.Code.*;

/** A grant binds a local folder's native identity, never just its pathname. */
public final class PhotoFolder {
    public static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final Duration DEFAULT_BUDGET = Duration.ofSeconds(2);
    private final Path root;
    private final long[] identity;
    public PhotoFolder(Path granted) throws Failure {
        root = absolute(granted);
        identity = WindowsFiles.identify(root.toString(), millis(DEFAULT_BUDGET));
    }
    private static Path absolute(Path p) throws Failure {
        if (p == null || !p.getFileSystem().equals(FileSystems.getDefault())
                || !p.isAbsolute() || !p.equals(p.normalize())) throw new Failure(PATH_DENIED);
        return p;
    }
    public List<Path> discover(boolean recursive, int maxEntries) throws Failure {
        return discover(recursive, maxEntries, DEFAULT_BUDGET);
    }
    public List<Path> discover(boolean recursive, int maxEntries, Duration budget) throws Failure {
        if (maxEntries < 1 || maxEntries > 100_000) throw new IllegalArgumentException("entry budget");
        String[] names = WindowsFiles.discover(root.toString(), identity, recursive, maxEntries, millis(budget));
        List<Path> paths = new ArrayList<>(names.length);
        for (String name : names) paths.add(root.resolve(name));
        return List.copyOf(paths);
    }
    public byte[] read(Path p) throws Failure { return read(p, DEFAULT_BUDGET); }
    public byte[] read(Path p, Duration budget) throws Failure {
        absolute(p);
        if (!p.startsWith(root) || p.equals(root)) throw new Failure(PATH_DENIED);
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg"))) throw new Failure(FORMAT_DENIED);
        return WindowsFiles.read(root.toString(), identity, root.relativize(p).toString(), millis(budget));
    }
    private static long millis(Duration budget) {
        Objects.requireNonNull(budget);
        if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("I/O budget");
        return Math.max(1, budget.toMillis());
    }
}
