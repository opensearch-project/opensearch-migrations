package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void cooperativeAssignmentCarriesTheCompleteProxyMembershipToEveryMember() {
        var nodeA = assignor("node-a");
        var nodeB = assignor("node-b");
        var subscriptions = Map.of(
            "member-a",
            subscription(nodeA),
            "member-b",
            subscription(nodeB)
        );

        var assignment = nodeA.assign(cluster(4), new ConsumerPartitionAssignor.GroupSubscription(subscriptions));

        assertEquals(
            Set.of("node-a", "node-b"),
            CaptureCooperativeStickyAssignor.decodeMembership(
                assignment.groupAssignment().get("member-a").userData()
            )
        );
        assertEquals(
            Set.of("node-a", "node-b"),
            CaptureCooperativeStickyAssignor.decodeMembership(
                assignment.groupAssignment().get("member-b").userData()
            )
        );
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

    @Test
    void receivedAssignmentPublishesTheCompleteMembershipToTheLocalTracker() {
        var tracker = new CaptureMembershipAssignmentTracker();
        var assignor = assignor("node-a", tracker);

        assignor.onAssignment(
            new ConsumerPartitionAssignor.Assignment(
                List.of(),
                CaptureCooperativeStickyAssignor.encodeMembership(Set.of("node-a", "node-b"))
            ),
            new ConsumerGroupMetadata("capture-group")
        );

        assertEquals(Set.of("node-a", "node-b"), tracker.currentMembers());
    }

    private static CaptureCooperativeStickyAssignor assignor(String nodeId) {
        return assignor(nodeId, new CaptureMembershipAssignmentTracker());
    }

    private static CaptureCooperativeStickyAssignor assignor(
        String nodeId,
        CaptureMembershipAssignmentTracker tracker
    ) {
        var assignor = new CaptureCooperativeStickyAssignor();
        assignor.configure(Map.of(
            CaptureCooperativeStickyAssignor.NODE_ID_CONFIG,
            nodeId,
            CaptureCooperativeStickyAssignor.ASSIGNMENT_TRACKER_CONFIG,
            tracker
        ));
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
