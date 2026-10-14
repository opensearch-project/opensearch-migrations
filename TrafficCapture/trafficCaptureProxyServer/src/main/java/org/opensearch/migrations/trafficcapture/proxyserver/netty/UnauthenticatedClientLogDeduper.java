package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Limits repeated diagnostic stack traces for failures that occur before a frontside TLS
 * connection has established a usable client channel. {@link ProxyChannelInitializer} creates one
 * instance and shares it across its frontside TLS exception handlers, allowing the first occurrence
 * of a known failure to be logged immediately while suppressing equivalent logs for a bounded
 * interval. The next log after that interval reports how many occurrences were suppressed.
 *
 * <p>This class controls log volume only. It does not identify clients, authenticate them, rate
 * limit connections, change channel handling, or provide denial-of-service protection. The caller
 * still applies the normal failure behavior to every occurrence; currently that means closing each
 * channel whose frontside TLS handshake fails. State is process-local, uses monotonic time, and is
 * bounded by the fixed set of {@link KnownEvent} values rather than by client address.
 */
class UnauthenticatedClientLogDeduper {

    /** Failure categories whose repeated diagnostic logs may be coalesced independently. */
    enum KnownEvent {
        FRONTSIDE_TLS_HANDSHAKE_FAILURE
    }

    private final EnumMap<KnownEvent, EventState> eventStates = new EnumMap<>(KnownEvent.class);
    private final long dedupeWindowNanos;
    private final LongSupplier nanoTimeSupplier;

    UnauthenticatedClientLogDeduper(Duration dedupeWindow) {
        this(dedupeWindow, System::nanoTime);
    }

    UnauthenticatedClientLogDeduper(Duration dedupeWindow, LongSupplier nanoTimeSupplier) {
        if (dedupeWindow == null || !dedupeWindow.isPositive()) {
            throw new IllegalArgumentException("dedupeWindow must be positive");
        }
        this.dedupeWindowNanos = dedupeWindow.toNanos();
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier);
        for (var knownEvent : KnownEvent.values()) {
            eventStates.put(knownEvent, new EventState());
        }
    }

    /**
     * Records one occurrence and decides whether its diagnostic should be emitted. A logging
     * decision includes the number of equivalent events suppressed since the previous emitted
     * diagnostic.
     */
    synchronized LogDecision recordOccurrence(KnownEvent knownEvent) {
        var eventState = eventStates.get(Objects.requireNonNull(knownEvent));
        var now = nanoTimeSupplier.getAsLong();
        if (now < eventState.nextLogAfterNanos) {
            eventState.incrementSuppressedCount();
            return LogDecision.suppress();
        }

        var suppressedCount = eventState.suppressedCount;
        eventState.suppressedCount = 0;
        eventState.nextLogAfterNanos = now + dedupeWindowNanos;
        return LogDecision.log(suppressedCount);
    }

    private static class EventState {
        private long nextLogAfterNanos = Long.MIN_VALUE;
        private long suppressedCount;

        private void incrementSuppressedCount() {
            if (suppressedCount < Long.MAX_VALUE) {
                suppressedCount++;
            }
        }
    }

    /** Immutable instruction returned to the exception handler for one observed event. */
    static class LogDecision {
        private static final LogDecision SUPPRESS = new LogDecision(false, 0);

        private final boolean shouldLog;
        private final long suppressedCountSinceLastLog;

        private LogDecision(boolean shouldLog, long suppressedCountSinceLastLog) {
            this.shouldLog = shouldLog;
            this.suppressedCountSinceLastLog = suppressedCountSinceLastLog;
        }

        private static LogDecision suppress() {
            return SUPPRESS;
        }

        private static LogDecision log(long suppressedCountSinceLastLog) {
            return new LogDecision(true, suppressedCountSinceLastLog);
        }

        boolean shouldLog() {
            return shouldLog;
        }

        long getSuppressedCountSinceLastLog() {
            return suppressedCountSinceLastLog;
        }
    }
}
