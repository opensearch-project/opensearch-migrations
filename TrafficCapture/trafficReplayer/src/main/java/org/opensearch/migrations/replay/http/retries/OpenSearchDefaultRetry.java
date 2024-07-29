package org.opensearch.migrations.replay.http.retries;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteBufferFeeder;
import com.fasterxml.jackson.core.async.NonBlockingInputFeeder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.HttpByteBufFormatter;
import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.util.TextTrackedFuture;
import org.opensearch.migrations.replay.util.TrackedFuture;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class OpenSearchDefaultRetry extends DefaultRetry {

    private static final Pattern bulkPathMatcher = Pattern.compile("^(/[^/]*)?/_bulk([/?]+.*)$");

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

        boolean hadNoErrors() {
            return errorField != null && !errorField;
        }

        @Override
        public void channelRead(@NonNull ChannelHandlerContext ctx, @NonNull Object msg) throws Exception {
            if (msg instanceof HttpContent) {
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
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.FIELD_NAME && "error".equals(parser.getCurrentName())) {
                    parser.nextToken();
                    errorField = parser.getValueAsBoolean();
                    break;
                } else if (parser.getParsingContext().inRoot() && token == JsonToken.END_OBJECT) {
                    break;
                } else if (token != JsonToken.START_OBJECT && token != JsonToken.END_OBJECT) {
                    // Skip non-root level content
                    if (!parser.getParsingContext().inRoot()) {
                        parser.skipChildren();
                    }
                }
            }
        }
    }

    boolean bulkResponseHadNoErrors(ByteBuf responseByteBuf) {
        var errorFieldFinderHandler = new BulkErrorFindingHandler();
        HttpByteBufFormatter.parseHttpMessageFromBufs(HttpByteBufFormatter.HttpMessageType.RESPONSE,
            Stream.of(responseByteBuf), errorFieldFinderHandler);
        return errorFieldFinderHandler.hadNoErrors();
    }

    @Override
    public TrackedFuture<String, RequestSenderOrchestrator.RetryDirective>
    apply(ByteBuf targetRequestBytes,
          List<AggregatedRawResponse> previousResponses,
          AggregatedRawResponse currentResponse,
          TrackedFuture<String, ? extends IRequestResponsePacketPair> reconstructedSourceTransactionFuture) {

        var targetRequestByteBuf = Unpooled.wrappedBuffer(targetRequestBytes);
        var targetRequest =
            HttpByteBufFormatter.parseHttpRequestFromBufs(Stream.of(targetRequestByteBuf), 0);
        if (bulkPathMatcher.matcher(targetRequest.uri()).matches() &&
            currentResponse.getRawResponse().status().code() == 200) // check more granularly
        {
            if (bulkResponseHadNoErrors(currentResponse.getResponseAsByteBuf())) {
                return TextTrackedFuture.completedFuture(RequestSenderOrchestrator.RetryDirective.DONE,
                    () -> "no errors found in the target response, so not retrying");
            } else {
                return reconstructedSourceTransactionFuture.thenCompose(rrp ->
                    TextTrackedFuture.completedFuture(
                        bulkResponseHadNoErrors(rrp.getResponseData().asByteBuf()) ?
                            RequestSenderOrchestrator.RetryDirective.RETRY :
                            RequestSenderOrchestrator.RetryDirective.DONE,
                        () -> "evaluating retry status dependent upon source error field"),
                    () -> "checking the accumulated source response value");
            }
        }
        return super.apply(targetRequestBytes, previousResponses, currentResponse, reconstructedSourceTransactionFuture);
    }
}
