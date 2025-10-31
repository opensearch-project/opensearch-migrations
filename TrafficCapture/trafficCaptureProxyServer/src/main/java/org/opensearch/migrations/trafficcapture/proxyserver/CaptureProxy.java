package org.opensearch.migrations.trafficcapture.proxyserver;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.common.settings.Settings;
import org.opensearch.migrations.jcommander.EnvVarParameterPuller;
import org.opensearch.migrations.jcommander.NoSplitter;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamHolder;
import org.opensearch.migrations.trafficcapture.FileConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.StreamLifecycleManager;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaCaptureFactory;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaConfig;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaConfig.KafkaParameters;
import org.opensearch.migrations.trafficcapture.netty.HeaderValueFilteringCapturePredicate;
import org.opensearch.migrations.trafficcapture.netty.RequestCapturePredicate;
import org.opensearch.migrations.trafficcapture.proxyserver.netty.BacksideConnectionPool;
import org.opensearch.migrations.trafficcapture.proxyserver.netty.HeaderAdderHandler;
import org.opensearch.migrations.trafficcapture.proxyserver.netty.HeaderRemoverHandler;
import org.opensearch.migrations.trafficcapture.proxyserver.netty.NettyScanningHttpProxy;
import org.opensearch.migrations.trafficcapture.proxyserver.netty.ProxyChannelInitializer;
import org.opensearch.migrations.utils.ProcessHelpers;
import org.opensearch.security.ssl.DefaultSecurityKeyStore;
import org.opensearch.security.ssl.util.SSLConfigConstants;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.ParametersDelegate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.protobuf.CodedOutputStream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.Lombok;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;

@Slf4j
public class CaptureProxy {

    private static final String HTTPS_CONFIG_PREFIX = "plugins.security.ssl.http.";
    public static final String SUPPORTED_TLS_PROTOCOLS_LIST_KEY = "plugins.security.ssl.http.enabled_protocols";

