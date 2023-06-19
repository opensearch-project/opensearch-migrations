package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLEngine;
import java.net.URI;

@Slf4j
public class FrontsideHandler extends ChannelInboundHandlerAdapter {

    private Channel outboundChannel;

    private final URI backsideUri;
    private final SslContext backsideSslContext;

    /**
     * Create a handler that sets the autoreleases flag
     * @param backsideUri
     * @param backsideSslContext
     */
    public FrontsideHandler(URI backsideUri, SslContext backsideSslContext) {
        this.backsideUri = backsideUri;
        this.backsideSslContext = backsideSslContext;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        final Channel inboundChannel = ctx.channel();
        // Start the connection attempt.
        Bootstrap b = new Bootstrap();
        b.group(inboundChannel.eventLoop())
                .channel(ctx.channel().getClass())
                .handler(new BacksideHandler(inboundChannel))
                .option(ChannelOption.AUTO_READ, false);
        log.debug("Active - setting up backend connection");
        var f = b.connect(backsideUri.getHost(), backsideUri.getPort());
        outboundChannel = f.channel();
        f.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (future.isSuccess()) {
                    // connection complete start to read first data
                    log.debug("Done setting up backend channel & it was successful");
                    if (backsideSslContext != null) {
                        var pipeline = future.channel().pipeline();
                        SSLEngine sslEngine = backsideSslContext.newEngine(future.channel().alloc());
                        sslEngine.setUseClientMode(true);
                        pipeline.addFirst("ssl", new SslHandler(sslEngine));
                    }
                    inboundChannel.read();
                } else {
                    // Close the connection if the connection attempt has failed.
                    log.debug("closing outbound channel because CONNECT future was not successful");
                    inboundChannel.close();
                }
            }
        });
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) {
        log.debug("frontend handler read: "+msg);
        if (outboundChannel.isActive()) {
            log.debug("Writing data to backside handler");
            outboundChannel.writeAndFlush(msg)
                    .addListener((ChannelFutureListener) future -> {
                        if (future.isSuccess()) {
                            ctx.channel().read(); // kickoff another read for the frontside
                        } else {
                            log.debug("closing outbound channel because WRITE future was not successful due to: ",
                                    future.cause());
                            future.channel().close(); // close the backside
                        }
                    });
        } // if the outbound channel has died, so be it... let this frontside finish with it's caller naturally
    }

    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        log.debug("channelRead COMPLETE");
        ctx.fireChannelReadComplete();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    /**
     * This will close our proxy connection to downstream services.
     * @param ctx current channel context.
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (outboundChannel != null) {
            closeAndFlush(outboundChannel);
        }
    }

    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    static void closeAndFlush(Channel ch) {
        if (ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
