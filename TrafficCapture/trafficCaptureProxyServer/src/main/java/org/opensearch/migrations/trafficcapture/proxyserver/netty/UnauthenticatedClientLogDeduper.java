package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Objects;
import java.util.function.LongSupplier;

class UnauthenticatedClientLogDeduper {

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

    synchronized LogDecision record(KnownEvent knownEvent) {
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

    int knownEventCount() {
        return eventStates.size();
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
