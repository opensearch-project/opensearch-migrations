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

    private static class BulkErrorFindingHandler extends ChannelInboundHandlerAdapter {
        private final JsonParser parser;
        private final ByteBufferFeeder feeder;
        private Boolean errorField = null;

        @SneakyThrows
        public BulkErrorFindingHandler() {
            JsonFactory jsonFactory = new JsonFactory();
            parser = jsonFactory.createNonBlockingByteBufferParser();
            feeder = (ByteBufferFeeder) parser.getNonBlockingInputFeeder();
        }

        /**
         * @return true iff the "errors" field was present at the root and its value was false.  false otherwise.
         */
        boolean hadNoErrors() {
            return errorField != null && !errorField;
        }

        @Override
        public void channelRead(@NonNull ChannelHandlerContext ctx, @NonNull Object msg) throws Exception {
            if (msg instanceof HttpContent && errorField == null) {
                log.atDebug().setMessage("body contents: {}")
                    .addArgument(((HttpContent) msg).content().duplicate()).log();
                feeder.feedInput(((HttpContent) msg).content().nioBuffer());
                consumeInput();
                if (msg instanceof LastHttpContent) {
                    feeder.endOfInput();
                    consumeInput();
                }
            }
            ctx.fireChannelRead(msg); // Pass other messages down the pipeline
        }

        private void consumeInput() throws IOException {
            if (errorField != null) {
                return;
            }
            JsonToken token;
            while (!parser.isClosed() &&
                ((token = parser.nextToken()) != null) &&
                token != JsonToken.NOT_AVAILABLE)
            {
                JsonToken finalToken = token;
                log.atTrace().setMessage("Got token: {}").addArgument(finalToken).log();
                if (token == JsonToken.FIELD_NAME && "errors".equals(parser.currentName())) {
                    parser.nextToken();
                    errorField = parser.getValueAsBoolean();
                    break;
                } else if (parser.getParsingContext().inRoot() && token == JsonToken.END_OBJECT) {
                    break;
                } else if (token != JsonToken.START_OBJECT &&
                    token != JsonToken.END_OBJECT &&
                    !parser.getParsingContext().inRoot())
                {
                    // Skip non-root level content
                    parser.skipChildren();
                }
            }
        }
    }

    boolean bulkResponseHadNoErrors(ByteBuf responseByteBuf) {
        var errorFieldFinderHandler = new BulkErrorFindingHandler();
        HttpByteBufFormatter.processHttpMessageFromBufs(HttpByteBufFormatter.HttpMessageType.RESPONSE,
            Stream.of(responseByteBuf), errorFieldFinderHandler);
        return errorFieldFinderHandler.hadNoErrors();
    }


    @Override
    public TrackedFuture<String, RequestSenderOrchestrator.RetryDirective>
    shouldRetry(@NonNull ByteBuf targetRequestBytes,
                @NonNull AggregatedRawResponse currentResponse,
                @NonNull TrackedFuture<String, ? extends IRequestResponsePacketPair> reconstructedSourceTransactionFuture) {

        var targetRequestByteBuf = Unpooled.wrappedBuffer(targetRequestBytes);
        var parsedRequest = HttpByteBufFormatter.parseHttpRequestFromBufs(Stream.of(targetRequestByteBuf), 0);
        if (parsedRequest != null &&
            bulkPathMatcher.matcher(parsedRequest.uri()).matches() &&
            // do a more granular check.  If the raw response wasn't present, then just push it to the superclass
            // since it isn't going to be any kind of response, let alone a bulk one
            Optional.ofNullable(currentResponse.getRawResponse())
                .map(r->r.status().code() == 200)
                .orElse(false))
        {
            if (bulkResponseHadNoErrors(currentResponse.getResponseAsByteBuf())) {
                return TextTrackedFuture.completedFuture(RequestSenderOrchestrator.RetryDirective.DONE,
                    () -> "no errors found in the target response, so not retrying");
            } else {
                return reconstructedSourceTransactionFuture.thenCompose(rrp ->
                        TextTrackedFuture.completedFuture(
                            Optional.ofNullable(rrp.getResponseData())
                                .map(sourceResponse -> bulkResponseHadNoErrors(sourceResponse.asByteBuf()) ?
                                    RequestSenderOrchestrator.RetryDirective.RETRY :
                                    RequestSenderOrchestrator.RetryDirective.DONE)
                                .orElse(RequestSenderOrchestrator.RetryDirective.DONE),
                            () -> "evaluating retry status dependent upon source error field"),
                    () -> "checking the accumulated source response value");
            }
        }

        return super.shouldRetry(targetRequestBytes, currentResponse, reconstructedSourceTransactionFuture);
    }
}
