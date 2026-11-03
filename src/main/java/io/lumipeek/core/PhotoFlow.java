package io.lumipeek.core;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Headless presentation state. The adapter must recheck SDK state immediately before output. */
public final class PhotoFlow implements AutoCloseable {
    public enum State { IDLE, SELECTED, ANALYZING, REACTION_READY, REACTION_SHOWN, FINISHED }
    public enum End { NONE, COMPLETE, CANCELLED, FAILED, SKIPPED, TIMEOUT, REMOVED, SUPPRESSED, STOPPED }
    private static final AtomicLong TOKENS = new AtomicLong();
    public record Observation(boolean quiet, boolean hidden, boolean bubbleVisible, boolean targetPresent) {}
    public record View(State state, long generation, UUID photoId, boolean visible,
                       boolean localDots, boolean reactionEligible, String reaction, End end) {}
    private State state = State.IDLE;
    private End end = End.NONE;
    private long generation, started, shown, last;
    private final long budget;
    private UUID photoId;
    private PreparedPhoto photo;
    private Reaction reaction;
    private boolean stopped;
    private boolean clockSet;
    public PhotoFlow(Duration analysisBudget) {
        if (analysisBudget.isNegative() || analysisBudget.isZero() || analysisBudget.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("budget");
        budget = analysisBudget.toNanos();
    }
    public synchronized long select(UUID id, long now) {
        if (stopped) throw new IllegalStateException("STOPPED");
        time(now); finish(End.CANCELLED);
        generation = TOKENS.incrementAndGet(); photoId = Objects.requireNonNull(id); started = now;
        reaction = null; end = End.NONE; state = State.SELECTED;
        return generation;
    }
    public synchronized void prepared(long token, PreparedPhoto image) {
        if (token != generation || state != State.SELECTED || stopped) { image.close(); return; }
        photo = Objects.requireNonNull(image); state = State.ANALYZING;
    }
    public synchronized void analyzed(long token, Optional<Reaction> result) {
        if (token != generation || state != State.ANALYZING || stopped) return;
        if (result.isEmpty() || !result.get().safeToShow()) { finish(End.SKIPPED); return; }
        reaction = result.get(); state = State.REACTION_READY;
    }
    public synchronized void failed(long token) { if (token == generation && active()) finish(End.FAILED); }
    public synchronized void otherSpeech() { if (active()) finish(End.SUPPRESSED); }
    public synchronized void cancel() { if (active()) finish(End.CANCELLED); }
    public synchronized View tick(long now, Observation observed) {
        time(now);
        if (active()) {
            if (!observed.targetPresent()) finish(End.REMOVED);
            else if (observed.quiet() || observed.hidden()) finish(End.SUPPRESSED);
            else if (state == State.REACTION_SHOWN && now - shown >= Duration.ofSeconds(6).toNanos()) finish(End.COMPLETE);
            else if (state != State.REACTION_SHOWN && now - started >= budget) finish(End.TIMEOUT);
        }
        boolean clear = !observed.quiet() && !observed.hidden() && observed.targetPresent() && !observed.bubbleVisible();
        boolean visible = photo != null && active() && (clear || state == State.REACTION_SHOWN);
        boolean ready = state == State.REACTION_READY && clear;
        return new View(state, generation, photoId, visible, visible && state != State.REACTION_SHOWN,
            ready, reaction == null ? "" : reaction.reaction(), end);
    }
    /** Call only after a fresh SDK guard and successful output; never infer bubble ownership. */
    public synchronized boolean shown(long token, long now, Observation fresh) {
        View view = tick(now, fresh);
        if (token != generation || !view.reactionEligible()) return false;
        shown = now; state = State.REACTION_SHOWN; return true;
    }
    private boolean active() { return state != State.IDLE && state != State.FINISHED; }
    private void time(long now) { if (clockSet && now < last) throw new IllegalArgumentException("monotonic time"); last = now; clockSet = true; }
    private void finish(End reason) {
        if (photo != null) { photo.close(); photo = null; }
        reaction = null;
        if (state != State.IDLE) { state = State.FINISHED; end = reason; }
    }
    @Override public synchronized void close() { if (!stopped) { stopped = true; if (active()) finish(End.STOPPED); generation = TOKENS.incrementAndGet(); } }
}