    public static class Parameters {
        @Parameter(required = false,
            names = { "--traceDirectory" },
            arity = 1,
            description = "Directory to store trace files in.")
        public String traceDirectory;
        @Parameter(required = false,
            names = { "--noCapture" },
            arity = 0,
            description = "If enabled, Does NOT capture traffic to ANY sink.")
        public boolean noCapture;
        @Parameter(required = false,
            names = { "--sslConfigFile" },
            arity = 1,
            description = "YAML configuration of the HTTPS settings.  When this is not set, the proxy will not use TLS.")
        public String sslConfigFilePath;
        @Parameter(required = false,
            names = { "--maxTrafficBufferSize" },
            arity = 1,
            description = "The maximum number of bytes that will be written to a single TrafficStream object.")
        public int maximumTrafficStreamSize = 1024 * 1024;
        @Parameter(required = false,
            names = { "--insecureDestination" },
            arity = 0,
            description = "Do not check the destination server's certificate")
        public boolean allowInsecureConnectionsToBackside;
        @Parameter(required = true,
            names = { "--destinationUri" },
            arity = 1,
            description = "URI of the server that the proxy is capturing traffic for.")
        public String backsideUriString;
        @Parameter(required = true,
            names = { "--listenPort" },
            arity = 1,
            description = "Exposed port for clients to connect to this proxy.")
        public int frontsidePort = 0;
        @Parameter(required = false,
            names = { "--numThreads" },
            arity = 1,
            description = "How many threads netty should create in its event loop group")
        public int numThreads = 1;
        @Parameter(required = false,
            names = { "--destinationConnectionPoolSize" },
            arity = 1,
            description = "Number of socket connections that should be maintained to the destination server "
                + "to reduce the perceived latency to clients.  Each thread will have its own cache, so the "
                + "total number of outstanding warm connections will be multiplied by numThreads.")
        public int destinationConnectionPoolSize = 0;
        @Parameter(required = false,
            names = { "--destinationConnectionPoolTimeout" },
            arity = 1,
            description = "Of the socket connections maintained by the destination connection pool, "
                + "how long after connection should the be recycled "
                + "(closed with a new connection taking its place)")
        public String destinationConnectionPoolTimeout = "PT30S";
        @Parameter(required = false,
            names = { "--otelCollectorEndpoint" },
            arity = 1,
            description = "Endpoint (host:port) for the OpenTelemetry Collector to which metrics logs should be forwarded."
                + "If this is not provided, metrics will not be sent to a collector.")
        public String otelCollectorEndpoint;
        @Parameter(required = false,
            names = "--setHeader",
            splitter = NoSplitter.class,
            arity = 2,
            description = "[header-name header-value] Set an HTTP header (first argument) with to the specified value" +
                " (second argument).  Any existing headers with that name will be removed.")
        public List<String> headerOverrides = new ArrayList<>();
        @Parameter(required = false,
            names = "--suppressCaptureForHeaderMatch",
            splitter = NoSplitter.class,
            arity = 2,
            description = "The header name (which will be interpreted in a case-insensitive manner) and a regex "
                + "pattern.  When the incoming request has a header that matches the regex, it will be passed "
                + "through to the service but will NOT be captured.  E.g. user-agent 'healthcheck'.")
        public List<String> suppressCaptureHeaderPairs = new ArrayList<>();
        @Parameter(required = false,
            names = "--suppressCaptureForMethod",
            arity = 1,
            description = "The regex pattern to test against the METHOD value of the incoming HTTP request.  "
                + "When the incoming request has the method that matches the regex, it will be passed "
                + "through to the service but will NOT be captured.  " +
                "E.g. 'GET' to ignore capturing GET requests.")
        public String suppressMethod;
        @Parameter(required = false,
            names = "--suppressCaptureForUriPath",
            description = "The regex pattern to test against the PATH value of the incoming HTTP request.  "
                + "When the incoming request has a path that matches the regex, it will be passed "
                + "through to the service but will NOT be captured.  " +
                "E.g. '/_cat/*' to ignore capturing traffic that doesn't begin with the path '/_cat/'.")
        public String suppressUriPath;
        @Parameter(required = false,
            names = "--suppressMethodAndPath",
            description = "The regex pattern to test against the HTTP method and uri path of the incoming HTTP request.  "
                + "When the incoming request has a METHOD and PATH that matches the regex, it will be passed "
                + "through to the service but will NOT be captured.  " +
                "E.g. '(.* /ephemeral/*|GET /_cat/.*)' to ignore capturing all traffic for '/ephemeral' AND " +
                "all GET requests to /_cat/.*")
        public String suppressMethodAndPath;
        @Parameter(required = false,
            names = { "--kafkaTopic" },
            arity = 1,
            description = "Name of the topic to write captured traffic to.")
        public String kafakTopicName = KafkaCaptureFactory.DEFAULT_TOPIC_NAME_FOR_TRAFFIC;
        @ParametersDelegate
        public KafkaParameters kafkaParameters = new KafkaParameters();
    }

    static Parameters parseArgs(String[] args) {
        Parameters p = EnvVarParameterPuller.injectFromEnv(new Parameters(), "CAPTURE_PROXY_");
        JCommander jCommander = new JCommander(p);
        try {
            jCommander.parse(args);
            // Exactly one these 3 options are required. See that exactly one is set by summing up their presence
            if (Stream.of(p.traceDirectory, p.kafkaParameters.kafkaConnection, (p.noCapture ? "" : null))
                .mapToInt(s -> s != null ? 1 : 0)
                .sum() != 1) {
                throw new ParameterException(
                    "Expected exactly one of '--traceDirectory', '--kafkaConnection', or " + "'--noCapture' to be set"
                );
            }
            return p;
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            System.err.println("Got args: " + String.join("; ", args));
            jCommander.usage();
            System.exit(2);
            return null;
        }
    }

    @SneakyThrows
    protected static Settings getSettings(@NonNull String configFile) {
        var objectMapper = new ObjectMapper(new YAMLFactory());
        var configMap = objectMapper.readValue(new File(configFile), Map.class);

        var configParentDirStr = Paths.get(configFile).toAbsolutePath().getParent();
        var httpsSettings =
            objectMapper.convertValue(configMap, new TypeReference<Map<String, Object>>(){})
                .entrySet().stream()
                .filter(kvp -> kvp.getKey().startsWith(HTTPS_CONFIG_PREFIX))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        httpsSettings.putIfAbsent(SUPPORTED_TLS_PROTOCOLS_LIST_KEY, List.of("TLSv1.2", "TLSv1.3"));

        return Settings.builder().loadFromMap(httpsSettings)
            // Don't bother with configurations the 'transport' (port 9300), which the plugin that we're using
            // will also configure (& fail) otherwise.  We only use the plugin to setup security for the 'http'
            // port and then move the SSLEngine into our implementation.
            .put(SSLConfigConstants.SECURITY_SSL_TRANSPORT_ENABLED, false)
            .put("path.home", configParentDirStr)
            .build();
    }

