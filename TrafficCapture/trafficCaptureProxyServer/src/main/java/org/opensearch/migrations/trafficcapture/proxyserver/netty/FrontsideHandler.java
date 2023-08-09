package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;

@Slf4j
public class FrontsideHandler extends ChannelInboundHandlerAdapter {

    private Channel outboundChannel;
    private BacksideConnectionPool backsideConnectionPool;


    /**
     * Create a handler that sets the autorelease flag
     */
    public FrontsideHandler(BacksideConnectionPool backsideConnectionPool) {
        this.backsideConnectionPool = backsideConnectionPool;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        final Channel inboundChannel = ctx.channel();
        var f = backsideConnectionPool.getOutboundConnectionFuture(inboundChannel.eventLoop(),
                ctx.channel().getClass());
        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                var pipeline = future.channel().pipeline();
                pipeline.addLast(new BacksideHandler(inboundChannel));
                inboundChannel.read();
            } else {
                // Close the connection if the connection attempt has failed.
                log.debug("closing outbound channel because CONNECT future was not successful");
                inboundChannel.close();
            }
        });
        outboundChannel = f.channel();
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
