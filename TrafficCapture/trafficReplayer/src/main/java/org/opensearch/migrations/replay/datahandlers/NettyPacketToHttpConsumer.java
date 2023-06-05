package org.opensearch.migrations.replay.datahandlers;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import lombok.extern.log4j.Log4j2;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.netty.BacksideHttpWatcherHandler;
import org.opensearch.migrations.replay.netty.BacksideSnifferHandler;

import javax.net.ssl.SSLEngine;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@Log4j2
public class NettyPacketToHttpConsumer implements IPacketFinalizingConsumer<AggregatedRawResponse> {

    ChannelFuture outboundChannelFuture;
    AggregatedRawResponse.Builder responseBuilder;
    BacksideHttpWatcherHandler responseWatchHandler;

    public NettyPacketToHttpConsumer(NioEventLoopGroup eventLoopGroup, URI serverUri, SslContext sslContext) {
        // Start the connection attempt.
        Bootstrap b = new Bootstrap();
        responseBuilder = AggregatedRawResponse.builder(Instant.now());
        b.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new BacksideSnifferHandler(responseBuilder))
                .option(ChannelOption.AUTO_READ, false);
        String host = serverUri.getHost();
        int port = serverUri.getPort();
        log.debug("Active - setting up backend connection to " + host + ":" + port);
        outboundChannelFuture = b.connect(host, port);
        //outboundChannel = outboundChannelFuture.channel();
        responseWatchHandler = new BacksideHttpWatcherHandler(responseBuilder);

        outboundChannelFuture.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // connection complete start to read first data
                log.debug("Done setting up backend channel & it was successful");
                var pipeline = future.channel().pipeline();
                if (sslContext != null) {
                    SSLEngine sslEngine = sslContext.newEngine(future.channel().alloc());
                    sslEngine.setUseClientMode(true);
                    pipeline.addFirst("ssl", new SslHandler(sslEngine));
                }
                pipeline.addLast(new HttpResponseDecoder());
                // TODO - switch this out to use less memory.
                // We only need to know when the response has been fully received, not the contents
                // since we're already logging those in the sniffer earlier in the pipeline.
                pipeline.addLast(new HttpObjectAggregator(1024*1024));
                pipeline.addLast(responseWatchHandler);
            } else {
                // Close the connection if the connection attempt has failed.
                log.error("closing outbound channel because CONNECT future was not successful");
                log.error(future.cause());
            }
        });
    }

    @Override
    public CompletableFuture<Void> consumeBytes(ByteBuf packetData) {
        log.debug("Scheduling write of packetData["+packetData+"]" +
                " hash=" + System.identityHashCode(packetData));
        final var completableFuture = new CompletableFuture<Void>();
        if (outboundChannelFuture.isDone()) {
            Channel channel = outboundChannelFuture.channel();
            if (!channel.isActive()) {
                log.warn("Channel is not active - future packets for this connection will be dropped.");
                log.warn("Need to do more sophisticated tracking of progress and retry further up the stack");
                completableFuture.completeExceptionally(
                        new RuntimeException("The outbound channel's future has completed but " +
                                "the channel is not in an active state - dropping data"));
                return completableFuture;
            }
            log.trace("Writing data to backside handler and will return future = "+completableFuture);
            channel.writeAndFlush(packetData)
                    .addListener((ChannelFutureListener) future -> {
                        Throwable cause = null;
                        try {
                            if (!future.isSuccess()) {
                                log.warn("closing outbound channel because WRITE future was not successful " +
                                        future.cause() + " hash=" + System.identityHashCode(packetData));
                                future.channel().close(); // close the backside
                                cause = future.cause();
                            }
                        } catch (Exception e) {
                            cause = e;
                        }
                        if (cause == null) {
                            log.debug("Signaling previously returned CompletableFuture packet write was successful: "
                                    + packetData + " hash=" + System.identityHashCode(packetData));
                            completableFuture.complete(null);
                        } else {
                            log.trace("Signaling previously returned CompletableFuture packet write had an exception : "
                                    + packetData + " hash=" + System.identityHashCode(packetData));
                            completableFuture.completeExceptionally(cause);
                        }
                    });
        } else {
            log.trace("Channel isn't ready yet for writes chaining a callback to the channel's future " +
                    packetData + " hash=" + System.identityHashCode(packetData));
            log.trace("deferred future being returned = "+completableFuture);
            outboundChannelFuture.addListener(f-> {
                    if (outboundChannelFuture.isSuccess()) {
                        log.trace("outboundChannelFuture has finished - retriggering consumeBytes" +
                                " hash=" + System.identityHashCode(packetData));
                        consumeBytes(packetData).whenComplete((x,t)-> {
                            if (t != null) {
                                log.warn("inner consumeBytes has finished w/ exception t="+t +
                                        " hash=" + System.identityHashCode(packetData));
                                completableFuture.completeExceptionally(t);
                            } else {
                                log.trace("inner consumeBytes has finished w/ x="+x +
                                        " hash=" + System.identityHashCode(packetData));
                                completableFuture.complete(x);
                            }
                        });
                    } else {
                        log.warn("outbound channel was not set up successfully, NOT writing bytes " +
                                " hash=" + System.identityHashCode(packetData));
                        completableFuture.completeExceptionally(outboundChannelFuture.cause());
                    }
            });
        }
        return completableFuture;
    }

    @Override
    public CompletableFuture<AggregatedRawResponse>
    finalizeRequest() {
        var future = new CompletableFuture();
        responseWatchHandler.addCallback(arr -> future.complete(arr));
        return future;
    }
}
