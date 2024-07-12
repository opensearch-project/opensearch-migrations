package org.opensearch.migrations.trafficcapture.proxyserver;

import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import com.google.protobuf.CodedOutputStream;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;

import org.opensearch.common.settings.Settings;
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
import org.opensearch.migrations.trafficcapture.netty.HeaderValueFilteringCapturePredicate;
import org.opensearch.migrations.trafficcapture.proxyserver.netty.BacksideConnectionPool;
import org.opensearch.migrations.trafficcapture.proxyserver.netty.NettyScanningHttpProxy;
import org.opensearch.security.ssl.DefaultSecurityKeyStore;
import org.opensearch.security.ssl.util.SSLConfigConstants;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.Lombok;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CaptureProxy {

    private static final String HTTPS_CONFIG_PREFIX = "plugins.security.ssl.http.";
    public static final String DEFAULT_KAFKA_CLIENT_ID = "HttpCaptureProxyProducer";

    public static class Parameters {
        @Parameter(required = false, names = {
            "--traceDirectory" }, arity = 1, description = "Directory to store trace files in.")
        public String traceDirectory;
        @Parameter(required = false, names = {
            "--noCapture" }, arity = 0, description = "If enabled, Does NOT capture traffic to ANY sink.")
        public boolean noCapture;
        @Parameter(required = false, names = {
            "--kafkaConfigFile" }, arity = 1, description = "Kafka properties file for additional client customization.")
        public String kafkaPropertiesFile;
        @Parameter(required = false, names = {
            "--kafkaClientId" }, arity = 1, description = "clientId to use for interfacing with Kafka.")
        public String kafkaClientId = DEFAULT_KAFKA_CLIENT_ID;
        @Parameter(required = false, names = {
            "--kafkaConnection" }, arity = 1, description = "Sequence of <HOSTNAME:PORT> values delimited by ','.")
        public String kafkaConnection;
        @Parameter(required = false, names = {
            "--enableMSKAuth" }, arity = 0, description = "Enables SASL Kafka properties required for connecting to MSK with IAM auth.")
        public boolean mskAuthEnabled = false;
        @Parameter(required = false, names = {
            "--sslConfigFile" }, arity = 1, description = "YAML configuration of the HTTPS settings.  When this is not set, the proxy will not use TLS.")
        public String sslConfigFilePath;
        @Parameter(required = false, names = {
            "--maxTrafficBufferSize" }, arity = 1, description = "The maximum number of bytes that will be written to a single TrafficStream object.")
        public int maximumTrafficStreamSize = 1024 * 1024;
        @Parameter(required = false, names = {
            "--insecureDestination" }, arity = 0, description = "Do not check the destination server's certificate")
        public boolean allowInsecureConnectionsToBackside;
        @Parameter(required = true, names = {
            "--destinationUri" }, arity = 1, description = "URI of the server that the proxy is capturing traffic for.")
        public String backsideUriString;
        @Parameter(required = true, names = {
            "--listenPort" }, arity = 1, description = "Exposed port for clients to connect to this proxy.")
        public int frontsidePort = 0;
        @Parameter(required = false, names = {
            "--numThreads" }, arity = 1, description = "How many threads netty should create in its event loop group")
        public int numThreads = 1;
        @Parameter(required = false, names = {
            "--destinationConnectionPoolSize" }, arity = 1, description = "Number of socket connections that should be maintained to the destination server "
                + "to reduce the perceived latency to clients.  Each thread will have its own cache, so the "
                + "total number of outstanding warm connections will be multiplied by numThreads.")
        public int destinationConnectionPoolSize = 0;
        @Parameter(required = false, names = {
            "--destinationConnectionPoolTimeout" }, arity = 1, description = "Of the socket connections maintained by the destination connection pool, "
                + "how long after connection should the be recycled "
                + "(closed with a new connection taking its place)")
        public String destinationConnectionPoolTimeout = "PT30S";
        @Parameter(required = false, names = {
            "--otelCollectorEndpoint" }, arity = 1, description = "Endpoint (host:port) for the OpenTelemetry Collector to which metrics logs should be forwarded."
                + "If this is not provided, metrics will not be sent to a collector.")
        public String otelCollectorEndpoint;
        @Parameter(required = false, names = "--suppressCaptureForHeaderMatch", arity = 2, description = "The header name (which will be interpreted in a case-insensitive manner) and a regex "
            + "pattern.  When the incoming request has a header that matches the regex, it will be passed "
            + "through to the service but will NOT be captured.  E.g. user-agent 'healthcheck'.")
        public List<String> suppressCaptureHeaderPairs = new ArrayList<>();
    }

    static Parameters parseArgs(String[] args) {
        Parameters p = new Parameters();
        JCommander jCommander = new JCommander(p);
        try {
            jCommander.parse(args);
            // Exactly one these 3 options are required. See that exactly one is set by summing up their presence
            if (Stream.of(p.traceDirectory, p.kafkaConnection, (p.noCapture ? "" : null))
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
        var builder = Settings.builder();
        try (var lines = Files.lines(Paths.get(configFile))) {
            lines.map(
                line -> Optional.of(line.indexOf('#')).filter(i -> i >= 0).map(i -> line.substring(0, i)).orElse(line)
            ).filter(line -> line.startsWith(HTTPS_CONFIG_PREFIX) && line.contains(":")).forEach(line -> {
                var parts = line.split(": *", 2);
                builder.put(parts[0], parts[1]);
            });
        }
        builder.put(SSLConfigConstants.SECURITY_SSL_TRANSPORT_ENABLED, false);
        var configParentDirStr = Paths.get(configFile).toAbsolutePath().getParent();
        builder.put("path.home", configParentDirStr);
        return builder.build();
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

    static Properties buildKafkaProperties(Parameters params) throws IOException {
        var kafkaProps = new Properties();
        kafkaProps.put(
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.StringSerializer"
        );
        kafkaProps.put(
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.ByteArraySerializer"
        );
        // Property details:
        // https://docs.confluent.io/platform/current/installation/configuration/producer-configs.html#delivery-timeout-ms
        kafkaProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        kafkaProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        kafkaProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000);

        if (params.kafkaPropertiesFile != null) {
            try (var fileReader = new FileReader(params.kafkaPropertiesFile)) {
                kafkaProps.load(fileReader);
            } catch (IOException e) {
                log.error(
                    "Unable to locate provided Kafka producer properties file path: " + params.kafkaPropertiesFile
                );
                throw e;
            }
        }

        kafkaProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, params.kafkaConnection);
        kafkaProps.put(ProducerConfig.CLIENT_ID_CONFIG, params.kafkaClientId);
        if (params.mskAuthEnabled) {
            kafkaProps.setProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
            kafkaProps.setProperty(SaslConfigs.SASL_MECHANISM, "AWS_MSK_IAM");
            kafkaProps.setProperty(
                SaslConfigs.SASL_JAAS_CONFIG,
                "software.amazon.msk.auth.iam.IAMLoginModule required;"
            );
            kafkaProps.setProperty(
                SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS,
                "software.amazon.msk.auth.iam.IAMClientCallbackHandler"
            );
        }
        return kafkaProps;
    }

    protected static IConnectionCaptureFactory getConnectionCaptureFactory(
        Parameters params,
        RootCaptureContext rootContext
    ) throws IOException {
        var nodeId = getNodeId();
        // Resist the urge for now though until it comes in as a request/need.
        if (params.traceDirectory != null) {
            return new FileConnectionCaptureFactory(nodeId, params.traceDirectory, params.maximumTrafficStreamSize);
        } else if (params.kafkaConnection != null) {
            return new KafkaCaptureFactory(
                rootContext,
                nodeId,
                new KafkaProducer<>(buildKafkaProperties(params)),
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
        var map = new TreeMap<String, String>();
        for (int i = 0; i < list.size(); i += 2) {
            map.put(list.get(i), list.get(i + 1));
        }
        return map;
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        System.err.println("Starting Capture Proxy");
        System.err.println("Got args: " + String.join("; ", args));

        var params = parseArgs(args);
        var backsideUri = convertStringToUri(params.backsideUriString);

        var rootContext = new RootCaptureContext(
            RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(params.otelCollectorEndpoint, "capture"),
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())
        );

        var sksOp = Optional.ofNullable(params.sslConfigFilePath)
            .map(
                sslConfigFile -> new DefaultSecurityKeyStore(
                    getSettings(sslConfigFile),
                    Paths.get(sslConfigFile).toAbsolutePath().getParent()
                )
            );

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
            var headerCapturePredicate = new HeaderValueFilteringCapturePredicate(
                convertPairListToMap(params.suppressCaptureHeaderPairs)
            );
            proxy.start(
                rootContext,
                backsideConnectionPool,
                params.numThreads,
                sslEngineSupplier,
                getConnectionCaptureFactory(params, rootContext),
                headerCapturePredicate
            );
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
}
