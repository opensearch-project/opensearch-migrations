package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeMap;

import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.AdmissionPhase;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.AssignmentTable;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.Footprint;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.FootprintIdentity;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.Subscription;

/**
 * Member-side subscription echo, directional peer-visibility confirmation, and admission phase.
 */
final class CaptureGroupMemberState {
    record Update(
        Subscription subscription,
        AdmissionPhase effectivePhase,
        boolean metadataChanged,
        OptionalLong nextRebalanceAtNanos
    ) {}

    private final String nodeId;
    private final long peerVisibilityIntervalNanos;
    private final Map<String, Long> peerVisibleSinceNanos = new LinkedHashMap<>();
    private Footprint footprint;
    private AdmissionPhase advertisedPhase = AdmissionPhase.PROBATIONARY;
    private AdmissionPhase effectivePhase = AdmissionPhase.PROBATIONARY;
    private Map<String, FootprintIdentity> observedFootprints = Map.of();
    private Map<String, FootprintIdentity> confirmedPeerVisibility = Map.of();

    CaptureGroupMemberState(
        String nodeId,
        Footprint initialFootprint,
        Duration peerVisibilityInterval
    ) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        Objects.requireNonNull(peerVisibilityInterval);
        if (peerVisibilityInterval.isNegative()) {
            throw new IllegalArgumentException("peerVisibilityInterval must not be negative");
        }
        this.nodeId = nodeId;
        this.footprint = Objects.requireNonNull(initialFootprint);
        this.peerVisibilityIntervalNanos = peerVisibilityInterval.toNanos();
    }

    Subscription subscription() {
        return new Subscription(
            nodeId,
            advertisedPhase,
            footprint,
            observedFootprints,
            confirmedPeerVisibility
        );
    }

    Update installAssignment(AssignmentTable table, long nowNanos) {
        Objects.requireNonNull(table);
        var ownRow = table.members().get(nodeId);
        if (ownRow == null) {
            throw new IllegalStateException("capture assignment table does not contain this node");
        }
        if (!footprint.equals(ownRow.footprint())) {
            throw new IllegalStateException("capture assignment table changed this node's footprint");
        }
        if (advertisedPhase == AdmissionPhase.ACTIVE
            && ownRow.effectivePhase() != AdmissionPhase.ACTIVE) {
            throw new IllegalStateException("capture assignment table attempted to demote an active member");
        }

        var previousSubscription = subscription();
        effectivePhase = ownRow.effectivePhase();
        observedFootprints = observedFootprints(table);
        updatePeerVisibilityTimers(table, nowNanos);
        confirmedPeerVisibility = confirmedPeerVisibilityClaims(nowNanos);
        advertisedPhase = effectivePhase == AdmissionPhase.ACTIVE
            ? AdmissionPhase.ACTIVE
            : AdmissionPhase.PROBATIONARY;
        var nextRebalanceAt = nextRebalanceAt(nowNanos);
        var currentSubscription = subscription();
        return new Update(
            currentSubscription,
            effectivePhase,
            !previousSubscription.equals(currentSubscription),
            nextRebalanceAt
        );
    }

    private Map<String, FootprintIdentity> observedFootprints(AssignmentTable table) {
        var observations = new LinkedHashMap<String, FootprintIdentity>();
        table.members().forEach((memberNodeId, row) -> {
            if (!memberNodeId.equals(nodeId)) {
                observations.put(memberNodeId, row.footprint().identity(memberNodeId));
            }
        });
        return Collections.unmodifiableMap(observations);
    }

    private void updatePeerVisibilityTimers(AssignmentTable table, long nowNanos) {
        var ownIdentity = footprint.identity(nodeId);
        peerVisibleSinceNanos.keySet().removeIf(observerNodeId -> {
            var observer = table.members().get(observerNodeId);
            return observer == null || !ownIdentity.equals(observer.observedFootprints().get(nodeId));
        });
        table.members().forEach((observerNodeId, observer) -> {
            if (!observerNodeId.equals(nodeId)
                && ownIdentity.equals(observer.observedFootprints().get(nodeId))) {
                peerVisibleSinceNanos.putIfAbsent(observerNodeId, nowNanos);
            }
        });
    }

    private Map<String, FootprintIdentity> confirmedPeerVisibilityClaims(long nowNanos) {
        var ownIdentity = footprint.identity(nodeId);
        var claims = new TreeMap<String, FootprintIdentity>();
        peerVisibleSinceNanos.forEach((observerNodeId, visibleSinceNanos) -> {
            if (elapsedAtLeast(nowNanos, visibleSinceNanos, peerVisibilityIntervalNanos)) {
                claims.put(observerNodeId, ownIdentity);
            }
        });
        return Collections.unmodifiableMap(claims);
    }

    private OptionalLong nextRebalanceAt(long nowNanos) {
        return peerVisibleSinceNanos.values()
            .stream()
            .mapToLong(start -> Math.addExact(start, peerVisibilityIntervalNanos))
            .filter(deadline -> deadline > nowNanos)
            .min();
    }

    private static boolean elapsedAtLeast(long nowNanos, long startNanos, long requiredNanos) {
        return nowNanos - startNanos >= requiredNanos;
    }
}
