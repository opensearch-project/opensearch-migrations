package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.DiagnosticPayload;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

@WrapWithNettyLeakDetection
class TransformedTargetRequestAndResponseListTest {
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