    protected static IConnectionCaptureFactory<Object> getNullConnectionCaptureFactory() {
        System.err.println("No trace log directory specified.  Logging to /dev/null");
        return ctx -> new StreamChannelConnectionCaptureSerializer<>(
            null,
            ctx.getConnectionId(),
            new StreamLifecycleManager<>() {
                @Override
                public CodedOutputStreamHolder createStream() {
                    return new CodedOutputStreamHolder() {
                        final CodedOutputStream nullOutputStream = CodedOutputStream.newInstance(
                            OutputStream.nullOutputStream()
                        );

                        @Override
                        public int getOutputStreamBytesLimit() {
                            return -1;
                        }

                        @Override
                        public @NonNull CodedOutputStream getOutputStream() {
                            return nullOutputStream;
                        }
                    };
                }

                @Override
                public CompletableFuture<Object> closeStream(CodedOutputStreamHolder outputStreamHolder, int index) {
                    return CompletableFuture.completedFuture(null);
                }
            }
        );
    }

    protected static String getNodeId() {
        return UUID.randomUUID().toString();
    }


    protected static IConnectionCaptureFactory<?> getConnectionCaptureFactory(
        Parameters params,
        RootCaptureContext rootContext
    ) throws IOException {
        var nodeId = getNodeId();
        // Resist the urge for now though until it comes in as a request/need.
        if (params.traceDirectory != null) {
            return new FileConnectionCaptureFactory(nodeId, params.traceDirectory, params.maximumTrafficStreamSize);
        } else if (params.kafkaParameters.kafkaConnection != null) {
            return new KafkaCaptureFactory(
                rootContext,
                nodeId,
                new KafkaProducer<>(KafkaConfig.buildKafkaProperties(params.kafkaParameters)),
                params.kafakTopicName,
                params.maximumTrafficStreamSize
            );
        } else if (params.noCapture) {
            return getNullConnectionCaptureFactory();
        } else {
            throw new IllegalStateException("Must specify some connection capture factory options");
        }
    }

    // Utility method for converting uri string to an actual URI object. Similar logic is placed in the trafficReplayer
    // module: TrafficReplayer.java
    protected static URI convertStringToUri(String uriString) {
        URI serverUri;
        try {
            serverUri = new URI(uriString);
        } catch (Exception e) {
            System.err.println("Exception parsing URI string: " + uriString);
            System.err.println(e.getMessage());
            System.exit(3);
            return null;
        }
        if (serverUri.getPort() < 0) {
            throw new IllegalArgumentException("Port not present for URI: " + serverUri);
        }
        if (serverUri.getHost() == null) {
            throw new IllegalArgumentException("Hostname not present for URI: " + serverUri);
        }
        if (serverUri.getScheme() == null) {
            throw new IllegalArgumentException("Scheme (http|https) is not present for URI: " + serverUri);
        }
        return serverUri;
    }

    protected static SslContext loadBacksideSslContext(URI serverUri, boolean allowInsecureConnections)
        throws SSLException {
        if (serverUri.getScheme().equalsIgnoreCase("https")) {
            var sslContextBuilder = SslContextBuilder.forClient();
            if (allowInsecureConnections) {
                sslContextBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            }
            return sslContextBuilder.build();
        } else {
            return null;
        }
    }

    protected static Map<String, String> convertPairListToMap(List<String> list) {
        if (list == null) {
            return Map.of();
        }
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < list.size(); i += 2) {
            map.put(list.get(i), list.get(i + 1));
        }
        return map;
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        System.err.println("Got args: " + String.join("; ", args));
        log.info("Starting Capture Proxy on " + ProcessHelpers.getNodeInstanceName());

        var params = parseArgs(args);
        var backsideUri = convertStringToUri(params.backsideUriString);

