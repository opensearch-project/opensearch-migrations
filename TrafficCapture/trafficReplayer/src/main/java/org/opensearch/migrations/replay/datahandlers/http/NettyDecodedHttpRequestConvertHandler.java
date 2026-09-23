package org.opensearch.migrations.replay.datahandlers.http;

// REBUILD-LIMBO(G5) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: IReplayContexts . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G5)
/*

import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NettyDecodedHttpRequestConvertHandler extends ChannelInboundHandlerAdapter {

    final String diagnosticLabel;
    private final IReplayContexts.IRequestTransformationContext httpTransactionContext;
    final boolean propagateOriginalRequest;

    public NettyDecodedHttpRequestConvertHandler(
        IReplayContexts.IRequestTransformationContext httpTransactionContext,
        boolean propagateOriginalRequest
    ) {
        this.diagnosticLabel = "[" + httpTransactionContext + "] ";
        this.httpTransactionContext = httpTransactionContext;
        this.propagateOriginalRequest = propagateOriginalRequest;
    }

    @Override
    public void channelRead(@NonNull ChannelHandlerContext ctx, @NonNull Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            httpTransactionContext.onHeaderParse();
            var request = (HttpRequest) msg;
            log.atDebug().setMessage("{} parsed request: {} {} {}")
                .addArgument(diagnosticLabel)
                .addArgument(request::method)
                .addArgument(request::uri)
                .addArgument(() -> request.protocolVersion().text())
                .log();
            var httpJsonMessage = parseHeadersIntoMessage(request);
            ctx.fireChannelRead(httpJsonMessage);
            if (propagateOriginalRequest) {
                // Send both, may need to re-drive the request through again depending on initial processing of httpJsonMessage
                ctx.fireChannelRead(request);
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }

    public static HttpJsonRequestWithFaultingPayload parseHeadersIntoMessage(HttpRequest request) {
        var jsonMsg = new HttpJsonRequestWithFaultingPayload();
        jsonMsg.setPath(request.uri());
        jsonMsg.setMethod(request.method().toString());
        jsonMsg.setProtocol(request.protocolVersion().text());
        var headers = request.headers()
            .entries()
            .stream()
            .collect(
                Collectors.groupingBy(
                    Map.Entry::getKey,
                    StrictCaseInsensitiveHttpHeadersMap::new,
                    Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                )
            );
        jsonMsg.setHeaders(new ListKeyAdaptingCaseInsensitiveHeadersMap(headers));
        jsonMsg.setPayloadFaultMap(new PayloadAccessFaultingMap(headers));
        return jsonMsg;
    }
}

*/
// REBUILD-LIMBO-END(G5)