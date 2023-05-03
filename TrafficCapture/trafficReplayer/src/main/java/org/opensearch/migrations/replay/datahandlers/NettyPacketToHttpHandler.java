package org.opensearch.migrations.replay.datahandlers;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseDecoder;
import lombok.extern.log4j.Log4j2;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.netty.BacksideHttpWatcherHandler;
import org.opensearch.migrations.replay.netty.BacksideSnifferHandler;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@Log4j2
public class NettyPacketToHttpHandler implements IPacketToHttpHandler {

    ChannelFuture outboundChannelFuture;
    AggregatedRawResponse.Builder responseBuilder;
    BacksideHttpWatcherHandler responseWatchHandler;

    public NettyPacketToHttpHandler(NioEventLoopGroup eventLoopGroup, URI serverUri) {
        // Start the connection attempt.
        Bootstrap b = new Bootstrap();
        responseBuilder = AggregatedRawResponse.builder(Instant.now());
        b.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new BacksideSnifferHandler(responseBuilder))
                .option(ChannelOption.AUTO_READ, false);
        log.trace("Active - setting up backend connection");
        outboundChannelFuture = b.connect(serverUri.getHost(), serverUri.getPort());
        //outboundChannel = outboundChannelFuture.channel();
        responseWatchHandler = new BacksideHttpWatcherHandler(responseBuilder);

        outboundChannelFuture.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // connection complete start to read first data
                log.trace("Done setting up backend channel & it was successful");
                var pipeline = future.channel().pipeline();
                pipeline.addLast(new HttpResponseDecoder());
                // TODO - switch this out to use less memory.
                // We only need to know when the response has been fully received, not the contents
                // since we're already logging those in the sniffer earlier in the pipeline.
                pipeline.addLast(new HttpObjectAggregator(1024*1024));
                pipeline.addLast(responseWatchHandler);
            } else {
                // Close the connection if the connection attempt has failed.
                log.error("closing outbound channel because CONNECT future was not successful");
            }
        });
    }

    @Override
    public CompletableFuture<Void> consumeBytes(ByteBuf packetData) {
        log.trace("Writing packetData["+packetData+"]");
        var completableFuture = new CompletableFuture<Void>();
        if (outboundChannelFuture.isDone()) {
            Channel channel = outboundChannelFuture.channel();
            if (!channel.isActive()) {
                log.warn("Channel is not active - future packets for this connection will be dropped.");
                log.warn("Need to do more sophisticated tracking of progress and retry further up the stack");
                return null;
            }
            log.trace("Writing data to backside handler");
            channel.writeAndFlush(packetData)
                    .addListener((ChannelFutureListener) future -> {
                        if (future.isSuccess()) {
                            log.trace("packet write was successful: "+packetData);
                            completableFuture.complete(null);
                        } else {
                            log.warn("closing outbound channel because WRITE future was not successful "+future.cause());
                            future.channel().close(); // close the backside
                            completableFuture.completeExceptionally(future.cause());
                        }
                    });
        } else {
            outboundChannelFuture.addListener(f-> {
                    if (outboundChannelFuture.isSuccess()) {
                        consumeBytes(packetData).whenComplete((x,t)-> {
                            if (x != null) {
                                completableFuture.complete(x);
                            } else {
                                completableFuture.completeExceptionally(t);
                            }
                        });
                    } else {
                        completableFuture.completeExceptionally(outboundChannelFuture.cause());
                    }
            });
        }
        return completableFuture;
    }

    @Override
    public CompletableFuture<AggregatedRawResponse> finalizeRequest() {
        var future = new CompletableFuture();
        responseWatchHandler.addCallback(arr -> future.complete(arr));
        return future;
    }
}
