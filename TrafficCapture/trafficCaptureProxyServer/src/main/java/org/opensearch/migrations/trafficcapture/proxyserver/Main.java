package org.opensearch.migrations.trafficcapture.proxyserver;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.protobuf.CodedOutputStream;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.logging.log4j.core.util.NullOutputStream;
import org.opensearch.common.settings.Settings;
import org.opensearch.migrations.trafficcapture.FileConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaCaptureFactory;
import org.opensearch.migrations.trafficcapture.proxyserver.netty.NettyScanningHttpProxy;
import org.opensearch.security.ssl.DefaultSecurityKeyStore;
import org.opensearch.security.ssl.util.SSLConfigConstants;

import javax.net.ssl.SSLEngine;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Properties;
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
                description = "Directory to store trace files in.")
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
                names = {"--sslConfigFile"},
                arity = 1,
                description = "YAML configuration of the HTTPS settings.  When this is not set, the proxy will not use TLS.")
        String sslConfigFilePath;
        @Parameter(required = false,
                names = {"--configDirectory"},
                arity = 1,
                description = "Directory that all sslConfigFile resources will be relative to.")
        String sslConfigWorkingDirectoryPath;
        @Parameter(required = false,
                names = {"--maxTrafficBufferSize"},
                arity = 1,
                description = "The maximum number of bytes that will be written to a single TrafficStream object.")
        int maximumTrafficStreamSize = 1024*1024;
        @Parameter(required = false,
                names = {"--destinationHost"},
                arity = 1,
                description = "Hostname of the server that the proxy is capturing traffic for.")
        String backsideHostname = "localhost";
        @Parameter(required = true,
                names = {"--destinationPort"},
                arity = 1,
                description = "Port of the server that the proxy is capturing traffic for.")
        int backsidePort = 0;
        @Parameter(required = true,
                names = {"--listenPort"},
                arity = 1,
                description = "Port of the server that the proxy is capturing traffic for.")
        int frontsidePort = 0;
    }

    public static Parameters parseArgs(String[] args) {
        Parameters p = new Parameters();
        JCommander jCommander = new JCommander(p);
        try {
            jCommander.parse(args);
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
        builder.put("path.home", configFile);
        return builder.build();
    }

    private static IConnectionCaptureFactory getNullConnectionCaptureFactory() {
        System.err.println("No trace log directory specified.  Logging to /dev/null");
        return connectionId -> new StreamChannelConnectionCaptureSerializer(connectionId, () ->
                CodedOutputStream.newInstance(NullOutputStream.getInstance()),
                cos -> CompletableFuture.completedFuture(null));
    }

    private static IConnectionCaptureFactory
    getTraceConnectionCaptureFactory(String traceLogsDirectory, int maxBufferSize) {
        return new FileConnectionCaptureFactory(traceLogsDirectory, maxBufferSize);
    }

    private static IConnectionCaptureFactory getKafkaConnectionFactory(String kafkaPropsPath, int bufferSize)
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
        return new KafkaCaptureFactory(new KafkaProducer<>(producerProps), bufferSize);
    }

    private static IConnectionCaptureFactory getConnectionCaptureFactory(Parameters params) throws IOException {
        // TODO - it might eventually be a requirement to do multiple types of offloading.
        // Resist the urge for now though until it comes in as a request/need.
        if (params.traceDirectory != null) {
            return new FileConnectionCaptureFactory(params.traceDirectory, params.maximumTrafficStreamSize);
        } else if (params.kafkaPropertiesFile != null) {
            return getKafkaConnectionFactory(params.kafkaPropertiesFile, params.maximumTrafficStreamSize);
        } else if (params.kafkaConnection != null) {
            var kafkaProps = new Properties();
            kafkaProps.put("bootstrap.servers", params.kafkaConnection);
            kafkaProps.put("client.id", params.kafkaClientId);
            kafkaProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            kafkaProps.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");

            return new KafkaCaptureFactory(new KafkaProducer<>(kafkaProps), params.maximumTrafficStreamSize);
        } else if (params.noCapture) {
            return getNullConnectionCaptureFactory();
        } else {
            throw new RuntimeException("Must specify some connection capture factory options");
        }
    }

    public static void main(String[] args) throws InterruptedException, IOException {

        var params = parseArgs(args);

        // This should be added to the argument parser when added in
        var sksOp = Optional.ofNullable(params.sslConfigFilePath)
                .map(o->new DefaultSecurityKeyStore(getSettings(o),
                        Optional.ofNullable(params.sslConfigWorkingDirectoryPath)
                                .map(wd->Paths.get(wd))
                                .orElse(null)));

        sksOp.ifPresent(x->x.initHttpSSLConfig());
        var proxy = new NettyScanningHttpProxy(params.frontsidePort);

        try {
            proxy.start(params.backsideHostname, params.backsidePort,
                    sksOp.map(sks-> (Supplier<SSLEngine>) () -> {
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
