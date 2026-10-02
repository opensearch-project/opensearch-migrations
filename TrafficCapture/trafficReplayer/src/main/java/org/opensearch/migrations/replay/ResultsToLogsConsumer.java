package org.opensearch.migrations.replay;

import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.replay.lifecycle.RequestReplayOwner;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Deployed progress and tuple-log output for the rebuilt replay chain.
 *
 * <p>The progress summary is produced from the request result before worker transformation. The
 * JSON tuple is emitted by the physical worker sink after transformation. Keeping these as separate
 * responsibilities preserves the two established logger streams without adding bookkeeping fields
 * to tuple JSON or S3 objects.</p>
 */
@Slf4j
public final class ResultsToLogsConsumer {
    public static final String OUTPUT_TUPLE_JSON_LOGGER = "OutputTupleJsonLogger";
    public static final String TRANSACTION_SUMMARY_LOGGER = "TransactionSummaryLogger";
    private static final String MISSING_STR = "-";
    private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper();

    private final Logger tupleLogger;
    private final Logger progressLogger;
    private final AtomicInteger tupleCounter;

    public ResultsToLogsConsumer() {
        this(null, null);
    }

    ResultsToLogsConsumer(
        Logger tupleLogger,
        Logger progressLogger
    ) {
        this.tupleLogger = tupleLogger != null
            ? tupleLogger
            : LoggerFactory.getLogger(OUTPUT_TUPLE_JSON_LOGGER);
        this.progressLogger = progressLogger != null
            ? progressLogger
            : makeTransactionSummaryLogger();
        tupleCounter = new AtomicInteger();
    }

    private static Logger makeTransactionSummaryLogger() {
        var logger = LoggerFactory.getLogger(TRANSACTION_SUMMARY_LOGGER);
        logger.atDebug().setMessage("{}").addArgument(ResultsToLogsConsumer::getTransactionSummaryStringPreamble).log();
        return logger;
    }

    public Map<String, Object> createTupleAndReportProgress(
        IReplayContexts.ITupleHandlingContext replayContext,
        RequestReplayOwner.RequestResult<
            HttpMessageAndTimestamp.Request,
            NettyPacketToHttpConsumer.PreparedRequest,
            AggregatedRawResponse,
            HttpMessageAndTimestamp.Response
        > result
    ) {
        var parsed = new ParsedHttpMessagesAsDicts(replayContext, result);
        final var index = tupleCounter.getAndIncrement();
        progressLogger.atInfo().setMessage("{}")
            .addArgument(() -> toTransactionSummaryString(index, result, parsed)).log();
        return toJSONObject(result, parsed);
    }

    private Map<String, Object> toJSONObject(
        RequestReplayOwner.RequestResult<
            HttpMessageAndTimestamp.Request,
            NettyPacketToHttpConsumer.PreparedRequest,
            AggregatedRawResponse,
            HttpMessageAndTimestamp.Response
        > result,
        ParsedHttpMessagesAsDicts parsed
    ) {
        return parsed.toTupleMap(result);
    }

