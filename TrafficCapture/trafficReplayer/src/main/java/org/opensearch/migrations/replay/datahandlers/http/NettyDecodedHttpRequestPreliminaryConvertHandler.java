package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;
import org.opensearch.migrations.replay.datahandlers.PayloadNotLoadedException;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.transform.IAuthTransformer;
import org.opensearch.migrations.transform.IJsonTransformer;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class NettyDecodedHttpRequestPreliminaryConvertHandler<R> extends ChannelInboundHandlerAdapter {
    public static final int EXPECTED_PACKET_COUNT_GUESS_FOR_PAYLOAD = 32;

    final RequestPipelineOrchestrator<R> requestPipelineOrchestrator;
    final IJsonTransformer transformer;
    final List<List<Integer>> chunkSizes;
    final String diagnosticLabel;
    private UniqueReplayerRequestKey requestKeyForMetricsLogging;
    static final MetricsLogger metricsLogger = new MetricsLogger("NettyDecodedHttpRequestPreliminaryConvertHandler");

    public NettyDecodedHttpRequestPreliminaryConvertHandler(IJsonTransformer transformer,
                                                            List<List<Integer>> chunkSizes,
                                                            RequestPipelineOrchestrator<R> requestPipelineOrchestrator,
                                                            String diagnosticLabel,
                                                            UniqueReplayerRequestKey requestKeyForMetricsLogging) {
        this.transformer = transformer;
        this.chunkSizes = chunkSizes;
        this.requestPipelineOrchestrator = requestPipelineOrchestrator;
        this.diagnosticLabel = "[" + diagnosticLabel + "] ";
        this.requestKeyForMetricsLogging = requestKeyForMetricsLogging;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            var request = (HttpRequest) msg;
            log.info(new StringBuilder(diagnosticLabel)
                    .append(" parsed request: ")
                    .append(request.method())
                    .append(" ")
                    .append(request.uri())
                    .append(" ")
                    .append(request.protocolVersion().text())
                    .toString());
            metricsLogger.atSuccess(MetricsEvent.CAPTURED_REQUEST_PARSED_TO_HTTP)
                    .setAttribute(MetricsAttributeKey.REQUEST_ID, requestKeyForMetricsLogging)
                    .setAttribute(MetricsAttributeKey.CONNECTION_ID, requestKeyForMetricsLogging.getTrafficStreamKey().getConnectionId())
                    .setAttribute(MetricsAttributeKey.HTTP_METHOD, request.method())
                    .setAttribute(MetricsAttributeKey.HTTP_ENDPOINT, request.uri()).emit();

            // TODO - this is super ugly and sloppy - this has to be improved
            chunkSizes.add(new ArrayList<>(EXPECTED_PACKET_COUNT_GUESS_FOR_PAYLOAD));
            var originalHttpJsonMessage = parseHeadersIntoMessage(request);
            IAuthTransformer authTransformer =
                    requestPipelineOrchestrator.authTransfomerFactory.getAuthTransformer(originalHttpJsonMessage);
            try {
                handlePayloadNeutralTransformationOrThrow(ctx, request, transform(transformer, originalHttpJsonMessage),
                        authTransformer);
            } catch (PayloadNotLoadedException pnle) {
                log.debug("The transforms for this message require payload manipulation, " +
                        "all content handlers are being loaded.");
                // make a fresh message and its headers
                requestPipelineOrchestrator.addJsonParsingHandlers(ctx, transformer,
                        getAuthTransformerAsStreamingTransformer(authTransformer));
                ctx.fireChannelRead(handleAuthHeaders(parseHeadersIntoMessage(request), authTransformer));
            }
        } else if (msg instanceof HttpContent) {
            ctx.fireChannelRead(msg);
        } else {
            // ByteBufs shouldn't come through, but in case there's a regression in
            // RequestPipelineOrchestrator.removeThisAndPreviousHandlers to remove the handlers
            // in order rather in reverse order
            super.channelRead(ctx, msg);
        }
    }

    private static HttpJsonMessageWithFaultingPayload transform(IJsonTransformer transformer,
                                                                HttpJsonMessageWithFaultingPayload httpJsonMessage) {
        var returnedObject = transformer.transformJson(httpJsonMessage);
        if (returnedObject != httpJsonMessage) {
            httpJsonMessage.clear();
            httpJsonMessage = new HttpJsonMessageWithFaultingPayload(returnedObject);
        }
        return httpJsonMessage;
    }

    private void handlePayloadNeutralTransformationOrThrow(ChannelHandlerContext ctx,
                                                           HttpRequest request,
                                                           HttpJsonMessageWithFaultingPayload httpJsonMessage,
                                                           IAuthTransformer authTransformer)
    {
        // if the auth transformer only requires header manipulations, just do it right away, otherwise,
        // if it's a streaming transformer, require content parsing and send it in there
        handleAuthHeaders(httpJsonMessage, authTransformer);
        var streamingAuthTransformer = getAuthTransformerAsStreamingTransformer(authTransformer);

        var pipeline = ctx.pipeline();
        if (streamingAuthTransformer != null) {
            log.info(diagnosticLabel + "An Authorization Transformation is required for this message.  " +
                    "The headers and payload will be parsed and reformatted.");
            requestPipelineOrchestrator.addContentRepackingHandlers(ctx, streamingAuthTransformer);
            ctx.fireChannelRead(httpJsonMessage);
        } else if (headerFieldsAreIdentical(request, httpJsonMessage)) {
            log.info(diagnosticLabel + "Transformation isn't necessary.  " +
                    "Resetting the processing pipeline to let the caller send the original network bytes as-is.");
            while (pipeline.first() != null) {
                pipeline.removeFirst();
            }
        } else if (headerFieldIsIdentical("content-encoding", request, httpJsonMessage) &&
                headerFieldIsIdentical("transfer-encoding", request, httpJsonMessage)) {
            log.info(diagnosticLabel + "There were changes to the headers that require the message to be reformatted " +
                    "but the payload doesn't need to be transformed.");
            requestPipelineOrchestrator.addBaselineHandlers(pipeline);
            ctx.fireChannelRead(httpJsonMessage);
            RequestPipelineOrchestrator.removeThisAndPreviousHandlers(pipeline, this);
        } else {
            log.info(diagnosticLabel + "New headers have been specified that require the payload stream to be " +
                    "reformatted.  Setting up the processing pipeline to parse and reformat the request payload.");
            requestPipelineOrchestrator.addContentRepackingHandlers(ctx, streamingAuthTransformer);
            ctx.fireChannelRead(httpJsonMessage);
        }
    }

    private static HttpJsonMessageWithFaultingPayload
    handleAuthHeaders(HttpJsonMessageWithFaultingPayload httpJsonMessage, IAuthTransformer authTransformer) {
        if (authTransformer instanceof IAuthTransformer.HeadersOnlyTransformer) {
            ((IAuthTransformer.HeadersOnlyTransformer) authTransformer).rewriteHeaders(httpJsonMessage);
        }
        return httpJsonMessage;
    }

    private static IAuthTransformer.StreamingFullMessageTransformer
    getAuthTransformerAsStreamingTransformer(IAuthTransformer authTransformer) {
        return (authTransformer instanceof IAuthTransformer.StreamingFullMessageTransformer) ?
                (IAuthTransformer.StreamingFullMessageTransformer) authTransformer : null;
    }

    private boolean headerFieldsAreIdentical(HttpRequest request, HttpJsonMessageWithFaultingPayload httpJsonMessage) {
        if (!request.uri().equals(httpJsonMessage.path()) ||
                !request.method().toString().equals(httpJsonMessage.method()) ||
                request.headers().size() != httpJsonMessage.headers().strictHeadersMap.size()) {
            return false;
        }
        for (var headerName : httpJsonMessage.headers().keySet()) {
            if (!headerFieldIsIdentical(headerName, request, httpJsonMessage)) {
                return false;
            }
        }
        return true;
    }

    private static HttpJsonMessageWithFaultingPayload parseHeadersIntoMessage(HttpRequest request) {
        var jsonMsg = new HttpJsonMessageWithFaultingPayload();
        jsonMsg.setPath(request.uri());
        jsonMsg.setMethod(request.method().toString());
        jsonMsg.setProtocol(request.protocolVersion().text());
        var headers = request.headers().entries().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        StrictCaseInsensitiveHttpHeadersMap::new,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        jsonMsg.setHeaders(new ListKeyAdaptingCaseInsensitiveHeadersMap(headers));
        jsonMsg.setPayloadFaultMap(new PayloadAccessFaultingMap(headers));
        return jsonMsg;
    }

    private List<String> nullIfEmpty(List<String> list) {
        return list != null && list.isEmpty() ? null : list;
    }

    private boolean headerFieldIsIdentical(String headerName, HttpRequest request,
                                           HttpJsonMessageWithFaultingPayload httpJsonMessage) {
        var originalValue = nullIfEmpty(request.headers().getAll(headerName));
        var newValue = nullIfEmpty(httpJsonMessage.headers().asStrictMap().get(headerName));
        if (originalValue != null && newValue != null) {
            return originalValue.equals(newValue);
        } else {
            return (originalValue == null && newValue == null);
        }
    }

}
