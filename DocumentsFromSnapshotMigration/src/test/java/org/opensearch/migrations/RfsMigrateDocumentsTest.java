package org.opensearch.migrations;

import java.time.Duration;
import java.time.Instant;

import org.opensearch.migrations.bulkload.workcoordination.WorkItemTimeProvider;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


class RfsMigrateDocumentsTest {


    private static class TestClass extends RfsMigrateDocuments {
        public static int getSuccessorNextAcquisitionLeaseExponent(WorkItemTimeProvider workItemTimeProvider, Duration initialLeaseDuration,
                                                   Instant leaseExpirationTime) {
            return RfsMigrateDocuments.getSuccessorNextAcquisitionLeaseExponent(workItemTimeProvider, initialLeaseDuration, leaseExpirationTime);
        }
    }

    @Test
    public void testGetSuccessorNextAcquisitionLeaseExponent_LessThanThreshold() {
        WorkItemTimeProvider workItemTimeProvider = new WorkItemTimeProvider();

        var shardPrepTime = Duration.ofSeconds(59);
        var initialShardAttempts = 0;
        var initialLeaseMultiple =  (int) Math.pow(2, initialShardAttempts);

        workItemTimeProvider.getLeaseAcquisitionTimeRef().set(Instant.EPOCH);
        workItemTimeProvider.getDocumentMigraionStartTimeRef().set(Instant.EPOCH.plus(shardPrepTime));
        Duration initialLeaseDuration = Duration.ofMinutes(10);
        Instant leaseExpirationTime = Instant.EPOCH.plus(initialLeaseDuration.multipliedBy(initialLeaseMultiple));

        int successorAttempts = TestClass.getSuccessorNextAcquisitionLeaseExponent(workItemTimeProvider, initialLeaseDuration, leaseExpirationTime);

        Assertions.assertEquals(initialShardAttempts, successorAttempts, "Should return initialShardAttempts + 1 when shard prep time is less than 10% of lease duration");
    }

    @Test
    public void testGetSuccessorNextAcquisitionLeaseExponent_EqualToThreshold() {
        WorkItemTimeProvider workItemTimeProvider = new WorkItemTimeProvider();

        var shardPrepTime = Duration.ofSeconds(60);
        var initialShardAttempts = 0;
        var initialLeaseMultiple =  (int) Math.pow(2, initialShardAttempts);

        workItemTimeProvider.getLeaseAcquisitionTimeRef().set(Instant.EPOCH);
        workItemTimeProvider.getDocumentMigraionStartTimeRef().set(Instant.EPOCH.plus(shardPrepTime));
        Duration initialLeaseDuration = Duration.ofMinutes(10);
        Instant leaseExpirationTime = Instant.EPOCH.plus(initialLeaseDuration.multipliedBy(initialLeaseMultiple));

        int successorAttempts = TestClass.getSuccessorNextAcquisitionLeaseExponent(workItemTimeProvider, initialLeaseDuration, leaseExpirationTime);

        Assertions.assertEquals(initialShardAttempts, successorAttempts, "Should return initialShardAttempts when shard prep time is equal to 10% of lease duration");
    }

    @Test
    public void testGetSuccessorNextAcquisitionLeaseExponent_ExceedsThreshold() {
        WorkItemTimeProvider workItemTimeProvider = new WorkItemTimeProvider();

        var shardPrepTime = Duration.ofSeconds(61);
        var initialShardAttempts = 0;
        var initialLeaseMultiple =  (int) Math.pow(2, initialShardAttempts);

        workItemTimeProvider.getLeaseAcquisitionTimeRef().set(Instant.EPOCH);
        workItemTimeProvider.getDocumentMigraionStartTimeRef().set(Instant.EPOCH.plus(shardPrepTime));
        Duration initialLeaseDuration = Duration.ofMinutes(10);
        Instant leaseExpirationTime = Instant.EPOCH.plus(initialLeaseDuration.multipliedBy(initialLeaseMultiple));

        int successorAttempts = TestClass.getSuccessorNextAcquisitionLeaseExponent(workItemTimeProvider, initialLeaseDuration, leaseExpirationTime);

        Assertions.assertEquals(initialShardAttempts + 1, successorAttempts, "Should return initialShardAttempts + 1 when shard prep time is greater than to 10% of lease duration");
    }
}
