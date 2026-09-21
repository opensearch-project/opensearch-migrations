/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Deterministic clock shared by replay tests.
 *
 * <p>All zone views share the same mutable instant. Time can move only forward so tests cannot
 * accidentally create behavior that depends on a wall-clock regression.
 */
public final class FakeClock extends Clock {
    private static final class State {
        private Instant now;

        private State(Instant now) {
            this.now = now;
        }
    }

    private final State state;
    private final ZoneId zone;

    public FakeClock() {
        this(Instant.EPOCH);
    }

    public FakeClock(Instant initial) {
        this(new State(Objects.requireNonNull(initial)), ZoneOffset.UTC);
    }

    private FakeClock(State state, ZoneId zone) {
        this.state = state;
        this.zone = zone;
    }

    public Instant advance(Duration duration) {
        Objects.requireNonNull(duration);
        if (duration.isNegative()) {
            throw new IllegalArgumentException("FakeClock cannot move backward");
        }
        state.now = state.now.plus(duration);
        return state.now;
    }

    public void set(Instant newTime) {
        Objects.requireNonNull(newTime);
        if (newTime.isBefore(state.now)) {
            throw new IllegalArgumentException("FakeClock cannot move backward");
        }
        state.now = newTime;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
        Objects.requireNonNull(requestedZone);
        return requestedZone.equals(zone) ? this : new FakeClock(state, requestedZone);
    }

    @Override
    public Instant instant() {
        return state.now;
    }
}
