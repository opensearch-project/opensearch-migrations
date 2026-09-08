package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable values exchanged by the capture proxy membership protocol.
 */
final class CaptureGroupProtocol {
    private static final byte FOOTPRINT_DIGEST_VERSION = 1;

    private CaptureGroupProtocol() {}

    enum AdmissionPhase {
        PROBATIONARY,
        ACTIVE
    }

    record Footprint(long revision, boolean unknown, List<Integer> partitions, String digest) {
        Footprint {
            if (revision < 0) {
                throw new IllegalArgumentException("footprint revision must not be negative");
            }
            Objects.requireNonNull(partitions);
            var canonicalPartitions = canonicalPartitions(partitions);
            if (unknown && !canonicalPartitions.isEmpty()) {
                throw new IllegalArgumentException("an unknown footprint cannot contain partitions");
            }
            partitions = canonicalPartitions;
            var expectedDigest = footprintDigest(unknown, canonicalPartitions);
            if (!expectedDigest.equals(Objects.requireNonNull(digest))) {
                throw new IllegalArgumentException("footprint digest does not match its canonical partition set");
            }
        }

        static Footprint unknown(long revision) {
            return new Footprint(revision, true, List.of(), footprintDigest(true, List.of()));
        }

        static Footprint known(long revision, Collection<Integer> partitions) {
            var canonicalPartitions = canonicalPartitions(partitions);
            return new Footprint(
                revision,
                false,
                canonicalPartitions,
                footprintDigest(false, canonicalPartitions)
            );
        }

        FootprintIdentity identity(String nodeId) {
            return new FootprintIdentity(nodeId, revision, digest);
        }
    }

    record FootprintIdentity(String nodeId, long revision, String digest) {
        FootprintIdentity {
            nodeId = requireNonBlank(nodeId, "footprint nodeId");
            if (revision < 0) {
                throw new IllegalArgumentException("footprint revision must not be negative");
            }
            digest = requireDigest(digest);
        }
    }

    record Subscription(
        String nodeId,
        AdmissionPhase advertisedPhase,
        Footprint footprint,
        Map<String, FootprintIdentity> observedFootprints,
        Map<String, FootprintIdentity> confirmedPeerVisibility
    ) {
        Subscription {
            nodeId = requireNonBlank(nodeId, "subscription nodeId");
            advertisedPhase = Objects.requireNonNull(advertisedPhase);
            footprint = Objects.requireNonNull(footprint);
            observedFootprints = canonicalObservedFootprints(observedFootprints);
            confirmedPeerVisibility = canonicalPeerVisibilityClaims(nodeId, confirmedPeerVisibility);
            if (observedFootprints.containsKey(nodeId) || confirmedPeerVisibility.containsKey(nodeId)) {
                throw new IllegalArgumentException("a member cannot advertise itself as a peer");
            }
        }
    }

    record MemberRow(
        String nodeId,
        AdmissionPhase advertisedPhase,
        AdmissionPhase effectivePhase,
        Footprint footprint,
        Map<String, FootprintIdentity> observedFootprints,
        Map<String, FootprintIdentity> confirmedPeerVisibility
    ) {
        MemberRow {
            nodeId = requireNonBlank(nodeId, "member nodeId");
            advertisedPhase = Objects.requireNonNull(advertisedPhase);
            effectivePhase = Objects.requireNonNull(effectivePhase);
            footprint = Objects.requireNonNull(footprint);
            observedFootprints = canonicalObservedFootprints(observedFootprints);
            confirmedPeerVisibility = canonicalPeerVisibilityClaims(nodeId, confirmedPeerVisibility);
            if (observedFootprints.containsKey(nodeId) || confirmedPeerVisibility.containsKey(nodeId)) {
                throw new IllegalArgumentException("a member cannot advertise itself as a peer");
            }
        }
    }

    record AssignmentTable(Map<String, MemberRow> members) {
        AssignmentTable {
            Objects.requireNonNull(members);
            var canonical = new LinkedHashMap<String, MemberRow>();
            new TreeMap<>(members).forEach((nodeId, row) -> {
                if (!nodeId.equals(row.nodeId())) {
                    throw new IllegalArgumentException("assignment member key does not match its row");
                }
                canonical.put(nodeId, row);
            });
            if (canonical.isEmpty()) {
                throw new IllegalArgumentException("assignment table must contain at least one member");
            }
            members = Collections.unmodifiableMap(canonical);
        }
    }

    private static Map<String, FootprintIdentity> canonicalObservedFootprints(
        Map<String, FootprintIdentity> identities
    ) {
        Objects.requireNonNull(identities);
        var canonical = new LinkedHashMap<String, FootprintIdentity>();
        new TreeMap<>(identities).forEach((nodeId, identity) -> {
            if (!nodeId.equals(identity.nodeId())) {
                throw new IllegalArgumentException("observed footprint key does not match its identity");
            }
            canonical.put(nodeId, identity);
        });
        return Collections.unmodifiableMap(canonical);
    }

    private static Map<String, FootprintIdentity> canonicalPeerVisibilityClaims(
        String candidateNodeId,
        Map<String, FootprintIdentity> claims
    ) {
        Objects.requireNonNull(claims);
        var canonical = new LinkedHashMap<String, FootprintIdentity>();
        new TreeMap<>(claims).forEach((observerNodeId, candidateIdentity) -> {
            requireNonBlank(observerNodeId, "peer observer nodeId");
            if (!candidateNodeId.equals(candidateIdentity.nodeId())) {
                throw new IllegalArgumentException(
                    "confirmed peer visibility must identify the advertising candidate's footprint"
                );
            }
            canonical.put(observerNodeId, candidateIdentity);
        });
        return Collections.unmodifiableMap(canonical);
    }

    private static List<Integer> canonicalPartitions(Collection<Integer> partitions) {
        Objects.requireNonNull(partitions);
        var canonical = new TreeSet<Integer>();
        for (var partition : partitions) {
            if (partition == null || partition < 0) {
                throw new IllegalArgumentException("footprint partitions must not be negative");
            }
            if (!canonical.add(partition)) {
                throw new IllegalArgumentException("footprint partitions must be unique");
            }
        }
        return List.copyOf(canonical);
    }

    private static String footprintDigest(boolean unknown, List<Integer> partitions) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        digest.update(FOOTPRINT_DIGEST_VERSION);
        digest.update((byte) (unknown ? 1 : 0));
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(partitions.size()).array());
        for (var partition : partitions) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(partition).array());
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireDigest(String value) {
        var digest = requireNonBlank(value, "footprint digest");
        if (digest.length() != 64) {
            throw new IllegalArgumentException("footprint digest must be a SHA-256 hex value");
        }
        try {
            java.util.HexFormat.of().parseHex(digest);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("footprint digest must be a SHA-256 hex value", e);
        }
        return digest;
    }
}
