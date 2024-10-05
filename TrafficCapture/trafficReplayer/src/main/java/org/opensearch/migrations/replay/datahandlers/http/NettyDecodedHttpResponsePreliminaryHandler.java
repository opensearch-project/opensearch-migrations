package org.opensearch.migrations.replay.datahandlers.http;

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
public class NettyDecodedHttpResponsePreliminaryHandler extends ChannelInboundHandlerAdapter {

    final String diagnosticLabel;
    private final IReplayContexts.IRequestTransformationContext httpTransactionContext;

    public NettyDecodedHttpResponsePreliminaryHandler(
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
            log.atInfo()
                .setMessage(
                    () -> diagnosticLabel
                        + " parsed response: "
                        + response.status().code()
                        + " "
                        + response.status().reasonPhrase()
                        + " "
                        + response.protocolVersion().text()
                )
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
