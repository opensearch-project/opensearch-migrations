package org.opensearch.migrations.replay.http.retries;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TransformedTargetRequestAndResponseList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RetryCollectingVisitorFactory implements IRetryVisitorFactory<TransformedTargetRequestAndResponseList> {
    private final RequestRetryEvaluator shouldRetry;

    public RetryCollectingVisitorFactory(RequestRetryEvaluator shouldRetry) {
        this.shouldRetry = shouldRetry;
    }

    @Override
    public RequestSenderOrchestrator.RetryVisitor<TransformedTargetRequestAndResponseList>
    getRetryCheckVisitor(TransformedOutputAndResult<ByteBufListProducer> transformedResult,
                         TrackedFuture<String, ? extends IRequestResponsePacketPair> finishedAccumulatingResponseFuture) {
        var collector = new TransformedTargetRequestAndResponseList(
            transformedResult.transformedOutput.get(),
            transformedResult.transformationStatus);
        var exceptionRetryCount = new AtomicInteger(0);
        return (requestBytes, aggResponse, t) -> {
            if (t != null) {
                var retryNum = exceptionRetryCount.incrementAndGet();
                log.atWarn().setMessage("Request exception on attempt {} — retrying. exceptionType={} message={}")
                    .addArgument(retryNum)
                    .addArgument(() -> t.getClass().getSimpleName())
                    .addArgument(t::getMessage)
                    .log();
                return TextTrackedFuture.completedFuture(
                    new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                        RequestSenderOrchestrator.RetryDirective.RETRY,
                        null),
                    () -> "Returning a future to retry due to an exception (attempt " + retryNum + "): " + t);
            } else {
                assert (aggResponse != null);
                collector.addResponse(aggResponse);
                return shouldRetry.shouldRetry(requestBytes, Collections.unmodifiableList(collector.getResponseList()),
                        aggResponse, finishedAccumulatingResponseFuture)
                    .thenCompose(d -> TextTrackedFuture.completedFuture(
                            new RequestSenderOrchestrator.DeterminedTransformedResponse<>(d, collector),
                            () -> "Returning a future to retry due to a connection exception"),
                        () -> "determining if we should retry or just return the response now");
            }
        };
    }
}
