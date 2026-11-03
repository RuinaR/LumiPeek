package io.lumipeek.core;

import java.time.Duration;

/** Planning seam for SDK busy. Disabled unless a presentation adapter explicitly enables it. */
public final class BusyPolicy {
    private boolean requested;
    private long start;
    public boolean requestOnce(boolean enabled, boolean quiet, boolean occupied, boolean targetPresent, long now) {
        if (!enabled || requested || quiet || occupied || !targetPresent) return false;
        requested = true; start = now; return true;
    }
    public boolean localDotsOnly(long now) {
        return !requested || now - start >= Duration.ofSeconds(90).toNanos();
    }
    // No renew, hideBubble, claim or ownership heuristic. A new photo uses a new policy.
}
