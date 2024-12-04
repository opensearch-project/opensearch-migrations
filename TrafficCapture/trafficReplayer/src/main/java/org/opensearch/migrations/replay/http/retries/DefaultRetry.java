package org.opensearch.migrations.replay.http.retries;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.HttpByteBufFormatter;
import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponse;

public class DefaultRetry implements RequestRetryEvaluator {
    static final int MAX_RETRIES = 4;

    public static boolean retryIsUnnecessaryGivenStatusCode(int statusCode) {
        switch (statusCode) {
            case 200:
            case 201:
            case 401:
            case 403:
                return true;
            default:
                return statusCode >= 300 && statusCode < 400;
        }
    }

    public TextTrackedFuture<RequestSenderOrchestrator.RetryDirective>
    getRetryDirectiveUnlessExceededMaxRetries(List<AggregatedRawResponse> previousResponses) {
        var d = previousResponses.size() > MAX_RETRIES ?
            RequestSenderOrchestrator.RetryDirective.DONE :
            RequestSenderOrchestrator.RetryDirective.RETRY;
        return TextTrackedFuture.completedFuture(d, () -> "determined if we should retry or return the response now");
    }

    TrackedFuture<String, RequestSenderOrchestrator.RetryDirective>
    makeDeterminationFuture(RequestSenderOrchestrator.RetryDirective d, String msg) {
        return TextTrackedFuture.completedFuture(d, () -> msg);
    }

    @Override
    public TrackedFuture<String, RequestSenderOrchestrator.RetryDirective>
    shouldRetry(ByteBuf targetRequestBytes,
                List<AggregatedRawResponse> previousResponses,
                AggregatedRawResponse currentResponse,
                TrackedFuture<String, ? extends IRequestResponsePacketPair> reconstructedSourceTransactionFuture) {
        if (previousResponses.size() >= MAX_RETRIES) {
            return TextTrackedFuture.completedFuture(RequestSenderOrchestrator.RetryDirective.DONE, () -> "done");
        }
        return shouldRetry(targetRequestBytes, currentResponse, reconstructedSourceTransactionFuture);
    }

    /**
     * @param targetRequestBytes the raw request as it was sent to the target cluster, which can be useful because
     *                           of the HTTP verb and path.
     */
    public TrackedFuture<String, RequestSenderOrchestrator.RetryDirective>
    shouldRetry(ByteBuf targetRequestBytes,
                AggregatedRawResponse currentResponse,
                TrackedFuture<String, ? extends IRequestResponsePacketPair> reconstructedSourceTransactionFuture) {
        var rr = currentResponse.getRawResponse();
        if (rr != null) {
            if (retryIsUnnecessaryGivenStatusCode(rr.status().code())) {
                return makeDeterminationFuture(RequestSenderOrchestrator.RetryDirective.DONE,
                    "returning DONE because response code was terminal");
            } else {
                return reconstructedSourceTransactionFuture.thenCompose(rrp ->
                        TextTrackedFuture.completedFuture(
                            Optional.ofNullable(rrp.getResponseData())
                                .flatMap(sourceResponse ->
                                    Optional.ofNullable(HttpByteBufFormatter.processHttpMessageFromBufs(
                                        HttpByteBufFormatter.HttpMessageType.RESPONSE,
                                        Stream.of(sourceResponse.asByteBuf()))))
                                .filter(HttpResponse.class::isInstance)
                                .map(responseMsg -> shouldRetry(((HttpResponse)responseMsg).status().code(),
                                    rr.status().code()))
                                .orElse(RequestSenderOrchestrator.RetryDirective.RETRY),
                            () -> "evaluating retry status dependent upon source error field"),
                    () -> "checking the accumulated source response value");
            }
        } else {
            return TextTrackedFuture.completedFuture(RequestSenderOrchestrator.RetryDirective.RETRY, () -> "retrying");
        }
    }

    private RequestSenderOrchestrator.RetryDirective shouldRetry(int sourceStatus, int targetStatus) {
        return targetStatus >= 300 && sourceStatus < 300 ?
            RequestSenderOrchestrator.RetryDirective.RETRY :
            RequestSenderOrchestrator.RetryDirective.DONE;
    }
}
