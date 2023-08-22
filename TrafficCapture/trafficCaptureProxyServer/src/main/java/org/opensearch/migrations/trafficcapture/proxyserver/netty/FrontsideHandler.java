package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.logging.LogLevel;
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
        var outboundChannelFuture = backsideConnectionPool.getOutboundConnectionFuture(inboundChannel.eventLoop());
        log.atDebug().setMessage(()->"Active - setting up backend connection with channel " + outboundChannelFuture.channel());
        outboundChannelFuture.addListener((ChannelFutureListener) (future -> {
            if (future.isSuccess()) {
                var pipeline = future.channel().pipeline();
                pipeline.addLast(new BacksideHandler(inboundChannel));
                inboundChannel.read();
            } else {
                // Close the connection if the connection attempt has failed.
                log.atDebug().setMessage(()->"closing outbound channel because CONNECT future was not successful").log();
                inboundChannel.close();
            }
        }));
        outboundChannel = outboundChannelFuture.channel();
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) {
        log.atDebug().setMessage(()->"frontend handler[" + this.outboundChannel + "] read: "+msg).log();
        if (outboundChannel.isActive()) {
            log.atDebug().setMessage(()->"Writing data to backside handler " + outboundChannel).log();
            outboundChannel.writeAndFlush(msg)
                    .addListener((ChannelFutureListener) future -> {
                        if (future.isSuccess()) {
                            ctx.channel().read(); // kickoff another read for the frontside
                        } else {
                            log.atDebug().setCause(future.cause())
                                    .setMessage(()->"closing outbound channel because WRITE future was not successful due to").log();
                            future.channel().close(); // close the backside
                        }
                    });
            outboundChannel.config().setAutoRead(true);
        } else { // if the outbound channel has died, so be it... let this frontside finish with it's caller naturally
            log.atWarn().setMessage(()->"Output channel (" + outboundChannel + ") is NOT active");
        }
    }

    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        log.atDebug().setMessage(()->"channelRead COMPLETE").log();
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
