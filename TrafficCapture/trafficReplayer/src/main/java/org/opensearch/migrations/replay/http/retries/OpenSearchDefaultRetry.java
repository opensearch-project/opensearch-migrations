package org.opensearch.migrations.replay.http.retries;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.HttpByteBufFormatter;
import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OpenSearchDefaultRetry extends DefaultRetry {

    private static final Pattern bulkPathMatcher = Pattern.compile("^(/[^/]*)?/_bulk(/.*)?$");

    enum BulkResponseAnalysis {
        /** No errors at all */
        NO_ERRORS,
        /** Has errors, but at least one is retryable */
        HAS_RETRYABLE_ERRORS,
        /** Has errors, but ALL are non-retryable */
        ONLY_NON_RETRYABLE_ERRORS
    }

    private static class BulkResponseAnalyzer extends ChannelInboundHandlerAdapter {
        private final ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        private BulkResponseAnalysis result = null;

        BulkResponseAnalysis getAnalysis() {
            return result;
        }

        @Override
        public void channelRead(@NonNull ChannelHandlerContext ctx, @NonNull Object msg) throws Exception {
            if (msg instanceof HttpContent && result == null) {
                var content = ((HttpContent) msg).content();
                var readable = content.readableBytes();
                if (readable > 0) {
                    var bytes = new byte[readable];
                    content.getBytes(content.readerIndex(), bytes);
                    bodyBytes.write(bytes);
                }
                if (msg instanceof LastHttpContent) {
                    result = analyzeBody(bodyBytes.toString(StandardCharsets.UTF_8));
                }
            }
            ctx.fireChannelRead(msg);
        }

        private BulkResponseAnalysis analyzeBody(String body) {
            try {
                return doAnalyzeBody(body);
            } catch (Exception e) {
                log.atWarn().setCause(e)
                    .setMessage("Failed to parse bulk response body, treating as retryable")
                    .log();
                return BulkResponseAnalysis.HAS_RETRYABLE_ERRORS;
            }
        }

        private BulkResponseAnalysis doAnalyzeBody(String body) throws IOException {
            var mapper = new ObjectMapper();
            var root = mapper.readTree(body);

            var errorsNode = root.get("errors");
            if (errorsNode != null && !errorsNode.asBoolean()) {
                return BulkResponseAnalysis.NO_ERRORS;
            }

            var items = root.get("items");
            if (items == null || !items.isArray()) {
                return errorsNode != null ? BulkResponseAnalysis.HAS_RETRYABLE_ERRORS : BulkResponseAnalysis.NO_ERRORS;
            }

            var itemAnalysis = classifyItemErrors(items);
            if (itemAnalysis != null) {
                return itemAnalysis;
            }
            // No error items found — trust the top-level "errors" field
            if (errorsNode != null && errorsNode.asBoolean()) {
                return BulkResponseAnalysis.HAS_RETRYABLE_ERRORS;
            }
            return BulkResponseAnalysis.NO_ERRORS;
        }

        /**
         * Scans bulk items for errors and classifies them.
         * @return the analysis result, or null if no error items were found
         */
        private BulkResponseAnalysis classifyItemErrors(com.fasterxml.jackson.databind.JsonNode items) {
            boolean hasAnyError = false;
            for (var item : items) {
                var actionNode = item.fields().hasNext() ? item.fields().next().getValue() : null;
                if (actionNode == null) continue;

                var errorNode = actionNode.get("error");
                if (errorNode == null || !errorNode.isObject()) continue;

                hasAnyError = true;
                var typeNode = errorNode.get("type");
                var errorType = typeNode != null ? typeNode.asText() : null;

                if (!BulkItemErrorClassifier.isNonRetryable(errorType)) {
                    log.atDebug().setMessage("Found retryable bulk item error type: {}")
                        .addArgument(errorType).log();
                    return BulkResponseAnalysis.HAS_RETRYABLE_ERRORS;
                }
            }
            return hasAnyError ? BulkResponseAnalysis.ONLY_NON_RETRYABLE_ERRORS : null;
        }
    }

    BulkResponseAnalysis analyzeBulkResponse(ByteBuf responseByteBuf) {
        var analyzer = new BulkResponseAnalyzer();
        HttpByteBufFormatter.processHttpMessageFromBufs(HttpByteBufFormatter.HttpMessageType.RESPONSE,
            Stream.of(responseByteBuf), analyzer);
        var analysis = analyzer.getAnalysis();
        return analysis != null ? analysis : BulkResponseAnalysis.HAS_RETRYABLE_ERRORS;
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
            if (analysis == BulkResponseAnalysis.HAS_RETRYABLE_ERRORS) {
                return TextTrackedFuture.completedFuture(RequestSenderOrchestrator.RetryDirective.RETRY,
                    () -> "bulk response has retryable errors, retrying");
            }
            // NO_ERRORS or ONLY_NON_RETRYABLE_ERRORS — nothing to retry
            return TextTrackedFuture.completedFuture(RequestSenderOrchestrator.RetryDirective.DONE,
                () -> "bulk response has no retryable errors");
        }

        return super.shouldRetry(targetRequestBytes, currentResponse, reconstructedSourceTransactionFuture);
    }
}
