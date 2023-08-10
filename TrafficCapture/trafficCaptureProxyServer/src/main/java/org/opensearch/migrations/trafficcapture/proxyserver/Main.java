package org.opensearch.migrations.trafficcapture.proxyserver;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.protobuf.CodedOutputStream;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.logging.log4j.core.util.NullOutputStream;
import org.opensearch.common.settings.Settings;
import org.opensearch.migrations.trafficcapture.FileConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaCaptureFactory;
import org.opensearch.migrations.trafficcapture.proxyserver.netty.BacksideConnectionPool;
import org.opensearch.migrations.trafficcapture.proxyserver.netty.NettyScanningHttpProxy;
import org.opensearch.security.ssl.DefaultSecurityKeyStore;
import org.opensearch.security.ssl.util.SSLConfigConstants;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;

@Slf4j
public class Main {

    private final static String HTTPS_CONFIG_PREFIX = "plugins.security.ssl.http.";

    static class Parameters {
        @Parameter(required = false,
                names = {"--traceDirectory"},
                arity = 1,
                description = "Directory to store trace files in.")
        String traceDirectory;
        @Parameter(required = false,
                names = {"--noCapture"},
                arity = 0,
                description = "If enabled, Does NOT capture traffic to ANY sink.")
        boolean noCapture;
        @Parameter(required = false,
                names = {"--kafkaConfigFile"},
                arity = 1,
                description = "Kafka properties file")
        String kafkaPropertiesFile;
        @Parameter(required = false,
                names = {"--kafkaClientId"},
                arity = 1,
                description = "clientId to use for interfacing with Kafka.")
        String kafkaClientId = "KafkaLoggingProducer";
        @Parameter(required = false,
                names = {"--kafkaConnection"},
                arity = 1,
                description = "Sequence of <HOSTNAME:PORT> values delimited by ','.")
        String kafkaConnection;
        @Parameter(required = false,
            names = {"--enableMSKAuth"},
            arity = 0,
            description = "Enables SASL Kafka properties required for connecting to MSK with IAM auth.")
        boolean mskAuthEnabled = false;
        @Parameter(required = false,
                names = {"--sslConfigFile"},
                arity = 1,
                description = "YAML configuration of the HTTPS settings.  When this is not set, the proxy will not use TLS.")
        String sslConfigFilePath;
        @Parameter(required = false,
                names = {"--maxTrafficBufferSize"},
                arity = 1,
                description = "The maximum number of bytes that will be written to a single TrafficStream object.")
        int maximumTrafficStreamSize = 1024*1024;
        @Parameter(required = false,
            names = {"--insecureDestination"},
            arity = 0,
            description = "Do not check the destination server's certificate")
        boolean allowInsecureConnectionsToBackside;
        @Parameter(required = true,
                names = {"--destinationUri"},
                arity = 1,
                description = "URI of the server that the proxy is capturing traffic for.")
        String backsideUriString;
        @Parameter(required = true,
                names = {"--listenPort"},
                arity = 1,
                description = "Exposed port for clients to connect to this proxy.")
        int frontsidePort = 0;
        @Parameter(required = false,
                names = {"--numThreads"},
                arity = 1,
                description = "How many threads netty should create in its event loop group")
        int numThreads = 1;
        @Parameter(required = false,
        names = {"--destinationConnectionPoolSize"},
        arity = 1,
        description = "Number of socket connections that should be maintained to the destination server " +
                "to reduce the perceived latency to clients.  Each thread will have its own cache, so the " +
                "total number of outstanding warm connections will be multiplied by numThreads.")
        int destinationConnectionPoolSize = 0;
        @Parameter(required = false,
                names = {"--destinationConnectionPoolTimeout"},
                arity = 1,
                description = "Of the socket connections maintained by the destination connection pool, " +
                        "how long after connection should the be recycled " +
                        "(closed with a new connection taking its place)")
        String destinationConnectionPoolTimeout = "PT30S";
    }

    public static Parameters parseArgs(String[] args) {
        Parameters p = new Parameters();
        JCommander jCommander = new JCommander(p);
        try {
            jCommander.parse(args);
            // Exactly one these 4 options are required.  See that exactly one is set by summing up their presence
            if (Stream.of(p.traceDirectory, p.kafkaPropertiesFile, p.kafkaConnection, (p.noCapture?"":null))
                    .mapToInt(s->s!=null?1:0).sum() != 1) {
                throw new ParameterException("Expected exactly one of '--traceDirectory', '--kafkaConfigFile', " +
                        "'--kafkaConnection', or '--noCapture' to be set");
            }
            return p;
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            System.err.println("Got args: "+ String.join("; ", args));
            jCommander.usage();
            return null;
        }
    }

    @SneakyThrows
    private static Settings getSettings(@NonNull String configFile) {
        var builder = Settings.builder();
        try (var lines = Files.lines(Paths.get(configFile))) {
            lines
                    .map(line->Optional.of(line.indexOf('#')).filter(i->i>=0).map(i->line.substring(0, i)).orElse(line))
                    .filter(line->line.startsWith(HTTPS_CONFIG_PREFIX) && line.contains(":"))
                    .forEach(line->{
                        var parts = line.split(": *", 2);
                        builder.put(parts[0], parts[1]);
                    });
        }
        builder.put(SSLConfigConstants.SECURITY_SSL_TRANSPORT_ENABLED, false);
        var configParentDirStr = Paths.get(configFile).toAbsolutePath().getParent();
        builder.put("path.home", configParentDirStr);
        return builder.build();
    }

