/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.shim;

import java.net.URI;

import org.opensearch.migrations.transform.IJsonTransformer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * A lightweight HTTP proxy that transforms requests and responses using {@link IJsonTransformer}.
 * Sits between a client and a backend, applying JS-based transformations in both directions.
 */
@Slf4j
public class TransformationShimProxy {
    @Getter
    private final int port;
    private final URI backendUri;
    private final IJsonTransformer requestTransformer;
    private final IJsonTransformer responseTransformer;
    private Channel serverChannel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public TransformationShimProxy(int port, URI backendUri,
                                   IJsonTransformer requestTransformer,
                                   IJsonTransformer responseTransformer) {
        this.port = port;
        this.backendUri = backendUri;
        this.requestTransformer = requestTransformer;
        this.responseTransformer = responseTransformer;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        var bootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(
                        new HttpServerCodec(),
                        new HttpObjectAggregator(10 * 1024 * 1024),
                        new TransformingProxyHandler(backendUri, requestTransformer, responseTransformer)
                    );
                }
            })
            .childOption(ChannelOption.AUTO_READ, true);
        serverChannel = bootstrap.bind(port).sync().channel();
        log.info("TransformationShimProxy started on port {}, backend={}", port, backendUri);
    }

    public void stop() throws InterruptedException {
        if (serverChannel != null) {
            serverChannel.close().sync();
        }
        if (workerGroup != null) workerGroup.shutdownGracefully().sync();
        if (bossGroup != null) bossGroup.shutdownGracefully().sync();
    }
}
