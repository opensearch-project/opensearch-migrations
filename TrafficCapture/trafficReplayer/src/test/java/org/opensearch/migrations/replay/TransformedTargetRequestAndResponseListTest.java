package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: IReplayContexts . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.DiagnosticPayload;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome.NoTargetResponseDiagnostic;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome.NoTargetResponseKind;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

@WrapWithNettyLeakDetection
class TransformedTargetRequestAndResponseListTest {
    @Test
    void retainsOrderedTypedAttemptHistoryAndProjectsOnlyObtainedResponses() {
        var firstResponse = new AggregatedRawResponse(null, 0, Duration.ZERO, null, null);
        var secondResponse = new AggregatedRawResponse(null, 0, Duration.ZERO, null, null);
        var noResponse = new TargetAttemptOutcome.NoTargetResponseObtained<AggregatedRawResponse>(
            new NoTargetResponseDiagnostic(
                NoTargetResponseKind.TRANSPORT_FAILURE,
                "target closed before responding"
            )
        );
        var summary = new TransformedTargetRequestAndResponseList(
            null,
            HttpRequestTransformationStatus.completed(),
            firstResponse
        );

        summary.addAttemptOutcome(noResponse);
        summary.addResponse(secondResponse);

        Assertions.assertEquals(
            List.of(
                new TargetAttemptOutcome.TargetResponseObtained<>(firstResponse),
                noResponse,
                new TargetAttemptOutcome.TargetResponseObtained<>(secondResponse)
            ),
            summary.attemptHistory()
        );
        Assertions.assertEquals(List.of(firstResponse, secondResponse), summary.responses());
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> summary.attemptHistory().clear()
        );
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> summary.responses().clear()
        );
        summary.close();
    }

    @Test
    void diagnosticPayloadCanBeClaimedExactlyOnce() {
        var source = Unpooled.wrappedBuffer("request".getBytes(StandardCharsets.UTF_8));
        var packets = new ByteBufList(source);
        source.release();
        var summary = new TransformedTargetRequestAndResponseList(
            new DiagnosticPayload(packets),
            HttpRequestTransformationStatus.completed()
        );

        var payload = summary.claimDiagnosticPayload();

        Assertions.assertSame(packets, payload.packets());
        Assertions.assertThrows(IllegalStateException.class, summary::claimDiagnosticPayload);
        summary.close();
        Assertions.assertFalse(payload.isClosed());
        payload.close();
        payload.close();
        Assertions.assertTrue(payload.isClosed());
    }

    @Test
    void closingUnclaimedSummaryClosesDiagnosticPayload() {
        var source = Unpooled.wrappedBuffer("request".getBytes(StandardCharsets.UTF_8));
        var packets = new ByteBufList(source);
        source.release();
        var payload = new DiagnosticPayload(packets);
        var summary = new TransformedTargetRequestAndResponseList(
            payload,
            HttpRequestTransformationStatus.completed()
        );

        summary.close();
        summary.close();

        Assertions.assertTrue(payload.isClosed());
    }

    @Test
    void tupleConstructionFailureLeavesDiagnosticPayloadWithTheSummary() {
        var source = Unpooled.wrappedBuffer("request".getBytes(StandardCharsets.UTF_8));
        var packets = new ByteBufList(source);
        source.release();
        var payload = new DiagnosticPayload(packets);
        var malformedResponse = new AggregatedRawResponse(null, 0, Duration.ZERO, null, null);
        var summary = new TransformedTargetRequestAndResponseList(
            payload,
            HttpRequestTransformationStatus.completed(),
            malformedResponse
        );

        Assertions.assertThrows(
            NullPointerException.class,
            () -> new SourceTargetCaptureTuple(
                mock(IReplayContexts.ITupleHandlingContext.class),
                null,
                summary,
                null
            )
        );
        Assertions.assertFalse(payload.isClosed());

        summary.close();
        Assertions.assertTrue(payload.isClosed());
    }
}

*/
// REBUILD-LIMBO-END(G10)