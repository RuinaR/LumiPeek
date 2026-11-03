package io.lumipeek.sdk;

import io.lumipeek.core.Failure;
import java.nio.file.Path;
import java.util.concurrent.*;

/** JNI initialization is deferred until the first explicit folder grant, never on EDT. */
final class LazyPhotos implements PhotoSource {
    private final ExecutorService init=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"lumipeek-native-init"); t.setDaemon(true); return t; });
    private volatile PhotoSource delegate;
    private volatile boolean closed;
    @Override public CompletableFuture<Folder> grant(Path root) {
        if(closed) return CompletableFuture.failedFuture(new Failure(Failure.Code.CANCELLED));
        try { return CompletableFuture.supplyAsync(() -> {
                if(closed) throw new CompletionException(new Failure(Failure.Code.CANCELLED));
                try {
                    if(delegate==null) delegate=PhotoSource.production();
                    if(closed) { delegate.close(); throw new Failure(Failure.Code.CANCELLED); }
                    return delegate;
                }
                catch(Failure ex) { throw new CompletionException(ex); }
        },init).thenCompose(source -> {
            try { return source.grant(root); } catch(Failure ex) { return CompletableFuture.failedFuture(ex); }
        }); } catch(RejectedExecutionException ex) { return CompletableFuture.failedFuture(new Failure(Failure.Code.CANCELLED)); }
    }
    @Override public void cancel() { PhotoSource current=delegate; if(current!=null) current.cancel(); }
    @Override public void close() { closed=true; PhotoSource current=delegate; if(current!=null) current.close(); init.shutdown(); }
}
