package org.opensearch.migrations.replay.datahandlers.http;

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

    public NettyDecodedHttpRequestConvertHandler(
        IReplayContexts.IRequestTransformationContext httpTransactionContext
    ) {
        this.diagnosticLabel = "[" + httpTransactionContext + "] ";
        this.httpTransactionContext = httpTransactionContext;
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
