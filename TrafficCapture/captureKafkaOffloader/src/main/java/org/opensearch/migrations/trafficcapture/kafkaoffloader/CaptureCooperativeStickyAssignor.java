package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Configurable;

/**
 * Cooperative-sticky assignment with proxy identities carried in-band through subscription and assignment metadata.
 */
public final class CaptureCooperativeStickyAssignor extends CooperativeStickyAssignor implements Configurable {
    public static final String ASSIGNOR_NAME = "capture-cooperative-sticky";
    public static final String NODE_ID_CONFIG = "opensearch.migrations.capture.node.id";

    private static final int SUBSCRIPTION_FOOTER_MAGIC = 0x43504e49;
    private static final Map<String, Consumer<Set<String>>> MEMBERSHIP_OBSERVERS = new ConcurrentHashMap<>();

    private String nodeId;

    @Override
    public String name() {
        return ASSIGNOR_NAME;
    }

    @Override
    public void configure(Map<String, ?> configs) {
        var configuredNodeId = configs.get(NODE_ID_CONFIG);
        if (!(configuredNodeId instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(NODE_ID_CONFIG + " must be a non-blank string");
        }
        nodeId = value;
    }

    @Override
    public ByteBuffer subscriptionUserData(Set<String> topics) {
        var parentData = Objects.requireNonNull(super.subscriptionUserData(topics)).duplicate();
        var nodeBytes = configuredNodeId().getBytes(StandardCharsets.UTF_8);
        var result = ByteBuffer.allocate(
            parentData.remaining() + nodeBytes.length + Integer.BYTES * 2
        );
        result.put(parentData);
        result.put(nodeBytes);
        result.putInt(nodeBytes.length);
        result.putInt(SUBSCRIPTION_FOOTER_MAGIC);
        result.flip();
        return result;
    }

    @Override
    public ConsumerPartitionAssignor.GroupAssignment assign(
        Cluster metadata,
        ConsumerPartitionAssignor.GroupSubscription groupSubscription
    ) {
        var nodeIds = new TreeSet<String>();
        for (var subscription : groupSubscription.groupSubscription().values()) {
            var memberNodeId = decodeNodeId(subscription.userData());
            if (!nodeIds.add(memberNodeId)) {
                throw new IllegalStateException("Duplicate capture proxy nodeId in Kafka membership: " + memberNodeId);
            }
        }

        var base = super.assign(metadata, groupSubscription);
        var encodedMembership = encodeMembership(nodeIds);
        var decorated = new HashMap<String, ConsumerPartitionAssignor.Assignment>();
        base.groupAssignment().forEach((memberId, assignment) ->
            decorated.put(
                memberId,
                new ConsumerPartitionAssignor.Assignment(
                    assignment.partitions(),
                    encodedMembership.duplicate()
                )
            )
        );
        return new ConsumerPartitionAssignor.GroupAssignment(Map.copyOf(decorated));
    }

    @Override
    public void onAssignment(
        ConsumerPartitionAssignor.Assignment assignment,
        ConsumerGroupMetadata metadata
    ) {
        super.onAssignment(assignment, metadata);
        var observer = MEMBERSHIP_OBSERVERS.get(configuredNodeId());
        if (observer != null) {
            observer.accept(decodeMembership(assignment.userData()));
        }
    }

    static AutoCloseable registerMembershipObserver(String nodeId, Consumer<Set<String>> observer) {
        Objects.requireNonNull(nodeId);
        Objects.requireNonNull(observer);
        var previous = MEMBERSHIP_OBSERVERS.putIfAbsent(nodeId, observer);
        if (previous != null) {
            throw new IllegalStateException("A membership observer is already registered for node " + nodeId);
        }
        return () -> MEMBERSHIP_OBSERVERS.remove(nodeId, observer);
    }

    static String decodeNodeId(ByteBuffer subscriptionData) {
        if (subscriptionData == null || subscriptionData.remaining() < Integer.BYTES * 2) {
            throw new IllegalStateException("Capture membership subscription is missing node identity");
        }
        var data = subscriptionData.duplicate();
        int end = data.limit();
        int magic = data.getInt(end - Integer.BYTES);
        int length = data.getInt(end - Integer.BYTES * 2);
        int start = end - Integer.BYTES * 2 - length;
        if (magic != SUBSCRIPTION_FOOTER_MAGIC || length <= 0 || start < data.position()) {
            throw new IllegalStateException("Capture membership subscription has invalid node identity");
        }
        var nodeBytes = new byte[length];
        data.position(start);
        data.get(nodeBytes);
        var decoded = new String(nodeBytes, StandardCharsets.UTF_8);
        if (decoded.isBlank()) {
            throw new IllegalStateException("Capture membership subscription has a blank node identity");
        }
        return decoded;
    }

    static ByteBuffer encodeMembership(Set<String> nodeIds) {
        int size = Integer.BYTES;
        var encoded = new HashMap<String, byte[]>();
        for (var member : nodeIds) {
            var bytes = member.getBytes(StandardCharsets.UTF_8);
            encoded.put(member, bytes);
            size = Math.addExact(size, Math.addExact(Integer.BYTES, bytes.length));
        }
        var result = ByteBuffer.allocate(size);
        result.putInt(nodeIds.size());
        nodeIds.forEach(member -> {
            var bytes = encoded.get(member);
            result.putInt(bytes.length);
            result.put(bytes);
        });
        result.flip();
        return result;
    }

    static Set<String> decodeMembership(ByteBuffer assignmentData) {
        if (assignmentData == null || assignmentData.remaining() < Integer.BYTES) {
            throw new IllegalStateException("Capture membership assignment is missing member identities");
        }
        var data = assignmentData.duplicate();
        int count = data.getInt();
        if (count <= 0) {
            throw new IllegalStateException("Capture membership assignment has no members");
        }
        var members = new HashSet<String>();
        for (int i = 0; i < count; ++i) {
            if (data.remaining() < Integer.BYTES) {
                throw new IllegalStateException("Capture membership assignment is truncated");
            }
            int length = data.getInt();
            if (length <= 0 || data.remaining() < length) {
                throw new IllegalStateException("Capture membership assignment has an invalid member identity");
            }
            var bytes = new byte[length];
            data.get(bytes);
            var member = new String(bytes, StandardCharsets.UTF_8);
            if (member.isBlank() || !members.add(member)) {
                throw new IllegalStateException("Capture membership assignment has duplicate or blank identities");
            }
        }
        if (data.hasRemaining()) {
            throw new IllegalStateException("Capture membership assignment has trailing data");
        }
        return Set.copyOf(members);
    }

    private String configuredNodeId() {
        if (nodeId == null) {
            throw new IllegalStateException("Capture membership assignor has not been configured");
        }
        return nodeId;
    }
}
