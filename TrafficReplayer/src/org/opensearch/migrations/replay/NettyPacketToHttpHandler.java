package org.opensearch.migrations.replay;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.opensearch.migrations.replay.netty.BacksideHttpWatcherHandler;
import org.opensearch.migrations.replay.netty.BacksideSnifferHandler;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.Socket;
import java.net.URI;
import java.util.function.Consumer;

public class NettyPacketToHttpHandler implements IPacketToHttpHandler {

    Channel outboundChannel;
    AggregatedRawResponse.Builder responseBuilder;
    BacksideHttpWatcherHandler responseWatchHandler;

    NettyPacketToHttpHandler(NioEventLoopGroup eventLoopGroup, URI serverUri) throws IOException {
        // Start the connection attempt.
        Bootstrap b = new Bootstrap();
        responseBuilder = AggregatedRawResponse.builder();
        b.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new BacksideSnifferHandler(responseBuilder))
                .option(ChannelOption.AUTO_READ, false);
        System.err.println("Active - setting up backend connection");
        var f = b.connect(serverUri.getHost(), serverUri.getPort());
        outboundChannel = f.channel();
        responseWatchHandler = new BacksideHttpWatcherHandler(responseBuilder);

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                // connection complete start to read first data
                System.err.println("Done setting up backend channel & it was successful");
                var pipeline = future.channel().pipeline();
                pipeline.addFirst(new LoggingHandler(LogLevel.INFO));
                pipeline.addLast(new LoggingHandler(LogLevel.WARN));
                pipeline.addLast(new HttpServerCodec());
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
        if (outboundChannel.isActive()) {
            System.err.println("Writing data to backside handler");
            outboundChannel.writeAndFlush(packet)
                    .addListener((ChannelFutureListener) future -> {
                        if (future.isSuccess()) {
                            System.out.println("packet write was successful: "+packetData);
                        } else {
                            System.err.println("closing outbound channel because WRITE future was not successful");
                            future.channel().close(); // close the backside
                        }
                    });
        } // if the outbound channel has died, so be it... let this frontside finish with it's caller naturally
    }

    @Override
    public void finalizeRequest(Consumer<AggregatedRawResponse> onResponseFinishedCallback)
            throws InvalidHttpStateException {
        responseWatchHandler.addCallback(onResponseFinishedCallback);
    }
}
