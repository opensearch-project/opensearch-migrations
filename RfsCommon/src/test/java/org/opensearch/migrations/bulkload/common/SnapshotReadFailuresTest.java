package org.opensearch.migrations.bulkload.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SnapshotReadFailuresTest {

    /** Minimal stand-in so the helper test doesn't couple to any specific production exception. */
    private static class FakeSnapshotReadFailure extends RuntimeException implements SnapshotReadFailure {
        FakeSnapshotReadFailure(String message) {
            super(message);
        }

        FakeSnapshotReadFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Test
    void findSnapshotReadFailure_directMatch() {
        var ex = new FakeSnapshotReadFailure("could not read snapshot");
        Assertions.assertSame(ex, SnapshotReadFailures.find(ex));
    }

    @Test
    void findSnapshotReadFailure_wrappedCauseMatch() {
        var cause = new FakeSnapshotReadFailure("could not read snapshot");
        var wrapper = new RuntimeException("partition migration failed", cause);
        Assertions.assertSame(cause, SnapshotReadFailures.find(wrapper));
    }

    @Test
    void findSnapshotReadFailure_nestedMarkersPrefersDeepest() {
        // Wrapper marker around a specific marker: find() returns the deepest (specific) one.
        var specific = new FakeSnapshotReadFailure("Failed to read object from S3 bucket: b, key: k");
        var wrapper = new FakeSnapshotReadFailure("Could not unpack shard: Index i, Shard s", specific);
        Assertions.assertSame(specific, SnapshotReadFailures.find(wrapper));
    }

    @Test
    void findSnapshotReadFailure_noMatchReturnsNull() {
        var ex = new RuntimeException("target cluster rejected the bulk request");
        Assertions.assertNull(SnapshotReadFailures.find(ex));
    }

    @Test
    void findSnapshotReadFailure_cyclicCauseChainDoesNotLoop() {
        // A cyclic cause chain (a -> b -> a) with no snapshot-read failure must terminate, not hang.
        var a = new RuntimeException("a");
        var b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a);
        Assertions.assertNull(SnapshotReadFailures.find(a));
    }

    @Test
    void findSnapshotReadFailure_nullReturnsNull() {
        Assertions.assertNull(SnapshotReadFailures.find(null));
    }

    @Test
    void describe_includesReasonAndSnapshotContext() {
        var failure = new FakeSnapshotReadFailure("could not read snapshot");
        var msg = SnapshotReadFailures.describe(failure, "snap1", "s3://bucket/repo", "us-east-1");
        Assertions.assertTrue(msg.contains("could not read snapshot"), msg);
        Assertions.assertTrue(msg.contains("snapshot=snap1"), msg);
        Assertions.assertTrue(msg.contains("repo=s3://bucket/repo"), msg);
        Assertions.assertTrue(msg.contains("region=us-east-1"), msg);
    }
}