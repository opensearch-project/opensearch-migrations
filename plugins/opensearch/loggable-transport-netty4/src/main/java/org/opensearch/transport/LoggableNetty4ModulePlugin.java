/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 */

package org.opensearch.transport;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.network.NetworkService;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.BigArrays;
import org.opensearch.common.util.PageCacheRecycler;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.common.xcontent.NamedXContentRegistry;
import org.opensearch.http.HttpHandlingSettings;
import org.opensearch.http.HttpServerTransport;
import org.opensearch.http.netty4.Netty4HttpServerTransport;
import org.opensearch.indices.breaker.CircuitBreakerService;
import org.opensearch.plugins.NetworkPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.threadpool.ThreadPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A plugin that adds returns a subclass of the HttpServerTransport provided by the transport-netty4 module.
 * The subclass will return a subclass of an HttpChannelHandler when a serverChannelHandler is configured.
 * That innermost HttpChannelHandler will add a handler (an instance of WireLoggingHandler) to the pipeline
 * to log HTTP requests and responses.
 *
 * To use this enhanced trace logging, install this plugin and set the <code>http.type</code> in the
 * <code>opensearch.yml</code> configuration to "http.type: logging_netty".  That will enable logging
 * requests through the server's log facilities.
 *
 * To configure the logging to go to a separate file, a configuration such as the following
 * can be added to the log4j.properties file.
 *
 * <pre>
 *     {@code
 * appender.http_trace_rolling.type = RollingFile
 * appender.http_trace_rolling.name = http_trace_rolling
 * appender.http_trace_rolling.fileName = ${sys:opensearch.logs.base_path}${sys:file.separator}${sys:opensearch.logs.cluster_name}_http_trace.log
 * appender.http_trace_rolling.filePermissions = rw-r-----
 * appender.http_trace_rolling.layout.type = PatternLayout
 * appender.http_trace_rolling.layout.pattern = [%d{ISO8601}][%-5p][%-25c{1.}] [%node_name]%marker %m%n
 *
 * appender.http_trace_rolling.filePattern = ${sys:opensearch.logs.base_path}${sys:file.separator}${sys:opensearch.logs.cluster_name}_http_trace-%i.log.gz
 * appender.http_trace_rolling.policies.type = Policies
 * appender.http_trace_rolling.policies.size.type = SizeBasedTriggeringPolicy
 * appender.http_trace_rolling.policies.size.size = 1MB
 * appender.http_trace_rolling.strategy.type = DefaultRolloverStrategy
 * appender.http_trace_rolling.strategy.max = 4
 *
 * logger.http_trace.name = org.opensearch.http.trace
 * logger.http_trace.level = trace
 * logger.http_trace.appenderRef.http_trace_rolling.ref = http_trace_rolling
 * logger.http_trace.appenderRef.header_warning.ref = header_warning
 * logger.http_trace.additivity = false
 * }
 * </pre>
 */
public class LoggableNetty4ModulePlugin extends Plugin implements NetworkPlugin {

    public static final String LOG_NAME = "org.opensearch.http.trace.WireLogger";

    /**
     * This is the identifying key that would be referenced by the <code>http.type</code> value in <code>opensearch.yml</code>
     */
    public static final String LOGGING_NETTY_TRANSPORT_NAME = "logging_netty";

    /**
     * This c'tor is only present because I needed a javadoc to add for an empty c'tor
     */
    public LoggableNetty4ModulePlugin() {}

    public class LoggingHttpServerTransport extends Netty4HttpServerTransport {

        public LoggingHttpServerTransport(Settings settings,
                                          NetworkService networkService,
                                          BigArrays bigArrays,
                                          ThreadPool threadPool,
                                          NamedXContentRegistry xContentRegistry,
                                          Dispatcher dispatcher,
                                          ClusterSettings clusterSettings,
                                          SharedGroupFactory sharedGroupFactory) {
            super(settings, networkService, bigArrays, threadPool, xContentRegistry, dispatcher, clusterSettings, sharedGroupFactory);
        }

        public class LoggingHttpChannelHandler extends Netty4HttpServerTransport.HttpChannelHandler {
            protected LoggingHttpChannelHandler(Netty4HttpServerTransport transport, HttpHandlingSettings handlingSettings) {
                super(transport, handlingSettings);
            }

            @Override
            protected void initChannel(Channel ch) throws Exception {
                super.initChannel(ch);
                ch.pipeline().addFirst(new WireLoggingHandler(LOG_NAME, LogLevel.TRACE));
            }
        }

        public ChannelHandler configureServerChannelHandler() {
            return new LoggingHttpChannelHandler(this, handlingSettings);
        }
    }

    @Override
    public Map<String, Supplier<HttpServerTransport>> getHttpTransports(
        Settings settings,
        ThreadPool threadPool,
        BigArrays bigArrays,
        PageCacheRecycler pageCacheRecycler,
        CircuitBreakerService circuitBreakerService,
        NamedXContentRegistry xContentRegistry,
        NetworkService networkService,
        HttpServerTransport.Dispatcher dispatcher,
        ClusterSettings clusterSettings
    ) {
        return Collections.singletonMap(
            LOGGING_NETTY_TRANSPORT_NAME,
            () -> new LoggingHttpServerTransport(
                settings,
                networkService,
                bigArrays,
                threadPool,
                xContentRegistry,
                dispatcher,
                clusterSettings,
                new SharedGroupFactory(settings)
            )
        );
    }
}
