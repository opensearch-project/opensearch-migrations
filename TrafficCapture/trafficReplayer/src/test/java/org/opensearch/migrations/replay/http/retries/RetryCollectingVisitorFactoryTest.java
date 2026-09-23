package org.opensearch.migrations.replay.http.retries;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: IRequestResponsePacketPair RequestRetryEvaluator RequestSenderOrchestrator . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.time.Duration;
import java.util.EnumMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.replay.lifecycle.ResourceOwnership;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@WrapWithNettyLeakDetection
class RetryCollectingVisitorFactoryTest {
    @Test
    void closingVisitorBeforeDecisionRejectsTerminalTransferAndReleasesDiagnosticPayload() {
        var decision = new CompletableFuture<RequestSenderOrchestrator.RetryDirective>();
        var fixture = new Fixture(
            (request, responses, current, source) ->
                new TextTrackedFuture<>(decision, "controlled retry decision")
        );
        var requestBytes = Unpooled.wrappedBuffer(new byte[] { 1 });

        try {
            var result = fixture.visitor.visit(
                requestBytes,
                new TargetAttemptOutcome.TargetResponseObtained<>(response())
            );
            fixture.visitor.close();

            Assertions.assertEquals(
                0,
                fixture.metrics.handles(ResourceOwnership.Type.DIAGNOSTIC_PAYLOAD)
            );
            decision.complete(RequestSenderOrchestrator.RetryDirective.DONE);

            var failure = Assertions.assertThrows(
                CompletionException.class,
                result.future::join
            );
            Assertions.assertEquals(
                "retry collector was closed before its decision completed",
                failure.getCause().getMessage()
            );
        } finally {
            requestBytes.release();
            fixture.close();
        }

        fixture.metrics.assertNoOwnedResources();
    }

    @Test
    void terminalDecisionTransfersDiagnosticPayloadToCloseableResultExactlyOnce() {
        var fixture = new Fixture(
            (request, responses, current, source) -> TextTrackedFuture.completedFuture(
                RequestSenderOrchestrator.RetryDirective.DONE,
                () -> "terminal retry decision"
            )
        );
        var requestBytes = Unpooled.wrappedBuffer(new byte[] { 1 });

        try {
            var result = fixture.visitor.visit(
                requestBytes,
                new TargetAttemptOutcome.TargetResponseObtained<>(response())
            ).future.join();
            fixture.visitor.close();

            Assertions.assertEquals(
                1,
                fixture.metrics.handles(ResourceOwnership.Type.DIAGNOSTIC_PAYLOAD)
            );
            result.close();
            result.close();
            Assertions.assertEquals(
                0,
                fixture.metrics.handles(ResourceOwnership.Type.DIAGNOSTIC_PAYLOAD)
            );
        } finally {
            requestBytes.release();
            fixture.close();
        }

        fixture.metrics.assertNoOwnedResources();
    }

    @Test
    void synchronousRetryPolicyFailurePropagatesWithoutBecomingRetry() {
        var policyFailure = new IllegalStateException("retry policy invariant failed");
        var fixture = new Fixture((request, responses, current, source) -> {
            throw policyFailure;
        });
        var requestBytes = Unpooled.wrappedBuffer(new byte[] { 1 });

        try {
            var failure = Assertions.assertThrows(
                IllegalStateException.class,
                () -> fixture.visitor.visit(
                    requestBytes,
                    new TargetAttemptOutcome.TargetResponseObtained<>(response())
                )
            );
            Assertions.assertSame(policyFailure, failure);
        } finally {
            requestBytes.release();
            fixture.close();
        }

        fixture.metrics.assertNoOwnedResources();
    }

    @Test
    void asynchronousRetryPolicyFailurePropagatesWithoutBecomingRetry() {
        var policyFailure = new IllegalStateException("asynchronous retry policy invariant failed");
        var fixture = new Fixture(
            (request, responses, current, source) ->
                TextTrackedFuture.failedFuture(policyFailure, () -> "failed retry policy")
        );
        var requestBytes = Unpooled.wrappedBuffer(new byte[] { 1 });

        try {
            var result = fixture.visitor.visit(
                requestBytes,
                new TargetAttemptOutcome.TargetResponseObtained<>(response())
            );
            var failure = Assertions.assertThrows(
                CompletionException.class,
                result.future::join
            );
            Assertions.assertSame(policyFailure, failure.getCause());
        } finally {
            requestBytes.release();
            fixture.close();
        }

        fixture.metrics.assertNoOwnedResources();
    }

    private static AggregatedRawResponse response() {
        return new AggregatedRawResponse(null, 0, Duration.ZERO, null, null);
    }

    private static TrackedFuture<String, IRequestResponsePacketPair> sourceTransaction() {
        return TextTrackedFuture.completedFuture(null, () -> "unused source transaction");
    }

    private static final class Fixture implements AutoCloseable {
        private final RecordingOwnershipMetrics metrics = new RecordingOwnershipMetrics();
        private final ByteBufListProducer producer;
        private final RequestSenderOrchestrator.RetryVisitor<?> visitor;

        private Fixture(RequestRetryEvaluator evaluator) {
            var source = Unpooled.wrappedBuffer(new byte[] { 1 });
            var packets = new ByteBufList(source);
            source.release();
            producer = ByteBufListProducer.of(packets);
            producer.trackOwnership(metrics);
            visitor = new RetryCollectingVisitorFactory(evaluator).getRetryCheckVisitor(
                new TransformedOutputAndResult<>(
                    producer,
                    HttpRequestTransformationStatus.completed()
                ),
                sourceTransaction()
            );
            producer.close();
        }

        @Override
        public void close() {
            visitor.close();
            if (producer.refCnt() > 0) {
                producer.close();
            }
        }
    }

    private static final class RecordingOwnershipMetrics implements ResourceOwnership.Metrics {
        private final EnumMap<ResourceOwnership.Type, Integer> handles =
            new EnumMap<>(ResourceOwnership.Type.class);

        @Override
        public void ownershipChanged(
            ResourceOwnership.Type type,
            int handleDelta,
            int bufferDelta,
            long byteDelta
        ) {
            handles.merge(type, handleDelta, Integer::sum);
        }

        private int handles(ResourceOwnership.Type type) {
            return handles.getOrDefault(type, 0);
        }

        private void assertNoOwnedResources() {
            for (var type : ResourceOwnership.Type.values()) {
                Assertions.assertEquals(0, handles(type), "leaked " + type);
            }
        }
    }
}

*/
// REBUILD-LIMBO-END(G10)