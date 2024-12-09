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
    public void testGetSuccessorNextAcquisitionLeaseExponent_LessThanLowerThreshold() {
        WorkItemTimeProvider workItemTimeProvider = new WorkItemTimeProvider();

        // Lease at 40 minutes, shard prep 59 seconds, successor lease should be decreased since shard prep is < 2.5%
        // and exponent is > 0
        var existingLeaseExponent = 2;
        var shardPrepTime = Duration.ofSeconds(59);
        Duration initialLeaseDuration = Duration.ofMinutes(10);
        var initialLeaseMultiple =  (int) Math.pow(2, existingLeaseExponent);

        workItemTimeProvider.getLeaseAcquisitionTimeRef().set(Instant.EPOCH);
        workItemTimeProvider.getDocumentMigraionStartTimeRef().set(Instant.EPOCH.plus(shardPrepTime));
        Instant leaseExpirationTime = Instant.EPOCH.plus(initialLeaseDuration.multipliedBy(initialLeaseMultiple));

        int successorNextAcquisitionLeaseExponent = TestClass.getSuccessorNextAcquisitionLeaseExponent(workItemTimeProvider, initialLeaseDuration, leaseExpirationTime);

        Assertions.assertEquals(existingLeaseExponent - 1, successorNextAcquisitionLeaseExponent, "Should decrease successorExponent");
    }


    @Test
    public void testGetSuccessorNextAcquisitionLeaseExponent_LessThanLowerThresholdWith0Exponent() {
        WorkItemTimeProvider workItemTimeProvider = new WorkItemTimeProvider();

        var shardPrepTime = Duration.ofSeconds(1);
        var existingLeaseExponent = 0;
        var initialLeaseMultiple =  (int) Math.pow(2, existingLeaseExponent);

        workItemTimeProvider.getLeaseAcquisitionTimeRef().set(Instant.EPOCH);
        workItemTimeProvider.getDocumentMigraionStartTimeRef().set(Instant.EPOCH.plus(shardPrepTime));
        Duration initialLeaseDuration = Duration.ofMinutes(10);
        Instant leaseExpirationTime = Instant.EPOCH.plus(initialLeaseDuration.multipliedBy(initialLeaseMultiple));

        int successorNextAcquisitionLeaseExponent = TestClass.getSuccessorNextAcquisitionLeaseExponent(workItemTimeProvider, initialLeaseDuration, leaseExpirationTime);

        Assertions.assertEquals(0, successorNextAcquisitionLeaseExponent, "Should return 0 for successorExponent");
    }


    @Test
    public void testGetSuccessorNextAcquisitionLeaseExponent_LessThanUpperThreshold() {
        WorkItemTimeProvider workItemTimeProvider = new WorkItemTimeProvider();

        var shardPrepTime = Duration.ofSeconds(59);
        var existingLeaseExponent = 0;
        var initialLeaseMultiple =  (int) Math.pow(2, existingLeaseExponent);

        workItemTimeProvider.getLeaseAcquisitionTimeRef().set(Instant.EPOCH);
        workItemTimeProvider.getDocumentMigraionStartTimeRef().set(Instant.EPOCH.plus(shardPrepTime));
        Duration initialLeaseDuration = Duration.ofMinutes(10);
        Instant leaseExpirationTime = Instant.EPOCH.plus(initialLeaseDuration.multipliedBy(initialLeaseMultiple));

        int successorNextAcquisitionLeaseExponent = TestClass.getSuccessorNextAcquisitionLeaseExponent(workItemTimeProvider, initialLeaseDuration, leaseExpirationTime);

        Assertions.assertEquals(existingLeaseExponent, successorNextAcquisitionLeaseExponent, "Should return existingLeaseExponent + 1 when shard prep time is less than 10% of lease duration");
    }

    @Test
    public void testGetSuccessorNextAcquisitionLeaseExponent_EqualToUpperThreshold() {
        WorkItemTimeProvider workItemTimeProvider = new WorkItemTimeProvider();

        var shardPrepTime = Duration.ofSeconds(60);
        var existingLeaseExponent = 0;
        var initialLeaseMultiple =  (int) Math.pow(2, existingLeaseExponent);

        workItemTimeProvider.getLeaseAcquisitionTimeRef().set(Instant.EPOCH);
        workItemTimeProvider.getDocumentMigraionStartTimeRef().set(Instant.EPOCH.plus(shardPrepTime));
        Duration initialLeaseDuration = Duration.ofMinutes(10);
        Instant leaseExpirationTime = Instant.EPOCH.plus(initialLeaseDuration.multipliedBy(initialLeaseMultiple));

        int successorNextAcquisitionLeaseExponent = TestClass.getSuccessorNextAcquisitionLeaseExponent(workItemTimeProvider, initialLeaseDuration, leaseExpirationTime);

        Assertions.assertEquals(existingLeaseExponent, successorNextAcquisitionLeaseExponent, "Should return existingLeaseExponent when shard prep time is equal to 10% of lease duration");
    }

    @Test
    public void testGetSuccessorNextAcquisitionLeaseExponent_ExceedsUpperThreshold() {
        WorkItemTimeProvider workItemTimeProvider = new WorkItemTimeProvider();

        var shardPrepTime = Duration.ofSeconds(61);
        var existingLeaseExponent = 0;
        var initialLeaseMultiple =  (int) Math.pow(2, existingLeaseExponent);

        workItemTimeProvider.getLeaseAcquisitionTimeRef().set(Instant.EPOCH);
        workItemTimeProvider.getDocumentMigraionStartTimeRef().set(Instant.EPOCH.plus(shardPrepTime));
        Duration initialLeaseDuration = Duration.ofMinutes(10);
        Instant leaseExpirationTime = Instant.EPOCH.plus(initialLeaseDuration.multipliedBy(initialLeaseMultiple));

        int successorNextAcquisitionLeaseExponent = TestClass.getSuccessorNextAcquisitionLeaseExponent(workItemTimeProvider, initialLeaseDuration, leaseExpirationTime);

        Assertions.assertEquals(existingLeaseExponent + 1, successorNextAcquisitionLeaseExponent, "Should return existingLeaseExponent + 1 when shard prep time is greater than to 10% of lease duration");
    }
}
