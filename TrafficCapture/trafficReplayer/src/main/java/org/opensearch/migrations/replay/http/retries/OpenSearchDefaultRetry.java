package org.opensearch.migrations.replay.http.retries;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.HttpByteBufFormatter;
import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RetryDecision;
import org.opensearch.migrations.replay.lifecycle.RequestReplayOwner;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteBufferFeeder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;


/**
 * Deployed OpenSearch retry policy for the rebuilt request owner.
 *
 * <p>The transformed request is classified once during preparation. Retry evaluation therefore
 * retains the inherited bulk semantics without reparsing or borrowing the request's owned buffers.
 */
@Slf4j
public final class OpenSearchDefaultRetry implements RequestReplayOwner.RetryPolicy<
    NettyPacketToHttpConsumer.PreparedRequest,
    AggregatedRawResponse,
    HttpMessageAndTimestamp.Response
> {
    public enum BulkResponseAnalysis {
        /** No errors at all */
        NO_ERRORS,
        /** Has errors, but at least one is retryable */
        HAS_RETRYABLE_ERRORS,
        /** Has errors, but ALL are non-retryable */
        ONLY_NON_RETRYABLE_ERRORS
    }

    @Value
    @Accessors(fluent = true)
    public static class BulkResponseInspection {
        BulkResponseAnalysis analysis;
        Set<String> errorTypes;
    }

    /**
     * Streaming JSON analyzer that processes bulk response chunks as they arrive.
     * Uses Jackson's non-blocking parser to avoid buffering the entire response body.
     * Short-circuits as soon as a determination can be made (e.g. "errors":false).
     */
    static class BulkResponseAnalyzer extends ChannelInboundHandlerAdapter {
        private final JsonParser parser;
        private final ByteBufferFeeder feeder;
        private final BulkItemErrorClassifier errorClassifier;
        private BulkResponseAnalysis result = null;

        // Parsing state
        private Boolean errorsFieldValue = null;
        private boolean inItems = false;
        private int itemDepth = 0;
        private boolean inErrorObject = false;
        private int errorObjectDepth = 0;
        private boolean hasAnyError = false;
        private boolean hasRetryableError = false;
        private String pendingFieldName = null;
        private boolean parseFailed = false;

        private boolean foundTypeInCurrentError = false;
        private final Set<String> errorTypes = new LinkedHashSet<>();

        @SneakyThrows
        public BulkResponseAnalyzer(BulkItemErrorClassifier errorClassifier) {
            this.errorClassifier = errorClassifier;
            var jsonFactory = new JsonFactory();
            parser = jsonFactory.createNonBlockingByteBufferParser();
            feeder = (ByteBufferFeeder) parser.getNonBlockingInputFeeder();
        }

        BulkResponseAnalysis getAnalysis() {
            return result;
        }

        Set<String> getErrorTypes() {
            return Set.copyOf(errorTypes);
        }

        @Override
        public void channelRead(@NonNull ChannelHandlerContext ctx, @NonNull Object msg) throws Exception {
            if (msg instanceof HttpContent && result == null && !parseFailed) {
                feeder.feedInput(((HttpContent) msg).content().nioBuffer());
                consumeTokens();
                if (msg instanceof LastHttpContent) {
                    feeder.endOfInput();
                    consumeTokens();
                    if (result == null && !parseFailed) {
                        result = finalizeAnalysis();
                    }
                }
            }
            ctx.fireChannelRead(msg);
        }

        private void consumeTokens() {
            if (result != null || parseFailed) {
                return;
            }
            try {
                parseTokens();
            } catch (Exception e) {
                log.atWarn().setCause(e)
                    .setMessage("Failed to parse bulk response body, falling back to status code comparison")
                    .log();
                parseFailed = true;
            }
        }

        @SuppressWarnings("java:S3776") // Cognitive complexity — streaming parser requires state tracking
        private void parseTokens() throws IOException {
            JsonToken token;
            while (result == null
                && !parser.isClosed()
                && (token = parser.nextToken()) != null
                && token != JsonToken.NOT_AVAILABLE)
            {
                if (token == JsonToken.FIELD_NAME) {
                    pendingFieldName = parser.currentName();
                    continue;
                }

                // Root-level "errors" field
                if ("errors".equals(pendingFieldName) && !inItems) {
                    boolean errors = parser.getValueAsBoolean();
                    errorsFieldValue = errors;
                    if (!errors) {
                        result = BulkResponseAnalysis.NO_ERRORS;
                        return;
                    }
                    pendingFieldName = null;
                    continue;
                }

                // Entering "items" array
                if ("items".equals(pendingFieldName) && token == JsonToken.START_ARRAY) {
                    inItems = true;
                    pendingFieldName = null;
                    continue;
                }

                if (inItems) {
                    processItemToken(token);
                    if (hasRetryableError) {
                        result = BulkResponseAnalysis.HAS_RETRYABLE_ERRORS;
                        return;
                    }
                }
                pendingFieldName = null;
            }
        }

        private void processItemToken(JsonToken token) throws IOException {
            if (token == JsonToken.END_ARRAY && itemDepth == 0) {
                inItems = false;
                return;
            }
            trackObjectDepth(token);
            processErrorFields(token);
        }

        private void trackObjectDepth(JsonToken token) {
            if (token == JsonToken.START_OBJECT) {
                itemDepth++;
            } else if (token == JsonToken.END_OBJECT) {
                if (inErrorObject && itemDepth == errorObjectDepth) {
                    if (!foundTypeInCurrentError) {
                        hasRetryableError = true;
                    }
                    inErrorObject = false;
                }
                itemDepth--;
            }
        }

        private void processErrorFields(JsonToken token) throws IOException {
            if (inErrorObject && "type".equals(pendingFieldName) && token.isScalarValue()) {
                hasAnyError = true;
                foundTypeInCurrentError = true;
                var errorType = parser.getValueAsString();
                errorTypes.add(errorType);
                if (!errorClassifier.isNonRetryable(errorType)) {
                    log.atDebug().setMessage("Found retryable bulk item error type: {}")
                        .addArgument(errorType).log();
                    hasRetryableError = true;
                }
            } else if ("error".equals(pendingFieldName) && token == JsonToken.START_OBJECT) {
                hasAnyError = true;
                inErrorObject = true;
                errorObjectDepth = itemDepth;
                foundTypeInCurrentError = false;
            } else if ("error".equals(pendingFieldName) && token.isScalarValue()) {
                hasAnyError = true;
                hasRetryableError = true;
            }
        }

        private BulkResponseAnalysis finalizeAnalysis() {
            if (errorsFieldValue != null && !errorsFieldValue) {
                return BulkResponseAnalysis.NO_ERRORS;
            }
            if (hasRetryableError) {
                return BulkResponseAnalysis.HAS_RETRYABLE_ERRORS;
            }
            if (hasAnyError) {
                return BulkResponseAnalysis.ONLY_NON_RETRYABLE_ERRORS;
            }
            // No error items found — trust the top-level "errors" field
            if (errorsFieldValue != null) {
                return BulkResponseAnalysis.HAS_RETRYABLE_ERRORS;
            }
            return BulkResponseAnalysis.NO_ERRORS;
        }
    }

    BulkResponseAnalysis analyzeBulkResponse(ByteBuf responseByteBuf) {
        return inspectBulkResponse(responseByteBuf).analysis();
    }

    public BulkResponseInspection inspectBulkResponse(ByteBuf responseByteBuf) {
        return inspectBulkResponse(responseByteBuf, errorClassifier);
    }

    public static BulkResponseInspection inspectBulkResponse(
        ByteBuf responseByteBuf,
        BulkItemErrorClassifier errorClassifier
    ) {
        var analyzer = new BulkResponseAnalyzer(errorClassifier);
        HttpByteBufFormatter.processHttpMessageFromBufs(
            HttpByteBufFormatter.HttpMessageType.RESPONSE,
            Stream.of(responseByteBuf),
            analyzer
        );
        return new BulkResponseInspection(analyzer.getAnalysis(), analyzer.getErrorTypes());
    }

    private static final int MAXIMUM_BACKOFF_SHIFT = 62;
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofMillis(100);
    private static final Duration MAXIMUM_RETRY_DELAY = Duration.ofSeconds(300);

    private final BulkItemErrorClassifier errorClassifier;

    public OpenSearchDefaultRetry() {
        this(new BulkItemErrorClassifier());
    }

    public OpenSearchDefaultRetry(@NonNull BulkItemErrorClassifier errorClassifier) {
        this.errorClassifier = errorClassifier;
    }

    @Override
    public boolean requiresSourceResponse(
        NettyPacketToHttpConsumer.PreparedRequest preparedRequest,
        AggregatedRawResponse targetResponse
    ) {
        var status = targetStatus(targetResponse);
        if (status.isEmpty()) {
            return false;
        }
        if (preparedRequest.retryRequestKind()
            == NettyPacketToHttpConsumer.PreparedRequest.RetryRequestKind.BULK) {
            var code = status.getAsInt();
            if (code == 200 || code == 429 || code / 100 == 5) {
                return false;
            }
        }
        return !retryIsUnnecessaryGivenStatusCode(status.getAsInt());
    }

    @Override
    public RetryDecision decide(
        NettyPacketToHttpConsumer.PreparedRequest preparedRequest,
        AggregatedRawResponse targetResponse,
        RequestReplayOwner.RetrySourceResponse<HttpMessageAndTimestamp.Response> sourceResponse
    ) {
        var status = targetStatus(targetResponse);
        if (status.isEmpty()) {
            return new RetryDecision.RetryRequired();
        }
        var targetStatus = status.getAsInt();
        if (preparedRequest.retryRequestKind()
            == NettyPacketToHttpConsumer.PreparedRequest.RetryRequestKind.BULK) {
            if (targetStatus == 429 || targetStatus / 100 == 5) {
                return new RetryDecision.RetryRequired();
            }
            if (targetStatus == 200) {
                var inspection = inspectBulkResponse(targetResponse, errorClassifier);
                if (inspection.analysis() == BulkResponseAnalysis.HAS_RETRYABLE_ERRORS) {
                    return new RetryDecision.RetryRequired();
                }
                if (inspection.analysis() != null) {
                    return new RetryDecision.TargetServerAttemptsFinished();
                }
            }
        }
        if (retryIsUnnecessaryGivenStatusCode(targetStatus)) {
            return new RetryDecision.TargetServerAttemptsFinished();
        }
        var sourceStatus = sourceStatus(sourceResponse);
        if (sourceStatus.isEmpty()) {
            return new RetryDecision.RetryRequired();
        }
        return targetStatus >= 300 && sourceStatus.getAsInt() < 300
            ? new RetryDecision.RetryRequired()
            : new RetryDecision.TargetServerAttemptsFinished();
    }

    @Override
    public Duration retryDelay(int completedAttemptCount) {
        if (completedAttemptCount <= 0) {
            throw new IllegalArgumentException("completedAttemptCount must be positive");
        }
        var shift = Math.min(completedAttemptCount - 1, MAXIMUM_BACKOFF_SHIFT);
        var multiplier = 1L << shift;
        final long delayMillis;
        try {
            delayMillis = Math.multiplyExact(
                INITIAL_RETRY_DELAY.toMillis(),
                multiplier
            );
        } catch (ArithmeticException overflow) {
            return MAXIMUM_RETRY_DELAY;
        }
        return Duration.ofMillis(
            Math.min(delayMillis, MAXIMUM_RETRY_DELAY.toMillis())
        );
    }

    private static BulkResponseInspection inspectBulkResponse(
        AggregatedRawResponse response,
        BulkItemErrorClassifier errorClassifier
    ) {
        var responseBytes = response.getResponseAsByteBuf();
        try {
            return inspectBulkResponse(responseBytes, errorClassifier);
        } finally {
            if (responseBytes.refCnt() > 0) {
                responseBytes.release();
            }
        }
    }

    private static boolean retryIsUnnecessaryGivenStatusCode(int statusCode) {
        return switch (statusCode) {
            case 200, 201, 401, 403 -> true;
            default -> statusCode >= 300 && statusCode < 400;
        };
    }

    private static OptionalInt targetStatus(AggregatedRawResponse response) {
        return response.getRawResponse() == null
            ? OptionalInt.empty()
            : OptionalInt.of(response.getRawResponse().status().code());
    }

    private static OptionalInt sourceStatus(
        RequestReplayOwner.RetrySourceResponse<HttpMessageAndTimestamp.Response> source
    ) {
        if (!(source
            instanceof RequestReplayOwner.CompleteSourceResponseForRetry<
                HttpMessageAndTimestamp.Response
            > complete)) {
            return OptionalInt.empty();
        }
        var responseBytes = complete.response().asByteBuf();
        try {
            var parsed = HttpByteBufFormatter.processHttpMessageFromBufs(
                HttpByteBufFormatter.HttpMessageType.RESPONSE,
                Stream.of(responseBytes)
            );
            return parsed instanceof HttpResponse response
                ? OptionalInt.of(response.status().code())
                : OptionalInt.empty();
        } finally {
            if (responseBytes.refCnt() > 0) {
                responseBytes.release();
            }
        }
    }

}