    private static IConnectionCaptureFactory getNullConnectionCaptureFactory() {
        System.err.println("No trace log directory specified.  Logging to /dev/null");
        return connectionId -> new StreamChannelConnectionCaptureSerializer(null, connectionId, () ->
                CodedOutputStream.newInstance(new byte[1024*1024]),
                cos -> CompletableFuture.completedFuture(null));
    }


    private static IConnectionCaptureFactory getKafkaConnectionFactory(String nodeId,
                                                                       String kafkaPropsPath,
                                                                       int bufferSize)
            throws IOException {
        Properties producerProps = new Properties();
        if (kafkaPropsPath != null) {
            try {
                producerProps.load(new FileReader(kafkaPropsPath));
            } catch (IOException e) {
                log.error("Unable to locate provided Kafka producer properties file path: " + kafkaPropsPath);
                throw e;
            }
        }
        return new KafkaCaptureFactory(nodeId, new KafkaProducer<>(producerProps), bufferSize);
    }

    private static String getNodeId(Parameters params) {
        return UUID.randomUUID().toString();
    }

    private static IConnectionCaptureFactory getConnectionCaptureFactory(Parameters params) throws IOException {
        var nodeId = getNodeId(params);
        // TODO - it might eventually be a requirement to do multiple types of offloading.
        // Resist the urge for now though until it comes in as a request/need.
        if (params.traceDirectory != null) {
            return new FileConnectionCaptureFactory(nodeId, params.traceDirectory, params.maximumTrafficStreamSize);
        } else if (params.kafkaPropertiesFile != null) {
            if (params.kafkaConnection != null || params.mskAuthEnabled) {
                log.warn("--kafkaConnection and --enableMSKAuth options are ignored when providing a Kafka properties file (--kafkaConfigFile) ");
            }
            return getKafkaConnectionFactory(nodeId, params.kafkaPropertiesFile, params.maximumTrafficStreamSize);
        } else if (params.kafkaConnection != null) {
            var kafkaProps = new Properties();
            kafkaProps.put("bootstrap.servers", params.kafkaConnection);
            kafkaProps.put("client.id", params.kafkaClientId);
            kafkaProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            kafkaProps.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
            if (params.mskAuthEnabled) {
                kafkaProps.setProperty("security.protocol", "SASL_SSL");
                kafkaProps.setProperty("sasl.mechanism", "AWS_MSK_IAM");
                kafkaProps.setProperty("sasl.jaas.config", "software.amazon.msk.auth.iam.IAMLoginModule required;");
                kafkaProps.setProperty("sasl.client.callback.handler.class", "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
            }

            return new KafkaCaptureFactory(nodeId, new KafkaProducer<>(kafkaProps), params.maximumTrafficStreamSize);
        } else if (params.noCapture) {
            return getNullConnectionCaptureFactory();
        } else {
            throw new RuntimeException("Must specify some connection capture factory options");
        }
    }

    // Utility method for converting uri string to an actual URI object. Similar logic is placed in the trafficReplayer
    // module: TrafficReplayer.java
    private static URI convertStringToUri(String uriString) {
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
            throw new RuntimeException("Port not present for URI: " + serverUri);
        }
        if (serverUri.getHost() == null) {
            throw new RuntimeException("Hostname not present for URI: " + serverUri);
        }
        if (serverUri.getScheme() == null) {
            throw new RuntimeException("Scheme (http|https) is not present for URI: " + serverUri);
        }
        return serverUri;
    }

    private static SslContext loadBacksideSslContext(URI serverUri, boolean allowInsecureConnections) throws
        SSLException {
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

    public static void main(String[] args) throws InterruptedException, IOException {

        var params = parseArgs(args);
        var backsideUri = convertStringToUri(params.backsideUriString);

        var sksOp = Optional.ofNullable(params.sslConfigFilePath)
                .map(sslConfigFile->new DefaultSecurityKeyStore(getSettings(sslConfigFile),
                        Paths.get(sslConfigFile).toAbsolutePath().getParent()));

        sksOp.ifPresent(x->x.initHttpSSLConfig());
        var proxy = new NettyScanningHttpProxy(params.frontsidePort);
        try {
            var pooledConnectionTimeout = params.destinationConnectionPoolSize == 0 ? Duration.ZERO :
                    Duration.parse(params.destinationConnectionPoolTimeout);
            var backsideConnectionPool = new BacksideConnectionPool(backsideUri,
                    loadBacksideSslContext(backsideUri, params.allowInsecureConnectionsToBackside),
                    params.destinationConnectionPoolSize, pooledConnectionTimeout);
            proxy.start(backsideConnectionPool, params.numThreads,
                    sksOp.map(sks -> (Supplier<SSLEngine>) () -> {
                        try {
                            var sslEngine = sks.createHTTPSSLEngine();
                            return sslEngine;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }).orElse(null), getConnectionCaptureFactory(params));
        } catch (Exception e) {
            System.err.println(e);
            e.printStackTrace();
            throw e;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.err.println("Received shutdown signal.  Trying to shutdown cleanly");
                proxy.stop();
                System.err.println("Done stopping the proxy.");
            } catch (InterruptedException e) {
                System.err.println("Caught exception while shutting down: "+e);
                throw new RuntimeException(e);
            }
        }));
        // This loop just gives the main() function something to do while the netty event loops
        // work in the background.
        while (true) {
            // TODO: The kill signal will cause the sleep to throw an InterruptedException,
            // which may not be what we want to do - it seems like returning an exit code of 0
            // might make more sense.  This is something to research - e.g. by seeing if there's
            // specific behavior that POSIX recommends/requires.
            Thread.sleep(60*1000);
        }
    }
}
