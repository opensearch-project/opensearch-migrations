package org.opensearch.migrations.replay.http.retries;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.HttpByteBufFormatter;
import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteBufferFeeder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OpenSearchDefaultRetry extends DefaultRetry {

    private static final Pattern bulkPathMatcher = Pattern.compile("^(/[^/]*)?/_bulk(/.*)?$");
    private final BulkItemErrorClassifier errorClassifier;

    public OpenSearchDefaultRetry() {
        this(new BulkItemErrorClassifier());
    }

    public OpenSearchDefaultRetry(BulkItemErrorClassifier errorClassifier) {
        this.errorClassifier = errorClassifier;
    }

    enum BulkResponseAnalysis {
        /** No errors at all */
        NO_ERRORS,
        /** Has errors, but at least one is retryable */
        HAS_RETRYABLE_ERRORS,
        /** Has errors, but ALL are non-retryable */
        ONLY_NON_RETRYABLE_ERRORS
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
        var analyzer = new BulkResponseAnalyzer(errorClassifier);
        HttpByteBufFormatter.processHttpMessageFromBufs(HttpByteBufFormatter.HttpMessageType.RESPONSE,
            Stream.of(responseByteBuf), analyzer);
        return analyzer.getAnalysis();
    }


    @Override
    public TrackedFuture<String, RequestSenderOrchestrator.RetryDirective>
    shouldRetry(@NonNull ByteBuf targetRequestBytes,
                @NonNull AggregatedRawResponse currentResponse,
                @NonNull TrackedFuture<String, ? extends IRequestResponsePacketPair> reconstructedSourceTransactionFuture) {

        var targetRequestByteBuf = Unpooled.wrappedBuffer(targetRequestBytes);
        var parsedRequest = HttpByteBufFormatter.parseHttpRequestFromBufs(Stream.of(targetRequestByteBuf), 0);
        if (parsedRequest == null ||
            !bulkPathMatcher.matcher(parsedRequest.uri()).matches())
        {
            return super.shouldRetry(targetRequestBytes, currentResponse, reconstructedSourceTransactionFuture);
        }

        var targetStatusCode = Optional.ofNullable(currentResponse.getRawResponse())
            .map(r -> r.status().code());

        // If target returned 429 or 5xx for a bulk request, retry immediately without parsing the response body
        if (targetStatusCode.map(code -> code == 429 || code / 100 == 5).orElse(false)) {
            return TextTrackedFuture.completedFuture(RequestSenderOrchestrator.RetryDirective.RETRY,
                () -> "target returned 429/5xx for bulk request, retrying");
        }

        // do a more granular check.  If the raw response wasn't present, then just push it to the superclass
        // since it isn't going to be any kind of response, let alone a bulk one
        if (targetStatusCode.map(code -> code == 200).orElse(false)) {
            var analysis = analyzeBulkResponse(currentResponse.getResponseAsByteBuf());
            if (analysis != null) {
                if (analysis == BulkResponseAnalysis.HAS_RETRYABLE_ERRORS) {
                    return TextTrackedFuture.completedFuture(RequestSenderOrchestrator.RetryDirective.RETRY,
                        () -> "bulk response has retryable errors, retrying");
                }
                return TextTrackedFuture.completedFuture(RequestSenderOrchestrator.RetryDirective.DONE,
                    () -> "bulk response has no retryable errors");
            }
            // Couldn't parse response body — fall through to superclass status code comparison
        }

        return super.shouldRetry(targetRequestBytes, currentResponse, reconstructedSourceTransactionFuture);
    }
}
