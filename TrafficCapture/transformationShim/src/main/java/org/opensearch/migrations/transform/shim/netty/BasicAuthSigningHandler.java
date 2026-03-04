package org.opensearch.migrations.transform.shim.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Netty handler that sets or removes the Authorization header on every request.
 * Mirrors the replayer's StaticAuthTransformerFactory / RemovingAuthTransformerFactory behavior.
 *
 * <p>If authHeaderValue is non-null, sets it. If null, removes the Authorization header.</p>
 */
@Slf4j
public class BasicAuthSigningHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final String authHeaderValue;

    public BasicAuthSigningHandler(String authHeaderValue) {
        super(false); // don't auto-release — we pass the request downstream
        this.authHeaderValue = authHeaderValue;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (authHeaderValue != null) {
            request.headers().set("Authorization", authHeaderValue);
            log.debug("Set Authorization header on request: {} {}", request.method(), request.uri());
        } else {
            request.headers().remove("Authorization");
            log.debug("Removed Authorization header from request: {} {}", request.method(), request.uri());
        }
        ctx.fireChannelRead(request);
    }
}
