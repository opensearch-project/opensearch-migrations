package org.opensearch.migrations;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.workcoordination.WorkItemTimeProvider;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class RfsMigrateDocumentsTest {

    private static final Duration TEST_INITIAL_LEASE_DURATION = Duration.ofMinutes(1);
    private static final double DECREASE_LEASE_DURATION_SHARD_SETUP_THRESHOLD = .025d;
    private static final double INCREASE_LEASE_DURATION_SHARD_SETUP_THRESHOLD = .1d;

    @ParameterizedTest
    @MethodSource("provideTestParameters")
    void testGetSuccessorNextAcquisitionLeaseExponent(int existingLeaseExponent, int expectedSuccessorExponent, double shardPrepFraction, String message) {
        WorkItemTimeProvider workItemTimeProvider = new WorkItemTimeProvider();

        int initialLeaseMultiple = (int) Math.pow(2, existingLeaseExponent);
        Duration leaseDuration = TEST_INITIAL_LEASE_DURATION.multipliedBy(initialLeaseMultiple);

        Duration shardPrepTime = Duration.ofNanos((long)(leaseDuration.toNanos() * shardPrepFraction));

        workItemTimeProvider.getLeaseAcquisitionTimeRef().set(Instant.EPOCH);
        workItemTimeProvider.getDocumentMigraionStartTimeRef().set(Instant.EPOCH.plus(shardPrepTime));
        Instant leaseExpirationTime = Instant.EPOCH.plus(leaseDuration);

        int successorNextAcquisitionLeaseExponent = RfsMigrateDocuments.getSuccessorNextAcquisitionLeaseExponent(
            workItemTimeProvider, TEST_INITIAL_LEASE_DURATION, leaseExpirationTime);

        Assertions.assertEquals(expectedSuccessorExponent, successorNextAcquisitionLeaseExponent, message);
    }

    static Stream<Arguments> provideTestParameters() {
        return Stream.of(
            Arguments.of(2, 1, DECREASE_LEASE_DURATION_SHARD_SETUP_THRESHOLD - 0.001, "Should decrease successorExponent when shard prep time is less than decrease threshold for lease duration"),
            Arguments.of(0, 0, DECREASE_LEASE_DURATION_SHARD_SETUP_THRESHOLD - 0.001, "Should return 0 for successorExponent when shard prep time is less than decrease threshold for lease duration and existingLeaseExponent is 0"),
            Arguments.of(1, 1, INCREASE_LEASE_DURATION_SHARD_SETUP_THRESHOLD - 0.001, "Should return existingLeaseExponent when shard prep time is less than increase threshold for lease duration"),
            Arguments.of(1, 1, INCREASE_LEASE_DURATION_SHARD_SETUP_THRESHOLD, "Should return existingLeaseExponent when shard prep time is equal to increase threshold for lease duration"),
            Arguments.of(1, 2, INCREASE_LEASE_DURATION_SHARD_SETUP_THRESHOLD + 0.001, "Should return existingLeaseExponent + 1 when shard prep time is greater than increase threshold for lease duration")
        );
    }

    @ParameterizedTest
    @MethodSource("emitDocTypeCases")
    void testResolveEmitDocType(RfsMigrateDocuments.EmitDocTypeMode mode, Version version, String transformerConfig, boolean expected) {
        Assertions.assertEquals(expected, RfsMigrateDocuments.resolveEmitDocType(mode, version, transformerConfig));
    }

    static Stream<Arguments> emitDocTypeCases() {
        Version es5 = Version.fromString("ES 5.6.16");
        Version es6 = Version.fromString("ES 6.8.23");
        Version es7 = Version.fromString("ES 7.10.2");
        Version os3 = Version.fromString("OS 3.1.0");
        String someTransformer = "[{\"TypeMappingSanitizationTransformerProvider\":{}}]";

        return Stream.of(
            Arguments.of(RfsMigrateDocuments.EmitDocTypeMode.ON,  es7, null, true),
            Arguments.of(RfsMigrateDocuments.EmitDocTypeMode.OFF, es5, someTransformer, false),
            Arguments.of(RfsMigrateDocuments.EmitDocTypeMode.AUTO, es5, someTransformer, true),
            Arguments.of(RfsMigrateDocuments.EmitDocTypeMode.AUTO, es6, someTransformer, true),
            Arguments.of(RfsMigrateDocuments.EmitDocTypeMode.AUTO, es7, someTransformer, false),
            Arguments.of(RfsMigrateDocuments.EmitDocTypeMode.AUTO, es5, null, false),
            Arguments.of(RfsMigrateDocuments.EmitDocTypeMode.AUTO, os3, someTransformer, false),
            Arguments.of(RfsMigrateDocuments.EmitDocTypeMode.AUTO, null, someTransformer, false)
        );
    }
}