    public TrafficReplayerTopLevel.ManagedPhysicalTupleSinkFactory<
        Map<String, Object>
    > tupleSinkFactory() {
        return ignoredWorkerIndex -> new TrafficReplayerTopLevel.ManagedPhysicalTupleSink<>() {
            @Override
            public CompletionStage<Void> write(
                IReplayContexts.ITupleHandlingContext replayContext,
                Map<String, Object> tuple
            ) {
                if (!tupleLogger.isInfoEnabled()) {
                    return CompletableFuture.completedFuture(null);
                }
                try {
                    var tupleString = PLAIN_MAPPER.writeValueAsString(tuple);
                    tupleLogger.atInfo().setMessage("{}").addArgument(tupleString).log();
                    return CompletableFuture.completedFuture(null);
                } catch (Exception failure) {
                    log.atError().setCause(failure).setMessage("Exception converting tuple to string").log();
                    tupleLogger.atInfo().setMessage("{ \"error\":\"{}\" }").addArgument(failure::getMessage).log();
                    return CompletableFuture.failedFuture(failure);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
    }

    public static String getTransactionSummaryStringPreamble() {
        return new StringJoiner(", ").add("#")
            .add("REQUEST_ID")
            .add("ORIGINAL_TIMESTAMP")
            .add("SOURCE_REQUEST_SIZE_BYTES/TARGET_REQUEST_SIZE_BYTES")
            .add("SOURCE_STATUS_CODE/TARGET_STATUS_CODE...")
            .add("SOURCE_RESPONSE_SIZE_BYTES/TARGET_RESPONSE_SIZE_BYTES...")
            .add("SOURCE_LATENCY_MS/TARGET_LATENCY_MS...")
            .add("METHOD...")
            .add("URI...")
            .toString();
    }

    public static String toTransactionSummaryString(
        int index,
        RequestReplayOwner.RequestResult<
            HttpMessageAndTimestamp.Request,
            NettyPacketToHttpConsumer.PreparedRequest,
            AggregatedRawResponse,
            HttpMessageAndTimestamp.Response
        > result,
        ParsedHttpMessagesAsDicts parsed
    ) {
        var sourceResponse = parsed.sourceResponseOp;
        return new StringJoiner(", ").add(Integer.toString(index))
            // REQUEST_ID
            .add(formatUniqueRequestKey(result.requestId()))
            // Original request timestamp
            .add(
                Optional.ofNullable(result.sourceRequest().getLastPacketTimestamp())
                .map(Object::toString)
                .orElse(MISSING_STR))
            // SOURCE/TARGET REQUEST_SIZE_BYTES
            .add(
                result.sourceRequest().stream().mapToInt(bytes -> bytes.length).sum()
                    + "/"
                    + preparedRequestSize(result.preparedRequest())
            )
            // SOURCE/TARGET STATUS_CODE
            .add(
                sourceResponse.map(r -> "" + r.get(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY)).orElse(MISSING_STR)
                    + "/" +
                    transformStreamToString(parsed.targetResponseList.stream(),
                        r -> "" + r.get(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY))
            )
            // SOURCE/TARGET RESPONSE_SIZE_BYTES
            .add(
                sourceResponseSize(result)
                    + "/" +
                    transformStreamToString(result.targetAttemptHistory().stream(),
                        attempt -> switch (attempt) {
                            case TargetAttemptOutcome.TargetResponseObtained<AggregatedRawResponse> obtained ->
                                obtained.response().getSizeInBytes() + "";
                            case TargetAttemptOutcome.NoTargetResponseObtained<AggregatedRawResponse> ignored ->
                                "0";
                        })
            )
            // SOURCE/TARGET LATENCY
            .add(
                sourceResponse.map(r -> "" + r.get(ParsedHttpMessagesAsDicts.RESPONSE_TIME_MS_KEY)).orElse(MISSING_STR)
                    + "/" +
                    transformStreamToString(parsed.targetResponseList.stream(),
                        r -> "" + r.get(ParsedHttpMessagesAsDicts.RESPONSE_TIME_MS_KEY))
            )
            // method
            .add(
                parsed.sourceRequestOp
                    .map(r -> (String) r.get(ParsedHttpMessagesAsDicts.METHOD_KEY))
                    .orElse(MISSING_STR))
            // uri
            .add(
                parsed.sourceRequestOp
                    .map(r -> (String) r.get(ParsedHttpMessagesAsDicts.REQUEST_URI_KEY))
                    .orElse(MISSING_STR))
            .toString();
    }

    private static String formatUniqueRequestKey(ReplayRequestId requestId) {
        return requestId.connectionProcessingId()
            .capturedConnectionId()
            .connectionId()
            + "."
            + requestId.capturedRequestOrdinal();
    }

    private static String preparedRequestSize(
        NettyPacketToHttpConsumer.PreparedRequest preparedRequest
    ) {
        if (preparedRequest == null) {
            return MISSING_STR;
        }
        try (var diagnostic = preparedRequest.request().retainDiagnosticCopy()) {
            return Long.toString(diagnostic.packets().readableBytes());
        }
    }

    private static String sourceResponseSize(
        RequestReplayOwner.RequestResult<
            HttpMessageAndTimestamp.Request,
            NettyPacketToHttpConsumer.PreparedRequest,
            AggregatedRawResponse,
            HttpMessageAndTimestamp.Response
        > result
    ) {
        if (!(result.finalSourceResponse()
            instanceof RequestReplayOwner.CompleteFinalSourceResponse<
                HttpMessageAndTimestamp.Response
            > complete)) {
            return MISSING_STR;
        }
        return Integer.toString(
            complete.response().stream().mapToInt(bytes -> bytes.length).sum()
        );
    }

    private static <T> String transformStreamToString(
        Stream<T> stream,
        Function<T, String> mapFunction
    ) {
        return Stream.of(stream
                .map(mapFunction)
                .collect(Collectors.joining(",")))
            .filter(Predicate.not(String::isEmpty))
            .findFirst()
            .orElse(MISSING_STR);
    }
}
