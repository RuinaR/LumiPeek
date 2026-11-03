package io.lumipeek.core;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static io.lumipeek.core.Failure.Code.*;

/** Async production entry point: file access and all decoding happen in a Job-contained child. */
public final class IsolatedPhotos implements AutoCloseable {
    private final Path javaExecutable, workerClasses, bridge, files;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lumipeek-worker-supervisor"); t.setDaemon(true); return t;
    });
    private boolean busy, closed;
    private Thread running;
    private CompletableFuture<?> active;

    /** All paths must refer to trusted runtime/code, not photo settings or downloads. */
    public IsolatedPhotos(Path javaExecutable, Path workerClasses, Path bridge, Path files) throws Failure {
        for (Path p : List.of(javaExecutable, workerClasses, bridge, files)) {
            if (!p.isAbsolute() || !p.equals(p.normalize()) || p.toString().startsWith("\\\\")
                    || !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) throw new Failure(WORK_FAILED);
        }
        if (!javaExecutable.getFileName().toString().equals("java.exe")) throw new Failure(WORK_FAILED);
        this.javaExecutable = javaExecutable; this.workerClasses = workerClasses; this.bridge = bridge; this.files = files;
        WorkerBridge.initialize(bridge);
    }
    public CompletableFuture<Grant> grant(Path root, Duration timeout) throws Failure {
        absolute(root);
        long ms = budget(timeout);
        byte[] request = request(Wire.GRANT, root, null, ms, out -> {});
        return submit(request, ms, in -> new Grant(root, new long[]{in.readLong(), in.readLong(), in.readLong()}));
    }
    public final class Grant {
        private final Path root;
        private final long[] id;
        private Grant(Path root, long[] id) { this.root = root; this.id = id; }
        public CompletableFuture<List<Path>> discover(boolean recursive, int limit, Duration timeout) throws Failure {
            if (limit < 1 || limit > 100_000) throw new IllegalArgumentException("entry budget");
            long ms = budget(timeout);
            return submit(request(Wire.DISCOVER, root, id, ms, out -> { out.writeBoolean(recursive); out.writeInt(limit); }), ms, in -> {
                int count = in.readInt(); if (count < 0 || count > limit) throw new IOException();
                List<Path> paths = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    paths.add(candidate(root, Wire.string(in)));
                }
                return List.copyOf(paths);
            });
        }
        public CompletableFuture<PreparedPhoto> analyze(Path photo, Duration timeout) throws Failure {
            absolute(photo);
            if (!photo.startsWith(root) || photo.equals(root)) throw new Failure(PATH_DENIED);
            long ms = budget(timeout);
            return submit(request(Wire.ANALYZE, root, id, ms,
                out -> Wire.string(out, root.relativize(photo).toString())), ms, PreparedPhoto::read);
        }
    }
    @FunctionalInterface private interface Parser<T> { T read(DataInputStream in) throws IOException; }
    private byte[] request(int operation, Path root, long[] id, long ms, Wire.Writer rest) throws Failure {
        return Wire.write(out -> {
            out.writeInt(Wire.MAGIC); out.writeInt(operation); out.writeLong(ms);
            Wire.string(out, files.toString()); Wire.string(out, root.toString());
            if (id != null) for (long value : id) out.writeLong(value);
            rest.write(out);
        }, Wire.MAX_REQUEST);
    }
    private synchronized <T> CompletableFuture<T> submit(byte[] request, long ms, Parser<T> parser) {
        if (closed || busy) { Arrays.fill(request, (byte) 0); return CompletableFuture.failedFuture(new Failure(closed ? CANCELLED : BUSY)); }
        busy = true;
        CompletableFuture<T> answer = new CompletableFuture<>(); active = answer;
        long start = System.nanoTime();
        executor.execute(() -> {
            T value = null;
            Failure problem = null;
            try {
                synchronized (IsolatedPhotos.this) { running = Thread.currentThread(); }
                if (answer.isDone()) return;
                WorkerBridge.Result result = WorkerBridge.run(javaExecutable, workerClasses, bridge, request, ms, 0);
                try (DataInputStream in = Wire.input(result.bytes(), Wire.MAX_REPLY)) {
                    if (result.exit() != 0 || in.readInt() != Wire.MAGIC) throw new Failure(WORK_FAILED);
                    String status = Wire.string(in);
                    if (!status.equals("OK")) {
                        Wire.end(in);
                        try { throw new Failure(Failure.Code.valueOf(status)); }
                        catch (IllegalArgumentException ex) { throw new Failure(WORK_FAILED); }
                    }
                    value = parser.read(in); Wire.end(in);
                } finally { Arrays.fill(result.bytes(), (byte) 0); }
            } catch (Failure ex) { problem = ex; }
            catch (Exception | Error ex) { problem = new Failure(WORK_FAILED); }
            finally {
                Arrays.fill(request, (byte) 0);
                synchronized (IsolatedPhotos.this) {
                    running = null; active = null; busy = false;
                    // Publish only after the native child is reaped and the slot released.
                    if (!answer.isDone()) {
                        if (problem == null || problem.code() != CLEANUP_FAILED) {
                            if (closed || Thread.currentThread().isInterrupted()) problem = new Failure(CANCELLED);
                            else if (System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(ms)) problem = new Failure(TIMEOUT);
                        }
                        if (problem != null) {
                            if (value instanceof PreparedPhoto photo) photo.close();
                            answer.completeExceptionally(problem);
                        } else if (!answer.complete(value) && value instanceof PreparedPhoto photo) photo.close();
                    } else if (value instanceof PreparedPhoto photo) {
                        photo.close();
                    }
                }
            }
        });
        answer.whenComplete((value, error) -> {
            if (answer.isCancelled()) synchronized (IsolatedPhotos.this) { if (running != null && active == answer) running.interrupt(); }
        });
        return answer;
    }
    public synchronized boolean busy() { return busy; }
    public synchronized void cancel() {
        if (active != null) active.cancel(false);
        if (running != null) running.interrupt();
    }
    @Override public synchronized void close() { if (!closed) { closed = true; cancel(); executor.shutdown(); } }
    /** For non-UI shutdown/testing only. close() itself never waits on the UI thread. */
    public boolean awaitStopped(Duration timeout) throws InterruptedException {
        return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    private static void absolute(Path p) throws Failure {
        if (!p.getFileSystem().equals(FileSystems.getDefault()) || !p.isAbsolute() || !p.equals(p.normalize())) throw new Failure(PATH_DENIED);
    }
    static Path candidate(Path root, String name) throws IOException {
        Path relative = Path.of(name);
        if (relative.getRoot() != null || !relative.equals(relative.normalize())) throw new IOException();
        Path candidate = root.resolve(relative);
        if (!candidate.equals(candidate.normalize()) || !candidate.startsWith(root) || candidate.equals(root)) throw new IOException();
        return candidate;
    }
    private static long budget(Duration d) {
        if (d.isNegative() || d.isZero() || d.compareTo(Duration.ofMinutes(1)) > 0) throw new IllegalArgumentException("deadline");
        return Math.max(1, d.toMillis());
    }
}