        var ctx = new RootCaptureContext(
            RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(params.otelCollectorEndpoint, "capture",
                ProcessHelpers.getNodeInstanceName()),
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())
        );

        var sksOp = Optional.ofNullable(params.sslConfigFilePath)
            .map(sslConfigFile -> new DefaultSecurityKeyStore(
                getSettings(sslConfigFile),
                Paths.get(sslConfigFile).toAbsolutePath().getParent()))
            .filter(sks -> sks.sslHTTPProvider != null);

        sksOp.ifPresent(DefaultSecurityKeyStore::initHttpSSLConfig);
        var proxy = new NettyScanningHttpProxy(params.frontsidePort);
        try {
            var pooledConnectionTimeout = params.destinationConnectionPoolSize == 0
                ? Duration.ZERO
                : Duration.parse(params.destinationConnectionPoolTimeout);
            var backsideConnectionPool = new BacksideConnectionPool(
                backsideUri,
                loadBacksideSslContext(backsideUri, params.allowInsecureConnectionsToBackside),
                params.destinationConnectionPoolSize,
                pooledConnectionTimeout
            );
            Supplier<SSLEngine> sslEngineSupplier = sksOp.map(sks -> (Supplier<SSLEngine>) () -> {
                try {
                    return sks.createHTTPSSLEngine();
                } catch (Exception e) {
                    throw Lombok.sneakyThrow(e);
                }
            }).orElse(null);
            var headerCapturePredicate = HeaderValueFilteringCapturePredicate.builder()
                .methodPattern(params.suppressMethod)
                .pathPattern(params.suppressUriPath)
                .methodAndPathPattern(params.suppressMethodAndPath)
                .protocolPattern("HTTP/2.*")
                .suppressCaptureHeaderPairs(convertPairListToMap(params.suppressCaptureHeaderPairs))
                .build();
            var proxyChannelInitializer =
                buildProxyChannelInitializer(ctx, backsideConnectionPool, sslEngineSupplier, headerCapturePredicate,
                    params.headerOverrides, getConnectionCaptureFactory(params, ctx));
            proxy.start(proxyChannelInitializer, params.numThreads);
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Caught exception while setting up the server and rethrowing").log();
            throw e;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.err.println("Received shutdown signal.  Trying to shutdown cleanly");
                proxy.stop();
                System.err.println("Done stopping the proxy.");
            } catch (InterruptedException e) {
                System.err.println("Caught InterruptedException while shutting down, resetting interrupt status: " + e);
                Thread.currentThread().interrupt();
            }
        }));
        // This loop just gives the main() function something to do while the netty event loops
        // work in the background.
        proxy.waitForClose();
    }

    @SuppressWarnings("java:S4030") // Collections removeStrings and addBufs are incorrectly reported as being unused
    static <T> ProxyChannelInitializer<T> buildProxyChannelInitializer(RootCaptureContext rootContext,
                                                                BacksideConnectionPool backsideConnectionPool,
                                                                Supplier<SSLEngine> sslEngineSupplier,
                                                                @NonNull RequestCapturePredicate headerCapturePredicate,
                                                                List<String> headerOverridesArgs,
                                                                IConnectionCaptureFactory<T> connectionFactory)
    {
        var headers = new ArrayList<>(convertPairListToMap(headerOverridesArgs).entrySet());
        Collections.reverse(headers);
        final var removeStrings = new ArrayList<String>(headers.size());
        final var addBufs = new ArrayList<ByteBuf>(headers.size());

        for (var kvp : headers) {
            addBufs.add(
                Unpooled.unreleasableBuffer(
                    Unpooled.wrappedBuffer(
                        (kvp.getKey() + ": " + kvp.getValue()).getBytes(StandardCharsets.UTF_8))));
            removeStrings.add(kvp.getKey() + ":");
        }

        return new ProxyChannelInitializer<>(
            rootContext,
            backsideConnectionPool,
            sslEngineSupplier,
            connectionFactory,
            headerCapturePredicate
        ) {
            @Override
            protected void initChannel(@NonNull SocketChannel ch) throws IOException {
                super.initChannel(ch);
                final var pipeline = ch.pipeline();
                int i = 0;
                for (var kvp : headers) {
                    pipeline.addAfter(ProxyChannelInitializer.CAPTURE_HANDLER_NAME, "AddHeader-" + kvp.getKey(),
                        new HeaderAdderHandler(addBufs.get(i++)));
                }

                i = 0;
                for (var kvp : headers) {
                    pipeline.addAfter(ProxyChannelInitializer.CAPTURE_HANDLER_NAME, "RemoveHeader-" + kvp.getKey(),
                        new HeaderRemoverHandler(removeStrings.get(i++)));
                }
            }
        };
    }
}
