package org.opensearch.migrations.replay;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.opensearch.migrations.replay.netty.BacksideHttpWatcherHandler;
import org.opensearch.migrations.replay.netty.BacksideSnifferHandler;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.function.Consumer;

public class NettyPacketToHttpSender implements IPacketToHttpHandler {

    ChannelFuture outboundChannelFuture;
    AggregatedRawResponse.Builder responseBuilder;
    BacksideHttpWatcherHandler responseWatchHandler;

    NettyPacketToHttpSender(NioEventLoopGroup eventLoopGroup, URI serverUri) throws IOException {
        // Start the connection attempt.
        Bootstrap b = new Bootstrap();
        responseBuilder = AggregatedRawResponse.builder(Instant.now());
        b.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new BacksideSnifferHandler(responseBuilder))
                .option(ChannelOption.AUTO_READ, false);
        System.err.println("Active - setting up backend connection");
        outboundChannelFuture = b.connect(serverUri.getHost(), serverUri.getPort());
        //outboundChannel = outboundChannelFuture.channel();
        responseWatchHandler = new BacksideHttpWatcherHandler(responseBuilder);

        outboundChannelFuture.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // connection complete start to read first data
                System.err.println("Done setting up backend channel & it was successful");
                var pipeline = future.channel().pipeline();
                pipeline.addFirst(new LoggingHandler(LogLevel.INFO));
                pipeline.addLast(new HttpResponseDecoder());
                pipeline.addLast(new LoggingHandler(LogLevel.WARN));
                // TODO - switch this out to use less memory.
                // We only need to know when the response has been fully received, not the contents
                // since we're already logging those in the sniffer earlier in the pipeline.
                pipeline.addLast(new HttpObjectAggregator(1024*1024));
                pipeline.addLast(responseWatchHandler);
                pipeline.addLast(new LoggingHandler(LogLevel.ERROR));
            } else {
                // Close the connection if the connection attempt has failed.
                System.err.println("closing outbound channel because CONNECT future was not successful");
            }
        });
    }

    @Override
    public void consumeBytes(byte[] packetData) throws InvalidHttpStateException {
        System.err.println("Writing packetData="+packetData);
        var packet = Unpooled.wrappedBuffer(packetData);
        if (outboundChannelFuture.isDone()) {
            Channel channel = outboundChannelFuture.channel();
            if (!channel.isActive()) {
                System.err.println("Channel is not active - future packets for this connection will be dropped.");
                System.err.println("Need to do more sophisticated tracking of progress and retry further up the stack");
                return;
            }
            System.err.println("Writing data to backside handler");
            channel.writeAndFlush(packet)
                    .addListener((ChannelFutureListener) future -> {
                        if (future.isSuccess()) {
                            System.err.println("packet write was successful: "+packetData);
                        } else {
                            System.err.println("closing outbound channel because WRITE future was not successful "+future.cause());
                            future.channel().close(); // close the backside
                        }
                    });
        } else {
            outboundChannelFuture.addListener(f->consumeBytes(packetData));
        }
    }

    @Override
    public void finalizeRequest(Consumer<AggregatedRawResponse> onResponseFinishedCallback)
            throws InvalidHttpStateException {
        responseWatchHandler.addCallback(onResponseFinishedCallback);
    }
}
