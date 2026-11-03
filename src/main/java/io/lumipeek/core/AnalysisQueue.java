package io.lumipeek.core;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static io.lumipeek.core.Failure.Code.*;

/** One worker, no pending backlog. A cancelled noncooperative job retains its slot. */
public final class AnalysisQueue implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "lumipeek-analysis"));
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "lumipeek-timeout"));
    private final Map<UUID, Long> recent = new LinkedHashMap<>();
    private final long cooldown;
    private boolean busy, closed;
    private Thread activeThread;
    private CompletableFuture<?> result;
    private ScheduledFuture<?> deadline;

    public AnalysisQueue(Duration cooldown) {
        if (cooldown.isNegative() || cooldown.compareTo(Duration.ofDays(1)) > 0) throw new IllegalArgumentException("cooldown");
        this.cooldown = cooldown.toNanos();
        timer.setRemoveOnCancelPolicy(true);
    }
    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }

    /** IDs come from PhotoIdentity; never pass paths or persist IDs. */
    public synchronized <T> Optional<CompletableFuture<T>> submit(UUID id, Callable<T> task, Duration timeout) {
        Objects.requireNonNull(id); Objects.requireNonNull(task);
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("timeout");
        long now = System.nanoTime();
        recent.entrySet().removeIf(e -> now - e.getValue() >= cooldown);
        if (closed || busy || recent.containsKey(id)) return Optional.empty();
        busy = true;
        recent.put(id, now);
        if (recent.size() > 256) recent.remove(recent.keySet().iterator().next());
        CompletableFuture<T> answer = new CompletableFuture<>();
        result = answer;
        // execute rather than submit: even cancellation before startup must run finally.
        FutureTask<Void> job = new FutureTask<>(() -> {
            try {
                synchronized (AnalysisQueue.this) { activeThread = Thread.currentThread(); }
                if (!answer.isDone()) {
                    T value = task.call();
                    answer.complete(value);
                }
            } catch (Failure ex) { answer.completeExceptionally(ex); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); answer.completeExceptionally(new Failure(CANCELLED)); }
            catch (Exception ex) { answer.completeExceptionally(new Failure(WORK_FAILED)); }
            catch (Error ex) { answer.completeExceptionally(new Failure(WORK_FAILED)); throw ex; }
            finally {
                synchronized (AnalysisQueue.this) {
                    if (deadline != null) deadline.cancel(false);
                    busy = false; activeThread = null; result = null; deadline = null;
                }
            }
            return null;
        });
        // Do not cancel the FutureTask itself: that would suppress its finally before start.
        deadline = timer.schedule(() -> expire(answer, TIMEOUT), timeout.toNanos(), TimeUnit.NANOSECONDS);
        answer.whenComplete((value, error) -> {
            if (answer.isCancelled()) {
                synchronized (AnalysisQueue.this) {
                    if (result == answer && activeThread != null) activeThread.interrupt();
                }
            }
        });
        worker.execute(job);
        return Optional.of(answer);
    }
    private synchronized void expire(CompletableFuture<?> answer, Failure.Code code) {
        if (result == answer && !answer.isDone()) {
            answer.completeExceptionally(new Failure(code));
            if (activeThread != null) activeThread.interrupt();
        }
    }
    public synchronized void cancel() { if (result != null) expire(result, CANCELLED); }
    public synchronized boolean busy() { return busy; }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; cancel(); timer.shutdownNow(); worker.shutdownNow();
    }
}
