package org.opensearch.migrations.replay.http.retries;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;
import io.netty.handler.timeout.ReadTimeoutException;

public class NoRetryEvaluatorFactory implements IRetryVisitorFactory<AggregatedRawResponse> {

    public static class NoRetryVisitor
        implements RequestSenderOrchestrator.RetryVisitor<AggregatedRawResponse> {
        @Override
        public TrackedFuture<String, RequestSenderOrchestrator.DeterminedTransformedResponse<AggregatedRawResponse>>
        visit(ByteBuf requestBytes, TargetAttemptOutcome<AggregatedRawResponse> outcome) {
            return outcome.visit(new TargetAttemptOutcome.Visitor<>() {
                @Override
                public TrackedFuture<String, RequestSenderOrchestrator.DeterminedTransformedResponse<
                    AggregatedRawResponse>> onTargetResponseObtained(
                    TargetAttemptOutcome.TargetResponseObtained<AggregatedRawResponse> obtained
                ) {
                    return completed(obtained.response());
                }

                @Override
                public TrackedFuture<String, RequestSenderOrchestrator.DeterminedTransformedResponse<
                    AggregatedRawResponse>> onNoTargetResponseObtained(
                    TargetAttemptOutcome.NoTargetResponseObtained<AggregatedRawResponse> notObtained
                ) {
                    var failure = switch (notObtained.diagnostic().kind()) {
                        case READ_TIMEOUT -> ReadTimeoutException.INSTANCE;
                        case TRANSPORT_FAILURE -> new IOException(notObtained.reason());
                        case MISSING_HTTP_RESPONSE -> new IllegalStateException(
                            notObtained.reason()
                        );
                    };
                    return completed(new AggregatedRawResponse(
                        null,
                        0,
                        Duration.ZERO,
                        List.of(),
                        failure
                    ));
                }
            });
        }

        private TrackedFuture<String, RequestSenderOrchestrator.DeterminedTransformedResponse<
            AggregatedRawResponse>> completed(AggregatedRawResponse response) {
            return TextTrackedFuture.completedFuture(
                new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                    RequestSenderOrchestrator.RetryDirective.DONE,
                    response
                ),
                () -> "returning DONE immediately because this NoRetry factory never retries"
            );
        }
    }

    @Override
    public RequestSenderOrchestrator.RetryVisitor<AggregatedRawResponse>
    getRetryCheckVisitor(TransformedOutputAndResult<ByteBufListProducer> transformedResult,
                         TrackedFuture<String, ? extends IRequestResponsePacketPair> accumulationResponseFuture) {
        return new NoRetryVisitor();
    }
}
