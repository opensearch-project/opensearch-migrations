package org.opensearch.migrations.replay.datahandlers.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.transform.IAuthTransformer;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NettyDecodedHttpRequestPreliminaryTransformHandler<R> extends ChannelInboundHandlerAdapter {
    public static final int EXPECTED_PACKET_COUNT_GUESS_FOR_PAYLOAD = 32;

    final RequestPipelineOrchestrator<R> requestPipelineOrchestrator;
    final IJsonTransformer transformer;
    final List<List<Integer>> chunkSizes;
    final String diagnosticLabel;

    public NettyDecodedHttpRequestPreliminaryTransformHandler(
        IJsonTransformer transformer,
        List<List<Integer>> chunkSizes,
        RequestPipelineOrchestrator<R> requestPipelineOrchestrator,
        IReplayContexts.IRequestTransformationContext httpTransactionContext
    ) {
        this.transformer = transformer;
        this.chunkSizes = chunkSizes;
        this.requestPipelineOrchestrator = requestPipelineOrchestrator;
        this.diagnosticLabel = "[" + httpTransactionContext + "] ";
    }

    public ListKeyAdaptingCaseInsensitiveHeadersMap clone(ListKeyAdaptingCaseInsensitiveHeadersMap original) {
        var originalStrictMap = original.asStrictMap();
        var newStrictMap = new StrictCaseInsensitiveHttpHeadersMap();
        for (var entry : originalStrictMap.entrySet()) {
            newStrictMap.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return new ListKeyAdaptingCaseInsensitiveHeadersMap(newStrictMap);
    }

    @Override
    public void channelRead(@NonNull ChannelHandlerContext ctx, @NonNull Object msg) throws Exception {
        if (msg instanceof HttpJsonRequestWithFaultingPayload) {
            var originalHttpJsonMessage = (HttpJsonRequestWithFaultingPayload) msg;
            originalHttpJsonMessage.setHeaders(clone(originalHttpJsonMessage.headers()));

            var httpJsonMessage = new HttpJsonRequestWithFaultingPayload();
            httpJsonMessage.setPath(originalHttpJsonMessage.path());
            httpJsonMessage.setHeaders(clone(originalHttpJsonMessage.headers()));
            httpJsonMessage.setMethod(originalHttpJsonMessage.method());
            httpJsonMessage.setProtocol(originalHttpJsonMessage.protocol());
            httpJsonMessage.setPayloadFaultMap((PayloadAccessFaultingMap) originalHttpJsonMessage.payload());

            // TODO - this is super ugly and sloppy - this has to be improved
            chunkSizes.add(new ArrayList<>(EXPECTED_PACKET_COUNT_GUESS_FOR_PAYLOAD));
            IAuthTransformer authTransformer = requestPipelineOrchestrator.authTransfomerFactory.getAuthTransformer(
                httpJsonMessage
            );
            HttpJsonRequestWithFaultingPayload transformedMessage = null;
            final var payloadMap = (PayloadAccessFaultingMap) httpJsonMessage.payload();
            try {
                payloadMap.setDisableThrowingPayloadNotLoaded(false);
                transformedMessage = transform(transformer, httpJsonMessage);
            } catch (Exception e) {
                var payload = (PayloadAccessFaultingMap) httpJsonMessage.payload();
                if (payload.missingPayloadWasAccessed()) {
                    payload.resetMissingPayloadWasAccessed();
                    log.atDebug().setMessage("The transforms for this message require payload manipulation, "
                        + "all content handlers are being loaded.").log();
                    // make a fresh message and its headers
                    requestPipelineOrchestrator.addJsonParsingHandlers(
                            ctx,
                            transformer,
                            getAuthTransformerAsStreamingTransformer(authTransformer));
                    ctx.fireChannelRead(handleAuthHeaders(httpJsonMessage, authTransformer));
                } else {
                    throw new TransformationException(e);
                }
            } finally {
                payloadMap.setDisableThrowingPayloadNotLoaded(true);
            }

            if (transformedMessage != null) {
                handlePayloadNeutralTransformationOrThrow(
                    ctx,
                    originalHttpJsonMessage,
                    transformedMessage,
                    authTransformer
                );
            }
        }
        else if (msg instanceof HttpRequest || msg instanceof HttpContent) {
            super.channelRead(ctx, msg);
        } else {
            assert false
                : "Only HttpJsonRequestWithFaultingPayload, HttpRequest, and HttpContent should come through here as per RequestPipelineOrchestrator";
            // In case message comes through, pass downstream
            super.channelRead(ctx, msg);
        }
    }

    @SuppressWarnings("unchecked")
    public static HttpJsonRequestWithFaultingPayload transform(
        IJsonTransformer transformer,
        HttpJsonRequestWithFaultingPayload httpJsonMessage
    ) {
        assert httpJsonMessage.containsKey("payload");

        Object returnedObject = transformer.transformJson(httpJsonMessage);

        var transformedRequest = HttpJsonRequestWithFaultingPayload.fromObject(returnedObject);

        if (httpJsonMessage != transformedRequest) {
            // clear httpJsonMessage for faster garbage collection if not persisted along
            httpJsonMessage.clear();
        }
        return transformedRequest;
    }

    private void handlePayloadNeutralTransformationOrThrow(
        ChannelHandlerContext ctx,
        HttpJsonRequestWithFaultingPayload originalRequest,
        HttpJsonRequestWithFaultingPayload httpJsonMessage,
        IAuthTransformer authTransformer
    ) {
        // if the auth transformer only requires header manipulations, just do it right away, otherwise,
        // if it's a streaming transformer, require content parsing and send it in there
        handleAuthHeaders(httpJsonMessage, authTransformer);
        var streamingAuthTransformer = getAuthTransformerAsStreamingTransformer(authTransformer);

        var pipeline = ctx.pipeline();
        if (streamingAuthTransformer != null) {
            log.atDebug().setMessage("{}An Authorization Transformation is required for this message.  "
                    + "The headers and payload will be parsed and reformatted.")
                .addArgument(diagnosticLabel).log();
            requestPipelineOrchestrator.addContentRepackingHandlers(ctx, streamingAuthTransformer);
            ctx.fireChannelRead(httpJsonMessage);
        } else if (headerFieldsAreIdentical(originalRequest, httpJsonMessage)) {
            log.atDebug().setMessage("{}Transformation isn't necessary.  "
                    + "Resetting the processing pipeline to let the caller send the original network bytes as-is.")
                .addArgument(diagnosticLabel).log();
            RequestPipelineOrchestrator.removeAllHandlers(pipeline);

        } else if (headerFieldIsIdentical("content-encoding", originalRequest, httpJsonMessage)
            && headerFieldIsIdentical("transfer-encoding", originalRequest, httpJsonMessage)) {
            log.atDebug().setMessage("{}There were changes to the headers that require the message to be reformatted "
                    + "but the payload doesn't need to be transformed.")
                .addArgument(diagnosticLabel).log();
            // By adding the baseline handlers and removing this and previous handlers in reverse order,
            // we will cause the upstream handlers to flush their in-progress accumulated ByteBufs downstream
            // to be processed accordingly
            requestPipelineOrchestrator.addBaselineHandlers(pipeline);
            ctx.fireChannelRead(httpJsonMessage);
            RequestPipelineOrchestrator.removeThisAndPreviousHandlers(pipeline, this);
        } else {
            log.atDebug().setMessage("{}New headers have been specified that require the payload stream to be "
                    + "reformatted.  Setting up the processing pipeline to parse and reformat the request payload.")
                .addArgument(diagnosticLabel).log();
            requestPipelineOrchestrator.addContentRepackingHandlers(ctx, streamingAuthTransformer);
            ctx.fireChannelRead(httpJsonMessage);
        }
    }

    private static HttpJsonRequestWithFaultingPayload handleAuthHeaders(
        HttpJsonRequestWithFaultingPayload httpJsonMessage,
        IAuthTransformer authTransformer
    ) {
        if (authTransformer instanceof IAuthTransformer.HeadersOnlyTransformer) {
            ((IAuthTransformer.HeadersOnlyTransformer) authTransformer).rewriteHeaders(httpJsonMessage);
        }
        return httpJsonMessage;
    }

    private static IAuthTransformer.StreamingFullMessageTransformer getAuthTransformerAsStreamingTransformer(
        IAuthTransformer authTransformer
    ) {
        return (authTransformer instanceof IAuthTransformer.StreamingFullMessageTransformer)
            ? (IAuthTransformer.StreamingFullMessageTransformer) authTransformer
            : null;
    }

    public static boolean headerFieldsAreIdentical(HttpJsonRequestWithFaultingPayload request1,
        HttpJsonRequestWithFaultingPayload request2) {
        // Check if both maps are the same size
        if (request1.size() != request2.size()) {
            return false;
        }

        // Iterate through the entries of request1 and compare with request2 except and headers
        for (Map.Entry<String, Object> entry : request1.entrySet()) {
            String key = entry.getKey();
            if (JsonKeysForHttpMessage.PAYLOAD_KEY.equals(key) || JsonKeysForHttpMessage.HEADERS_KEY.equals(key)) {
                continue;
            }
            Object value1 = entry.getValue();
            Object value2 = request2.getOrDefault(key, null);
            if (!Objects.deepEquals(value1, value2)) {
                return false;
            }
        }

        var headers1 = request1.headers();
        var headers2 = request2.headers();
        if (headers1 == null) {
            return headers2 == null;
        }

        if (headers1.size() != headers2.size()) {
            return false;
        }

        for (Map.Entry<String, Object> entry : headers1.entrySet()) {
            String key = entry.getKey();
            Object value1 = entry.getValue();
            Object value2 = headers2.getOrDefault(key, null);
            if (!Objects.deepEquals(value1, value2)) {
                return false;
            }
        }
        return true;
    }

    private boolean headerFieldIsIdentical(
        String headerName,
        HttpJsonRequestWithFaultingPayload request,
        HttpJsonRequestWithFaultingPayload httpJsonMessage
    ) {
        var originalValue = Optional.ofNullable(request)
            .map(HttpJsonMessageWithFaultingPayload::headers)
            .map(ListKeyAdaptingCaseInsensitiveHeadersMap::asStrictMap)
            .map(s -> s.getOrDefault(headerName, null))
            .filter(s -> !s.isEmpty());
        var newValue = Optional.ofNullable(httpJsonMessage)
            .map(HttpJsonMessageWithFaultingPayload::headers)
            .map(ListKeyAdaptingCaseInsensitiveHeadersMap::asStrictMap)
            .map(s -> s.getOrDefault(headerName, null))
            .filter(s -> !s.isEmpty());

        if (originalValue.isEmpty() || newValue.isEmpty()) {
            return originalValue.isEmpty() == newValue.isEmpty();
        }
        return originalValue.get().equals(newValue.get());
    }
}
