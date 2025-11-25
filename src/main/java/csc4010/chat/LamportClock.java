package csc4010.chat;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple Lamport logical clock helper.
 */
public final class LamportClock {
    private final AtomicLong counter = new AtomicLong(0L);

    /**
     * Advances the clock on a local event (e.g. user sends a message).
     */
    public long tick() {
        return counter.incrementAndGet();
    }

    /**
     * Applies the Lamport rule upon receiving a message carrying remote time.
     */
    public long observe(long remoteTime) {
        return counter.updateAndGet(current -> Math.max(current, remoteTime) + 1);
    }

    /**
     * Returns the current local logical time.
     */
    public long current() {
        return counter.get();
    }
}
