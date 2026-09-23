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
import io.netty.handler.codec.http.HttpResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NettyDecodedHttpResponseConvertHandler extends ChannelInboundHandlerAdapter {

    final String diagnosticLabel;
    private final IReplayContexts.IRequestTransformationContext httpTransactionContext;

    public NettyDecodedHttpResponseConvertHandler(
        IReplayContexts.IRequestTransformationContext httpTransactionContext
    ) {
        this.diagnosticLabel = "[" + httpTransactionContext + "] ";
        this.httpTransactionContext = httpTransactionContext;
    }

    @Override
    public void channelRead(@NonNull ChannelHandlerContext ctx, @NonNull Object msg) throws Exception {
        if (msg instanceof HttpResponse) {
            httpTransactionContext.onHeaderParse();
            var response = (HttpResponse) msg;
            log.atDebug().setMessage("{} parsed response: {} {} {}")
                .addArgument(diagnosticLabel)
                .addArgument(() -> response.status().code())
                .addArgument(() -> response.status().reasonPhrase())
                .addArgument(() -> response.protocolVersion().text())
                .log();
            var httpJsonMessage = parseHeadersIntoMessage(response);
            ctx.fireChannelRead(httpJsonMessage);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    public static HttpJsonResponseWithFaultingPayload parseHeadersIntoMessage(HttpResponse response) {
        var jsonMsg = new HttpJsonResponseWithFaultingPayload();
        jsonMsg.setProtocol(response.protocolVersion().text());
        jsonMsg.setCode(String.valueOf(response.status().code()));
        jsonMsg.setReason(response.status().reasonPhrase());
        var headers = response.headers()
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