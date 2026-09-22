package org.opensearch.migrations.replay;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;

import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RequestSenderTargetAttemptOutcomeTest {
    @Test
    void responseReadTimeoutIsTypedNoResponse() {
        var outcome = RequestSenderOrchestrator.classifyTargetAttemptOutcome(
            response(null, ReadTimeoutException.INSTANCE)
        );

        var noResponse = Assertions.assertInstanceOf(
            TargetAttemptOutcome.NoTargetResponseObtained.class,
            outcome
        );
        Assertions.assertTrue(noResponse.reason().contains(ReadTimeoutException.class.getName()));
    }

    @Test
    void transportIoFailureIsTypedNoResponse() {
        var transportFailure = new IOException("connection reset");

        var outcome = RequestSenderOrchestrator.classifyTargetAttemptOutcome(
            response(null, transportFailure)
        );

        var noResponse = Assertions.assertInstanceOf(
            TargetAttemptOutcome.NoTargetResponseObtained.class,
            outcome
        );
        Assertions.assertTrue(noResponse.reason().contains(IOException.class.getName()));
        Assertions.assertTrue(noResponse.reason().contains("connection reset"));
    }

    @Test
    void wrappedTransportIoFailureIsTypedNoResponse() {
        var transportFailure = new IOException("connection reset");

        var outcome = RequestSenderOrchestrator.classifyTargetAttemptOutcome(
            response(null, new CompletionException(transportFailure))
        );

        var noResponse = Assertions.assertInstanceOf(
            TargetAttemptOutcome.NoTargetResponseObtained.class,
            outcome
        );
        Assertions.assertTrue(noResponse.reason().contains(IOException.class.getName()));
        Assertions.assertTrue(noResponse.reason().contains("connection reset"));
    }

    @Test
    void completeHttpErrorResponseIsStillAnObtainedResponse() {
        var rawResponse = new DefaultHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.BAD_GATEWAY
        );
        var response = response(rawResponse, null);

        var outcome = RequestSenderOrchestrator.classifyTargetAttemptOutcome(response);

        var obtained = Assertions.assertInstanceOf(
            TargetAttemptOutcome.TargetResponseObtained.class,
            outcome
        );
        Assertions.assertSame(response, obtained.response());
    }

    @Test
    void unexpectedResponseFailureRemainsExceptionalEvenAfterHeaders() {
        var invariantFailure = new IllegalStateException(
            "channel failed after response headers"
        );
        var rawResponse = new DefaultHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.BAD_GATEWAY
        );

        var failure = Assertions.assertThrows(
            IllegalStateException.class,
            () -> RequestSenderOrchestrator.classifyTargetAttemptOutcome(
                response(rawResponse, invariantFailure)
            )
        );

        Assertions.assertSame(invariantFailure, failure);
    }

    @Test
    void completedAttemptWithoutHttpResponseIsTypedNoResponse() {
        var outcome = RequestSenderOrchestrator.classifyTargetAttemptOutcome(
            response(null, null)
        );

        var noResponse = Assertions.assertInstanceOf(
            TargetAttemptOutcome.NoTargetResponseObtained.class,
            outcome
        );
        Assertions.assertEquals(
            "target attempt completed without an HTTP response",
            noResponse.reason()
        );
    }

    @Test
    void missingResponseValueIsAnInvariantFailure() {
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> RequestSenderOrchestrator.classifyTargetAttemptOutcome(null)
        );
    }

    private static AggregatedRawResponse response(
        io.netty.handler.codec.http.HttpResponse rawResponse,
        Throwable failure
    ) {
        return new AggregatedRawResponse(
            rawResponse,
            0,
            Duration.ZERO,
            List.of(),
            failure
        );
    }
}
