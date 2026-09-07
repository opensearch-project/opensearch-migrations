package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaptureCooperativeStickyAssignorTest {
    private static final String TOPIC = "traffic";

    @Test
    void cooperativeAssignmentCarriesTheCompleteProxyMembershipToEveryMember() throws Exception {
        var nodeA = assignor("node-a");
        var nodeB = assignor("node-b");
        var subscriptions = Map.of(
            "member-a",
            subscription(nodeA),
            "member-b",
            subscription(nodeB)
        );

        var assignment = nodeA.assign(cluster(4), new ConsumerPartitionAssignor.GroupSubscription(subscriptions));
        var seenByA = new AtomicReference<Set<String>>();
        var seenByB = new AtomicReference<Set<String>>();
        try (
            var registrationA = CaptureCooperativeStickyAssignor.registerMembershipObserver(
                "node-a",
                seenByA::set
            );
            var registrationB = CaptureCooperativeStickyAssignor.registerMembershipObserver(
                "node-b",
                seenByB::set
            )
        ) {
            nodeA.onAssignment(
                assignment.groupAssignment().get("member-a"),
                metadata("member-a")
            );
            nodeB.onAssignment(
                assignment.groupAssignment().get("member-b"),
                metadata("member-b")
            );
        }

        assertEquals(Set.of("node-a", "node-b"), seenByA.get());
        assertEquals(Set.of("node-a", "node-b"), seenByB.get());
        assertEquals(
            Set.of(
                new TopicPartition(TOPIC, 0),
                new TopicPartition(TOPIC, 1),
                new TopicPartition(TOPIC, 2),
                new TopicPartition(TOPIC, 3)
            ),
            assignment.groupAssignment().values().stream()
                .flatMap(memberAssignment -> memberAssignment.partitions().stream())
                .collect(java.util.stream.Collectors.toSet())
        );
    }

    @Test
    void duplicateProxyNodeIdsFailTheAssignment() {
        var leader = assignor("duplicate");
        var duplicate = assignor("duplicate");
        var subscriptions = Map.of(
            "member-a",
            subscription(leader),
            "member-b",
            subscription(duplicate)
        );

        assertThrows(
            IllegalStateException.class,
            () -> leader.assign(cluster(2), new ConsumerPartitionAssignor.GroupSubscription(subscriptions))
        );
    }

    @Test
    void malformedSubscriptionOrAssignmentMetadataFailsClosed() {
        assertThrows(
            IllegalStateException.class,
            () -> CaptureCooperativeStickyAssignor.decodeNodeId(java.nio.ByteBuffer.allocate(0))
        );
        assertThrows(
            IllegalStateException.class,
            () -> CaptureCooperativeStickyAssignor.decodeMembership(java.nio.ByteBuffer.allocate(0))
        );
    }

    private static CaptureCooperativeStickyAssignor assignor(String nodeId) {
        var assignor = new CaptureCooperativeStickyAssignor();
        assignor.configure(Map.of(CaptureCooperativeStickyAssignor.NODE_ID_CONFIG, nodeId));
        return assignor;
    }

    private static ConsumerPartitionAssignor.Subscription subscription(
        CaptureCooperativeStickyAssignor assignor
    ) {
        return new ConsumerPartitionAssignor.Subscription(
            List.of(TOPIC),
            assignor.subscriptionUserData(Set.of(TOPIC)),
            List.of()
        );
    }

    private static ConsumerGroupMetadata metadata(String memberId) {
        return new ConsumerGroupMetadata("capture-proxy", 1, memberId, Optional.empty());
    }

    private static Cluster cluster(int partitionCount) {
        var partitions = new ArrayList<PartitionInfo>();
        for (int partition = 0; partition < partitionCount; ++partition) {
            partitions.add(new PartitionInfo(
                TOPIC,
                partition,
                null,
                new Node[0],
                new Node[0]
            ));
        }
        return new Cluster(
            "cluster",
            List.of(),
            partitions,
            Set.of(),
            Set.of()
        );
    }
}
