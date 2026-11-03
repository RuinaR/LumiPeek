package io.lumipeek.sdk;

import io.lumipeek.core.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Test seam for the existing Windows worker, not an alternative path-validation implementation. */
interface PhotoSource extends AutoCloseable {
    interface Folder {
        CompletableFuture<List<Path>> discover(boolean recursive) throws Failure;
        CompletableFuture<PreparedPhoto> prepare(Path photo) throws Failure;
    }
    CompletableFuture<Folder> grant(Path path) throws Failure;
    void cancel();
    @Override void close();
    static PhotoSource production() throws Failure {
        try {
            Path jar = Path.of(LumiPeekPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path nativeDir = jar.getParent().resolve("native");
            IsolatedPhotos worker = new IsolatedPhotos(Path.of(System.getProperty("java.home"),"bin","java.exe").toAbsolutePath().normalize(),
                jar, nativeDir.resolve("lumipeek-worker.dll"), nativeDir.resolve("lumipeek-files.dll"));
            return new PhotoSource() {
                @Override public CompletableFuture<Folder> grant(Path path) throws Failure {
                    return worker.grant(path,Duration.ofSeconds(10)).thenApply(g -> new Folder() {
                        @Override public CompletableFuture<List<Path>> discover(boolean recursive) throws Failure {
                            return g.discover(recursive,2048,Duration.ofSeconds(15));
                        }
                        @Override public CompletableFuture<PreparedPhoto> prepare(Path photo) throws Failure { return g.analyze(photo,Duration.ofSeconds(20)); }
                    });
                }
                @Override public void cancel() { worker.cancel(); }
                @Override public void close() { worker.close(); }
            };
        } catch (Exception | LinkageError ex) { throw new Failure(Failure.Code.WORK_FAILED); }
    }
}
