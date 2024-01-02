package org.opensearch.migrations.replay;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import io.netty.buffer.Unpooled;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.transform.IHttpMessage;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamLimiter;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
import org.opensearch.migrations.transform.IAuthTransformer;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.RemovingAuthTransformerFactory;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.arns.Arn;
import software.amazon.awssdk.regions.Region;

import javax.net.ssl.SSLException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.io.EOFException;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.opensearch.migrations.coreutils.MetricsLogger.initializeOpenTelemetry;

@Slf4j
public class TrafficReplayer {

    private static final MetricsLogger TUPLE_METRICS_LOGGER = new MetricsLogger("SourceTargetCaptureTuple");

    public static final String SIGV_4_AUTH_HEADER_SERVICE_REGION_ARG = "--sigv4-auth-header-service-region";
    public static final String AUTH_HEADER_VALUE_ARG = "--auth-header-value";
    public static final String REMOVE_AUTH_HEADER_VALUE_ARG = "--remove-auth-header";
    public static final String AWS_AUTH_HEADER_USER_AND_SECRET_ARG = "--auth-header-user-and-secret";
    public static final String PACKET_TIMEOUT_SECONDS_PARAMETER_NAME = "--packet-timeout-seconds";
    public static final int MAX_ITEMS_TO_SHOW_FOR_LEFTOVER_WORK_AT_INFO_LEVEL = 10;

    private final PacketToTransformingHttpHandlerFactory inputRequestTransformerFactory;
    private final ClientConnectionPool clientConnectionPool;
    private final TrafficStreamLimiter liveTrafficStreamLimiter;
    private final AtomicInteger successfulRequestCount;
    private final AtomicInteger exceptionRequestCount;
    private ConcurrentHashMap<UniqueReplayerRequestKey,
            DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>> requestFutureMap;
    private ConcurrentHashMap<UniqueReplayerRequestKey,
            DiagnosticTrackableCompletableFuture<String, Void>> requestToFinalWorkFuturesMap;

    private AtomicBoolean stopReadingRef;
    private AtomicReference<StringTrackableCompletableFuture<Void>> allRemainingWorkFutureOrShutdownSignalRef;
    private AtomicReference<Error> shutdownReasonRef;
    private AtomicReference<CompletableFuture<Void>> shutdownFutureRef;
    private AtomicReference<CompletableFuture<List<ITrafficStreamWithKey>>> nextChunkFutureRef;
    private ConcurrentHashMap<UniqueReplayerRequestKey, Boolean> liveRequests = new ConcurrentHashMap<>();
    private Future nettyShutdownFuture;

    public class DualException extends Exception {
        public final Throwable originalCause;
        public final Throwable immediateCause;
        public DualException(Throwable originalCause, Throwable immediateCause) {
            this(null, originalCause, immediateCause);
        }
        // use one of these two so that anybody handling this as any other exception can get
        // at least one of the root errors
        public DualException(String message, Throwable originalCause, Throwable immediateCause) {
            super(message, Optional.ofNullable(originalCause).orElse(immediateCause));
            this.originalCause = originalCause;
            this.immediateCause = immediateCause;
        }
    }

    public class TerminationException extends DualException {
        public TerminationException(Throwable originalCause, Throwable immediateCause) {
            super(originalCause, immediateCause);
        }
    }

    public TrafficReplayer(URI serverUri,
                           String fullTransformerConfig,
                           IAuthTransformerFactory authTransformerFactory,
                           boolean allowInsecureConnections)
            throws SSLException {
        this(serverUri, fullTransformerConfig, authTransformerFactory, null, allowInsecureConnections,
                0, 1024);
    }


    public TrafficReplayer(URI serverUri,
                           String fullTransformerConfig,
                           IAuthTransformerFactory authTransformerFactory,
                           String userAgent,
                           boolean allowInsecureConnections,
                           int numSendingThreads, int maxConcurrentOutstandingRequests)
            throws SSLException {
        this(serverUri, authTransformerFactory, allowInsecureConnections,
                numSendingThreads, maxConcurrentOutstandingRequests,
                new TransformationLoader()
                        .getTransformerFactoryLoader(serverUri.getHost(), userAgent, fullTransformerConfig)
        );
    }
    
    public TrafficReplayer(URI serverUri,
                           IAuthTransformerFactory authTransformer,
                           boolean allowInsecureConnections,
                           int numSendingThreads, int maxConcurrentOutstandingRequests,
                           IJsonTransformer jsonTransformer)
            throws SSLException
    {
        if (serverUri.getPort() < 0) {
            throw new IllegalArgumentException("Port not present for URI: "+serverUri);
        }
        if (serverUri.getHost() == null) {
            throw new IllegalArgumentException("Hostname not present for URI: "+serverUri);
        }
        if (serverUri.getScheme() == null) {
            throw new IllegalArgumentException("Scheme (http|https) is not present for URI: "+serverUri);
        }
        inputRequestTransformerFactory = new PacketToTransformingHttpHandlerFactory(jsonTransformer, authTransformer);
        clientConnectionPool = new ClientConnectionPool(serverUri,
                loadSslContext(serverUri, allowInsecureConnections), numSendingThreads);
        requestFutureMap = new ConcurrentHashMap<>();
        requestToFinalWorkFuturesMap = new ConcurrentHashMap<>();
        successfulRequestCount = new AtomicInteger();
        exceptionRequestCount = new AtomicInteger();
        liveTrafficStreamLimiter = new TrafficStreamLimiter(maxConcurrentOutstandingRequests);
        allRemainingWorkFutureOrShutdownSignalRef = new AtomicReference<>();
        shutdownReasonRef = new AtomicReference<>();
        shutdownFutureRef = new AtomicReference<>();
        nextChunkFutureRef = new AtomicReference<>();
        stopReadingRef = new AtomicBoolean();
    }

    private static SslContext loadSslContext(URI serverUri, boolean allowInsecureConnections) throws SSLException {
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

    public static boolean validateRequiredKafkaParams(String brokers, String topic, String groupId) {
        if (brokers == null && topic == null && groupId == null) {
            return false;
        }
        if (brokers == null || topic == null || groupId == null) {
            throw new ParameterException("To enable a Kafka traffic source, the following parameters are required " +
                "[--kafka-traffic-brokers, --kafka-traffic-topic, --kafka-traffic-group-id]");
        }
        return true;
    }

    static class Parameters {
        @Parameter(required = true,
                arity = 1,
                description = "URI of the target cluster/domain")
        String targetUriString;
        @Parameter(required = false,
                names = {"--insecure"},
                arity = 0,
                description = "Do not check the server's certificate")
        boolean allowInsecureConnections;


        @Parameter(required = false,
                names = {REMOVE_AUTH_HEADER_VALUE_ARG},
                arity = 0,
                description = "Remove the authorization header if present and do not replace it with anything.  " +
                        "(cannot be used with other auth arguments)")
        boolean removeAuthHeader;
        @Parameter(required = false,
                names = {AUTH_HEADER_VALUE_ARG},
                arity = 1,
                description = "Static value to use for the \"authorization\" header of each request " +
                        "(cannot be used with other auth arguments)")
        String authHeaderValue;
        @Parameter(required = false,
                names = {AWS_AUTH_HEADER_USER_AND_SECRET_ARG},
                arity = 2,
                description = "<USERNAME> <SECRET_ARN> pair to specify " +
                        "\"authorization\" header value for each request.  " +
                        "The USERNAME specifies the plaintext user and the SECRET_ARN specifies the ARN or " +
                        "Secret name from AWS Secrets Manager to retrieve the password from for the password section" +
                        "(cannot be used with other auth arguments)")
        List<String> awsAuthHeaderUserAndSecret;
        @Parameter(required = false,
                names = {SIGV_4_AUTH_HEADER_SERVICE_REGION_ARG},
                arity = 1,
                description = "Use AWS SigV4 to sign each request with the specified service name and region.  " +
                        "(e.g. es,us-east-1)  " +
                        "DefaultCredentialsProvider is used to resolve credentials.  " +
                        "(cannot be used with other auth arguments)")
        String useSigV4ServiceAndRegion;


        @Parameter(required = false,
                names = {"-i", "--input"},
                arity=1,
                description = "input file to read the request/response traces for the source cluster")
        String inputFilename;
        @Parameter(required = false,
                names = {"-t", PACKET_TIMEOUT_SECONDS_PARAMETER_NAME},
                arity = 1,
                description = "assume that connections were terminated after this many " +
                        "seconds of inactivity observed in the captured stream")
        int observedPacketConnectionTimeout = 70;
        @Parameter(required = false,
                names = {"--speedup-factor"},
                arity = 1,
                description = "Accelerate the replayed communications by this factor.  " +
                        "This means that between each interaction will be replayed at this rate faster " +
                        "than the original observations, provided that the replayer and target are able to keep up.")
        double speedupFactor = 1.0;
        @Parameter(required = false,
                names = {"--lookahead-time-window"},
                arity = 1,
                description = "Number of seconds of data that will be buffered.")
        int lookaheadTimeSeconds = 1;
        @Parameter(required = false,
                names = {"--max-concurrent-requests"},
                arity = 1,
                description = "Maximum number of requests at a time that can be outstanding")
        int maxConcurrentRequests = 1024;
        @Parameter(required = false,
                names = {"--num-client-threads"},
                arity = 1,
                description = "Number of threads to use to send requests from.")
        int numClientThreads = 0;

        @Parameter(required = false,
            names = {"--kafka-traffic-brokers"},
            arity=1,
            description = "Comma-separated list of host and port pairs that are the addresses of the Kafka brokers to bootstrap with i.e. 'localhost:9092,localhost2:9092'")
        String kafkaTrafficBrokers;
        @Parameter(required = false,
            names = {"--kafka-traffic-topic"},
            arity=1,
            description = "Topic name used to pull messages from Kafka")
        String kafkaTrafficTopic;
        @Parameter(required = false,
            names = {"--kafka-traffic-group-id"},
            arity=1,
            description = "Consumer group id that is used when pulling messages from Kafka")
        String kafkaTrafficGroupId;
        @Parameter(required = false,
            names = {"--kafka-traffic-enable-msk-auth"},
            arity=0,
            description = "Enables SASL properties required for connecting to MSK with IAM auth")
        boolean kafkaTrafficEnableMSKAuth;
        @Parameter(required = false,
            names = {"--kafka-traffic-property-file"},
            arity=1,
            description = "File path for Kafka properties file to use for additional or overriden Kafka properties")
        String kafkaTrafficPropertyFile;

        @Parameter(required = false,
                names = {"--otelCollectorEndpoint"},
                arity = 1,
                description = "Endpoint (host:port) for the OpenTelemetry Collector to which metrics logs should be" +
                        "forwarded. If no value is provided, metrics will not be forwarded.")
        String otelCollectorEndpoint;
        @Parameter(required = false,
                names = "--transformer-config",
                arity = 1,
                description = "Configuration of message transformers.  Either as a string that identifies the " +
                        "transformer that should be run (with default settings) or as json to specify options " +
                        "as well as multiple transformers to run in sequence.  " +
                        "For json, keys are the (simple) names of the loaded transformers and values are the " +
                        "configuration passed to each of the transformers.")
        String transformerConfig;
        @Parameter(required = false,
                names = "--transformer-config-file",
                arity = 1,
                description = "Path to the JSON configuration file of message transformers.")
        String transformerConfigFile;
        @Parameter(required = false,
                names = "--user-agent",
                arity = 1,
                description = "For HTTP requests to the target cluster, append this string (after \"; \") to" +
                        "the existing user-agent field or if the field wasn't present, simply use this value")
        String userAgent;
    }

    private static Parameters parseArgs(String[] args) {
        Parameters p = new Parameters();
        JCommander jCommander = new JCommander(p);
        try {
            jCommander.parse(args);
            return p;
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            System.err.println("Got args: "+ String.join("; ", args));
            jCommander.usage();
            System.exit(2);
            return null;
        }
    }

    private static String getTransformerConfig(Parameters params)
    {
        if (params.transformerConfigFile != null && !params.transformerConfigFile.isBlank() &&
                params.transformerConfig != null && !params.transformerConfig.isBlank()) {
            System.err.println("Specify either --transformer-config or --transformer-config-file, not both.");
            System.exit(4);
        }

        if (params.transformerConfigFile != null && !params.transformerConfigFile.isBlank()) {
            try {
                return Files.readString(Paths.get(params.transformerConfigFile), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("Error reading transformer configuration file: " + e.getMessage());
                System.exit(5);
            }
        }

        if (params.transformerConfig != null && !params.transformerConfig.isBlank()) {
            return params.transformerConfig;
        }

        return null;
    }

    public static void main(String[] args)
            throws IOException, InterruptedException, ExecutionException, TerminationException
    {
        var params = parseArgs(args);
        URI uri;
        System.err.println("Starting Traffic Replayer");
        System.err.println("Got args: "+ String.join("; ", args));
        try {
            uri = new URI(params.targetUriString);
        } catch (Exception e) {
            System.err.println("Exception parsing "+params.targetUriString);
            System.err.println(e.getMessage());
            System.exit(3);
            return;
        }
        if (params.otelCollectorEndpoint != null) {
            initializeOpenTelemetry("traffic-replayer", params.otelCollectorEndpoint);
        }

        try (var blockingTrafficSource = TrafficCaptureSourceFactory.createTrafficCaptureSource(params,
                     Duration.ofSeconds(params.lookaheadTimeSeconds));
             var authTransformer = buildAuthTransformerFactory(params))
        {
            String transformerConfig = getTransformerConfig(params);
            if (transformerConfig != null)
            {
                log.info("Transformations config string: ", transformerConfig);
            }
            var tr = new TrafficReplayer(uri, transformerConfig, authTransformer, params.userAgent,
                    params.allowInsecureConnections, params.numClientThreads, params.maxConcurrentRequests);

            setupShutdownHookForReplayer(tr);
            var tupleWriter = new TupleParserChainConsumer(TUPLE_METRICS_LOGGER, new ResultsToLogsConsumer());
            var timeShifter = new TimeShifter(params.speedupFactor);
            tr.setupRunAndWaitForReplayWithShutdownChecks(Duration.ofSeconds(params.observedPacketConnectionTimeout),
                    blockingTrafficSource, timeShifter, tupleWriter);
            log.info("Done processing TrafficStreams");
        }
    }

    private static void setupShutdownHookForReplayer(TrafficReplayer tr) {
        var weakTrafficReplayer = new WeakReference<>(tr);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // both Log4J and the java builtin loggers add shutdown hooks.
            // The API for addShutdownHook says that those hooks registered will run in an undetermined order.
            // Hence, the reason that this code logs via slf4j logging AND stderr.
            {
                var beforeMsg = "Running TrafficReplayer Shutdown.  " +
                        "The logging facilities may also be shutting down concurrently, " +
                        "resulting in missing logs messages.";
                log.atWarn().setMessage(beforeMsg).log();
                System.err.println(beforeMsg);
            }
            Optional.ofNullable(weakTrafficReplayer.get()).ifPresent(o->o.shutdown(null));
            {
                var afterMsg = "Done shutting down TrafficReplayer (due to Runtime shutdown).  " +
                        "Logs may be missing for events that have happened after the Shutdown event was received.";
                log.atWarn().setMessage(afterMsg).log();
                System.err.println(afterMsg);
            }
        }));
    }

    /**
     * Java doesn't have a notion of constexpr like C++ does, so this cannot be used within the
     * parameters' annotation descriptions, but it's still useful to break the documentation
     * aspect out from the core logic below.
     */
    private static String formatAuthArgFlagsAsString() {
        return List.of(REMOVE_AUTH_HEADER_VALUE_ARG,
                        AUTH_HEADER_VALUE_ARG,
                        AWS_AUTH_HEADER_USER_AND_SECRET_ARG,
                        SIGV_4_AUTH_HEADER_SERVICE_REGION_ARG).stream()
                .collect(Collectors.joining(", "));
    }

    private static IAuthTransformerFactory buildAuthTransformerFactory(Parameters params) {
        if (params.removeAuthHeader &&
                params.authHeaderValue != null &&
                params.useSigV4ServiceAndRegion != null &&
                params.awsAuthHeaderUserAndSecret != null) {
            throw new IllegalArgumentException("Cannot specify more than one auth option: " +
                    formatAuthArgFlagsAsString());
        }

        var authHeaderValue = params.authHeaderValue;
        if (params.awsAuthHeaderUserAndSecret != null) {
            if (params.awsAuthHeaderUserAndSecret.size() != 2) {
                throw new ParameterException(AWS_AUTH_HEADER_USER_AND_SECRET_ARG +
                        " must specify two arguments, <USERNAME> <SECRET_ARN>");
            }
            var secretArnStr = params.awsAuthHeaderUserAndSecret.get(1);
            var regionOp = Arn.fromString(secretArnStr).region();
            if (regionOp.isEmpty()) {
                throw new ParameterException(AWS_AUTH_HEADER_USER_AND_SECRET_ARG +
                        " must specify two arguments, <USERNAME> <SECRET_ARN>, and SECRET_ARN must specify a region");
            }
            try (var credentialsProvider = DefaultCredentialsProvider.create();
                 AWSAuthService awsAuthService = new AWSAuthService(credentialsProvider, Region.of(regionOp.get()))) {
                authHeaderValue = awsAuthService.getBasicAuthHeaderFromSecret(params.awsAuthHeaderUserAndSecret.get(0),
                        secretArnStr);
            }
        }

        if (authHeaderValue != null) {
            return new StaticAuthTransformerFactory(authHeaderValue);
        } else if (params.useSigV4ServiceAndRegion != null) {
            var serviceAndRegion = params.useSigV4ServiceAndRegion.split(",");
            if (serviceAndRegion.length != 2) {
                throw new IllegalArgumentException("Format for " + SIGV_4_AUTH_HEADER_SERVICE_REGION_ARG + " must be " +
                        "'SERVICE_NAME,REGION', such as 'es,us-east-1'");
            }
            String serviceName = serviceAndRegion[0];
            String region = serviceAndRegion[1];

            return new IAuthTransformerFactory() {
                DefaultCredentialsProvider defaultCredentialsProvider = DefaultCredentialsProvider.create();
                @Override
                public IAuthTransformer getAuthTransformer(IHttpMessage httpMessage) {
                    return new SigV4Signer(defaultCredentialsProvider, serviceName, region, "https", null);
                }
                @Override
                public void close() {
                    defaultCredentialsProvider.close();
                }
            };
        } else if (params.removeAuthHeader) {
            return RemovingAuthTransformerFactory.instance;
        } else {
            return null; // default is to do nothing to auth headers
        }
    }

    void setupRunAndWaitForReplay(Duration observedPacketConnectionTimeout,
                                  BlockingTrafficSource trafficSource,
                                  TimeShifter timeShifter,
                                  Consumer<SourceTargetCaptureTuple> resultTupleConsumer)
            throws InterruptedException, ExecutionException {

        var senderOrchestrator = new RequestSenderOrchestrator(clientConnectionPool);
        var replayEngine = new ReplayEngine(senderOrchestrator, trafficSource, timeShifter);

        CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator =
                new CapturedTrafficToHttpTransactionAccumulator(observedPacketConnectionTimeout,
                        "(see " + PACKET_TIMEOUT_SECONDS_PARAMETER_NAME + ")",
                        new TrafficReplayerAccumulationCallbacks(replayEngine, resultTupleConsumer, trafficSource));
        try {
            pullCaptureFromSourceToAccumulator(trafficSource, trafficToHttpTransactionAccumulator);
        } catch (InterruptedException ex) {
            throw ex;
        } catch (Exception e) {
            log.atWarn().setCause(e).setMessage("Terminating runReplay due to exception").log();
            throw e;
        } finally {
            trafficToHttpTransactionAccumulator.close();
            wrapUpWorkAndEmitSummary(replayEngine, trafficToHttpTransactionAccumulator);
            if (shutdownFutureRef.get() == null) {
                assert requestToFinalWorkFuturesMap.isEmpty() :
                        "expected to wait for all the in flight requests to fully flush and self destruct themselves";
            }
        }
    }

    private void wrapUpWorkAndEmitSummary(ReplayEngine replayEngine, CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator) throws ExecutionException, InterruptedException {
        final var primaryLogLevel = Level.INFO;
        final var secondaryLogLevel = Level.WARN;
        var logLevel = primaryLogLevel;
        for (var timeout = Duration.ofSeconds(60); ; timeout = timeout.multipliedBy(2)) {
            if (shutdownFutureRef.get() != null) {
                log.warn("Not waiting for work because the TrafficReplayer is shutting down.");
                break;
            }
            try {
                waitForRemainingWork(logLevel, timeout, replayEngine);
                break;
            } catch (TimeoutException e) {
                log.atLevel(logLevel).log("Timed out while waiting for the remaining " +
                        "requests to be finalized...");
                logLevel = secondaryLogLevel;
            }
        }
        if (requestToFinalWorkFuturesMap.size() > 0 ||
                exceptionRequestCount.get() > 0) {
            log.atWarn().setMessage("{} in-flight requests being dropped due to pending shutdown; " +
                            "{} requests to the target threw an exception; " +
                            "{} requests were successfully processed.")
                    .addArgument(requestToFinalWorkFuturesMap.size())
                    .addArgument(exceptionRequestCount.get())
                    .addArgument(successfulRequestCount.get())
                    .log();
        } else {
            log.info(successfulRequestCount.get() + " requests were successfully processed.");
        }
        log.info("# of connections created: {}; # of requests on reused keep-alive connections: {}; " +
                        "# of expired connections: {}; # of connections closed: {}; " +
                        "# of connections terminated upon accumulator termination: {}",
                trafficToHttpTransactionAccumulator.numberOfConnectionsCreated(),
                trafficToHttpTransactionAccumulator.numberOfRequestsOnReusedConnections(),
                trafficToHttpTransactionAccumulator.numberOfConnectionsExpired(),
                trafficToHttpTransactionAccumulator.numberOfConnectionsClosed(),
                trafficToHttpTransactionAccumulator.numberOfRequestsTerminatedUponAccumulatorClose()
        );
    }

    void setupRunAndWaitForReplayWithShutdownChecks(Duration observedPacketConnectionTimeout,
                                                    BlockingTrafficSource trafficSource,
                                                    TimeShifter timeShifter,
                                                    Consumer<SourceTargetCaptureTuple> resultTupleConsumer)
            throws TerminationException, ExecutionException, InterruptedException {
        try {
            setupRunAndWaitForReplay(observedPacketConnectionTimeout, trafficSource,
                    timeShifter, resultTupleConsumer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TerminationException(shutdownReasonRef.get(), e);
        } catch (Throwable t) {
            throw new TerminationException(shutdownReasonRef.get(), t);
        }
        if (shutdownReasonRef.get() != null) {
            throw new TerminationException(shutdownReasonRef.get(), null);
        }
        // if nobody has run shutdown yet, do so now so that we can tear down the netty resources
        shutdown(null).get(); // if somebody already HAD run shutdown, it will return the future already created
        nettyShutdownFuture.sync();
    }

    @AllArgsConstructor
    class TrafficReplayerAccumulationCallbacks implements AccumulationCallbacks {
        private final ReplayEngine replayEngine;
        private Consumer<SourceTargetCaptureTuple> resultTupleConsumer;
        private ITrafficCaptureSource trafficCaptureSource;

        @Override
        public void onRequestReceived(UniqueReplayerRequestKey requestKey, HttpMessageAndTimestamp request) {
            replayEngine.setFirstTimestamp(request.getFirstPacketTimestamp());

            liveTrafficStreamLimiter.addWork(1);
            var requestPushFuture = transformAndSendRequest(replayEngine, request, requestKey);
            requestFutureMap.put(requestKey, requestPushFuture);
            liveRequests.put(requestKey, true);
            requestPushFuture.map(f->f.whenComplete((v,t)->{
                        liveRequests.remove(requestKey);
                        liveTrafficStreamLimiter.doneProcessing(1);
                        log.atTrace()
                                .setMessage(()->"Summary response value for " + requestKey + " returned=" + v).log();
                    }),
                    ()->"logging summary");
        }

        @Override
        public void onFullDataReceived(@NonNull UniqueReplayerRequestKey requestKey,
                                       @NonNull RequestResponsePacketPair rrPair) {
            log.atInfo().setMessage(()->"Done receiving captured stream for " + requestKey +
                    ":" + rrPair.requestData).log();
            var resultantCf = requestFutureMap.remove(requestKey)
                    .map(f -> f.handle((summary,t)->handleCompletedTransaction(requestKey, rrPair, summary, t)),
                            () -> "TrafficReplayer.runReplayWithIOStreams.progressTracker");
            if (!resultantCf.future.isDone()) {
                log.trace("Adding " + requestKey + " to targetTransactionInProgressMap");
                requestToFinalWorkFuturesMap.put(requestKey, resultantCf);
                if (resultantCf.future.isDone()) {
                    requestToFinalWorkFuturesMap.remove(requestKey);
                }
            }
        }

        Void handleCompletedTransaction(@NonNull UniqueReplayerRequestKey requestKey, RequestResponsePacketPair rrPair,
                                        TransformedTargetRequestAndResponse summary, Throwable t) {
            try {
                // if this comes in with a serious Throwable (not an Exception), don't bother
                // packaging it up and calling the callback.
                // Escalate it up out handling stack and shutdown.
                if (t == null || t instanceof Exception) {
                    packageAndWriteResponse(resultTupleConsumer, requestKey, rrPair, summary, (Exception) t);
                    commitTrafficStreams(rrPair.trafficStreamKeysBeingHeld, rrPair.completionStatus);
                    return null;
                } else {
                    log.atError().setCause(t).setMessage(()->"Throwable passed to handle() for " + requestKey +
                            ".  Rethrowing.").log();
                    throw Lombok.sneakyThrow(t);
                }
            } catch (Error error) {
                log.atError()
                        .setCause(error)
                        .setMessage(() -> "Caught error and initiating TrafficReplayer shutdown")
                        .log();
                shutdown(error);
                throw error;
            } catch (Exception e) {
                log.atError()
                        .setMessage("Unexpected exception while sending the " +
                                "aggregated response and context for {} to the callback.  " +
                                "Proceeding, but the tuple receiver context may be compromised.")
                        .addArgument(requestKey)
                        .setCause(e)
                        .log();
                throw e;
            } finally {
                requestToFinalWorkFuturesMap.remove(requestKey);
                log.trace("removed rrPair.requestData to " +
                        "targetTransactionInProgressMap for " +
                        requestKey);
            }
        }

        @Override
        public void onTrafficStreamsExpired(RequestResponsePacketPair.ReconstructionStatus status,
                                            List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {
            commitTrafficStreams(trafficStreamKeysBeingHeld, status);
        }

        @SneakyThrows
        private void commitTrafficStreams(List<ITrafficStreamKey> trafficStreamKeysBeingHeld,
                                          RequestResponsePacketPair.ReconstructionStatus status) {
            commitTrafficStreams(trafficStreamKeysBeingHeld,
                    status != RequestResponsePacketPair.ReconstructionStatus.CLOSED_PREMATURELY);
        }

        @SneakyThrows
        private void commitTrafficStreams(List<ITrafficStreamKey> trafficStreamKeysBeingHeld, boolean shouldCommit) {
            if (shouldCommit && trafficStreamKeysBeingHeld != null) {
                for (var tsk : trafficStreamKeysBeingHeld) {
                    trafficCaptureSource.commitTrafficStream(tsk);
                }
            }
        }

        @Override
        public void onConnectionClose(ISourceTrafficChannelKey channelKey, int channelInteractionNum,
                                      RequestResponsePacketPair.ReconstructionStatus status, Instant timestamp,
                                      List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {
            replayEngine.setFirstTimestamp(timestamp);
            replayEngine.closeConnection(channelKey, channelInteractionNum, timestamp);
            commitTrafficStreams(trafficStreamKeysBeingHeld, status);
        }

        @Override
        public void onTrafficStreamIgnored(@NonNull ITrafficStreamKey tsk) {
            commitTrafficStreams(List.of(tsk), true);
        }

        private TransformedTargetRequestAndResponse
        packageAndWriteResponse(Consumer<SourceTargetCaptureTuple> tupleWriter,
                                @NonNull UniqueReplayerRequestKey requestKey,
                                RequestResponsePacketPair rrPair,
                                TransformedTargetRequestAndResponse summary,
                                Exception t) {
            log.trace("done sending and finalizing data to the packet handler");

            try (var requestResponseTuple = getSourceTargetCaptureTuple(requestKey, rrPair, summary, t)) {
                log.atInfo().setMessage(()->"Source/Target Request/Response tuple: " + requestResponseTuple).log();
                tupleWriter.accept(requestResponseTuple);
            }

            if (t != null) { throw new CompletionException(t); }
            if (summary.getError() != null) {
                log.atInfo().setCause(summary.getError()).setMessage("Exception for request {}: ")
                        .addArgument(requestKey).log();
                exceptionRequestCount.incrementAndGet();
            } else if (summary.getTransformationStatus() == HttpRequestTransformationStatus.ERROR) {
                log.atInfo().setCause(summary.getError()).setMessage("Unknown error transforming the request {}: ")
                        .addArgument(requestKey).log();
                exceptionRequestCount.incrementAndGet();
            } else {
                successfulRequestCount.incrementAndGet();
            }
            return summary;
        }
    }

    private void waitForRemainingWork(Level logLevel,
                                      @NonNull Duration timeout,
                                      ReplayEngine replayEngine)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map.Entry<UniqueReplayerRequestKey, DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>>[]
                allRemainingWorkArray = requestToFinalWorkFuturesMap.entrySet().toArray(Map.Entry[]::new);
        writeStatusLogsForRemainingWork(logLevel, allRemainingWorkArray);

        // remember, this block is ONLY for the leftover items.  Lots of other items have been processed
        // and were removed from the live map (hopefully)
        DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>[] allCompletableFuturesArray =
                Arrays.stream(allRemainingWorkArray)
                        .map(Map.Entry::getValue).toArray(DiagnosticTrackableCompletableFuture[]::new);
        var allWorkFuture = StringTrackableCompletableFuture.allOf(allCompletableFuturesArray,
                () -> "TrafficReplayer.AllWorkFinished");
        try {
            if (allRemainingWorkFutureOrShutdownSignalRef.compareAndSet(null, allWorkFuture)) {
                allWorkFuture.get(timeout);
            } else {
                handleAlreadySetFinishedSignal();
            }
        } catch (TimeoutException e) {
            var didCancel = allWorkFuture.future.cancel(true);
            if (!didCancel) {
                assert allWorkFuture.future.isDone() : "expected future to have finished if cancel didn't succeed";
                // continue with the rest of the function
            } else {
                throw e;
            }
        } finally {
            allRemainingWorkFutureOrShutdownSignalRef.set(null);
        }
        allWorkFuture.getDeferredFutureThroughHandle((t, v) -> {
                    log.info("stopping packetHandlerFactory's group");
                    replayEngine.closeConnectionsAndShutdown();
                    // squash exceptions for individual requests
                    return StringTrackableCompletableFuture.completedFuture(null, () -> "finished all work");
                }, () -> "TrafficReplayer.PacketHandlerFactory->stopGroup")
                .get(); // allWorkFuture already completed - here we're just going to wait for the
                        // rest of the cleanup to finish, as per the name of the function
    }

    private void handleAlreadySetFinishedSignal() throws InterruptedException, ExecutionException {
        try {
            var finishedSignal = allRemainingWorkFutureOrShutdownSignalRef.get().future;
            assert finishedSignal.isDone() : "Expected this reference to be EITHER the current work futures " +
                    "or a sentinel value indicating a shutdown has commenced.  The signal, when set, should " +
                    "have been completed at the time that the reference was set";
            finishedSignal.get();
            log.debug("Did shutdown cleanly");
        } catch (ExecutionException e) {
            var c = e.getCause();
            if (c instanceof Error) {
                throw (Error) c;
            } else {
                throw e;
            }
        } catch (Error t) {
            log.atError().setCause(t).setMessage(() -> "Not waiting for all work to finish.  " +
                    "The TrafficReplayer is shutting down").log();
            throw t;
        }
    }

    private static void writeStatusLogsForRemainingWork(Level logLevel, Map.Entry<UniqueReplayerRequestKey, DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>>[] allRemainingWorkArray) {
        log.atLevel(logLevel).log("All remaining work to wait on " + allRemainingWorkArray.length);
        if (log.isInfoEnabled()) {
            LoggingEventBuilder loggingEventBuilderToUse = log.isTraceEnabled() ? log.atTrace() : log.atInfo();
            long itemLimit = log.isTraceEnabled() ? Long.MAX_VALUE : MAX_ITEMS_TO_SHOW_FOR_LEFTOVER_WORK_AT_INFO_LEVEL;
            loggingEventBuilderToUse.setMessage(() -> " items: " +
                            Arrays.stream(allRemainingWorkArray)
                                    .map(kvp -> kvp.getKey() + " --> " +
                                            kvp.getValue().formatAsString(TrafficReplayer::formatWorkItem))
                                    .limit(itemLimit)
                                    .collect(Collectors.joining("\n")))
                    .log();
        }
    }

    private static String formatWorkItem(DiagnosticTrackableCompletableFuture<String,?> cf) {
        try {
            var resultValue = cf.get();
            if (resultValue instanceof TransformedTargetRequestAndResponse) {
                return "" + ((TransformedTargetRequestAndResponse) resultValue).getTransformationStatus();
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Exception: " + e.getMessage();
        } catch (ExecutionException e) {
            return e.getMessage();
        }
    }

    private static SourceTargetCaptureTuple
    getSourceTargetCaptureTuple(@NonNull UniqueReplayerRequestKey uniqueRequestKey,
                                RequestResponsePacketPair rrPair,
                                TransformedTargetRequestAndResponse summary,
                                Exception t)
    {
        SourceTargetCaptureTuple requestResponseTriple;
        if (t != null) {
            log.error("Got exception in CompletableFuture callback: ", t);
            requestResponseTriple = new SourceTargetCaptureTuple(uniqueRequestKey, rrPair,
                    new TransformedPackets(), new ArrayList<>(),
                    HttpRequestTransformationStatus.ERROR, t, Duration.ZERO);
        } else {
            requestResponseTriple = new SourceTargetCaptureTuple(uniqueRequestKey, rrPair,
                    summary.requestPackets,
                    summary.getReceiptTimeAndResponsePackets()
                            .map(Map.Entry::getValue).collect(Collectors.toList()),
                    summary.getTransformationStatus(),
                    summary.getError(),
                    summary.getResponseDuration()
            );
        }
        return requestResponseTriple;
    }

    public DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>
    transformAndSendRequest(ReplayEngine replayEngine, HttpMessageAndTimestamp request,
                            UniqueReplayerRequestKey requestKey) {
        return transformAndSendRequest(inputRequestTransformerFactory, replayEngine,
                request.getFirstPacketTimestamp(), request.getLastPacketTimestamp(), requestKey,
                request.packetBytes::stream);
    }

    public static DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>
    transformAndSendRequest(PacketToTransformingHttpHandlerFactory inputRequestTransformerFactory,
                            ReplayEngine replayEngine,
                            @NonNull Instant start, @NonNull Instant end,
                            UniqueReplayerRequestKey requestKey,
                            Supplier<Stream<byte[]>> packetsSupplier)
    {
        try {
            var transformationCompleteFuture = replayEngine.scheduleTransformationWork(requestKey, start, ()->
                    transformAllData(inputRequestTransformerFactory.create(requestKey), packetsSupplier));
            log.atDebug().setMessage(()->"finalizeRequest future for transformation of " + requestKey +
                    " = " + transformationCompleteFuture).log();
            // It might be safer to chain this work directly inside the scheduleWork call above so that the
            // read buffer horizons aren't set after the transformation work finishes, but after the packets
            // are fully handled
            return transformationCompleteFuture.thenCompose(transformedResult ->
                        replayEngine.scheduleRequest(requestKey, start, end,
                                        transformedResult.transformedOutput.size(),
                                        transformedResult.transformedOutput.streamRetained())
                                .map(future->future.thenApply(t->
                                                new TransformedTargetRequestAndResponse(transformedResult.transformedOutput,
                                                        t, transformedResult.transformationStatus, t.error)),
                                        ()->"(if applicable) packaging transformed result into a completed TransformedTargetRequestAndResponse object")
                                .map(future->future.exceptionally(t->
                                                new TransformedTargetRequestAndResponse(transformedResult.transformedOutput,
                                                        transformedResult.transformationStatus, t)),
                                        ()->"(if applicable) packaging transformed result into a failed TransformedTargetRequestAndResponse object"),
                    () -> "transitioning transformed packets onto the wire")
                    .map(future->future.exceptionally(t->new TransformedTargetRequestAndResponse(null, null, t)),
                            ()->"Checking for exception out of sending data to the target server");
        } catch (Exception e) {
            log.debug("Caught exception in writeToSocket, so failing future");
            return StringTrackableCompletableFuture.failedFuture(e, ()->"TrafficReplayer.writeToSocketAndClose");
        }
    }

    private static <R> DiagnosticTrackableCompletableFuture<String, R>
    transformAllData(IPacketFinalizingConsumer<R> packetHandler, Supplier<Stream<byte[]>> packetSupplier) {
        try {
            var logLabel = packetHandler.getClass().getSimpleName();
            var packets = packetSupplier.get().map(Unpooled::wrappedBuffer);
            packets.forEach(packetData -> {
                log.atDebug().setMessage(() -> logLabel + " sending " + packetData.readableBytes() +
                        " bytes to the packetHandler").log();
                var consumeFuture = packetHandler.consumeBytes(packetData);
                log.atDebug().setMessage(() -> logLabel + " consumeFuture = " + consumeFuture).log();
            });
            log.atDebug().setMessage(() -> logLabel + "  done sending bytes, now finalizing the request").log();
            return packetHandler.finalizeRequest();
        } catch (Exception e) {
            log.atInfo().setCause(e).setMessage("Encountered an exception while transforming the http request.  " +
                    "The base64 gzipped traffic stream, for later diagnostic purposes, is: " +
                    Utils.packetsToCompressedTrafficStream(packetSupplier.get())).log();
            throw e;
        }
    }

    public @NonNull CompletableFuture<Void> shutdown(Error error) {
        log.atWarn().setCause(error).setMessage(()->"Shutting down " + this + " because of error").log();
        shutdownReasonRef.compareAndSet(null, error);
        if (!shutdownFutureRef.compareAndSet(null, new CompletableFuture<>())) {
            log.atError().setMessage(()->"Shutdown was already signaled by {}.  " +
                    "Ignoring this shutdown request due to {}.")
                    .addArgument(shutdownReasonRef.get())
                    .addArgument(error)
                    .log();
            return shutdownFutureRef.get();
        }
        stopReadingRef.set(true);
        nettyShutdownFuture = clientConnectionPool.shutdownNow()
                .addListener(f->{
                    if (f.isSuccess()) {
                        shutdownFutureRef.get().complete(null);
                    } else {
                        shutdownFutureRef.get().completeExceptionally(f.cause());
                    }
                });
        Optional.ofNullable(this.nextChunkFutureRef.get()).ifPresent(f->f.cancel(true));
        var shutdownWasSignalledFuture = error == null ?
                StringTrackableCompletableFuture.<Void>completedFuture(null, ()->"TrafficReplayer shutdown") :
                StringTrackableCompletableFuture.<Void>failedFuture(error, ()->"TrafficReplayer shutdown");
        while (!allRemainingWorkFutureOrShutdownSignalRef.compareAndSet(null, shutdownWasSignalledFuture)) {
            var otherRemainingWorkObj = allRemainingWorkFutureOrShutdownSignalRef.get();
            if (otherRemainingWorkObj != null) {
                otherRemainingWorkObj.future.cancel(true);
                break;
            }
        }
        var shutdownFuture = shutdownFutureRef.get();
        log.atWarn().setMessage(()->"Shutdown setup has been initiated").log();
        return shutdownFuture;
    }

    @SneakyThrows
    public void pullCaptureFromSourceToAccumulator(
            ITrafficCaptureSource trafficChunkStream,
            CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator)
            throws InterruptedException {
        while (true) {
            log.trace("Reading next chunk from TrafficStream supplier");
            if (stopReadingRef.get()) {
                break;
            }
            this.nextChunkFutureRef.set(trafficChunkStream.readNextTrafficStreamChunk());
            List<ITrafficStreamWithKey> trafficStreams = null;
            try {
                trafficStreams = this.nextChunkFutureRef.get().get();
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof EOFException) {
                    log.atWarn().setCause(ex.getCause()).setMessage("Got an EOF on the stream.  " +
                            "Done reading traffic streams.").log();
                    break;
                } else {
                    log.atWarn().setCause(ex).setMessage("Done reading traffic streams due to exception.").log();
                    throw ex.getCause();
                }
            }
            if (log.isInfoEnabled()) {
                Optional.of(trafficStreams.stream()
                                .map(ts -> TrafficStreamUtils.summarizeTrafficStream(ts.getStream()))
                                .collect(Collectors.joining(";")))
                        .filter(s -> !s.isEmpty())
                        .ifPresent(s -> log.atInfo().log("TrafficStream Summary: {" + s + "}"));
            }
            trafficStreams.forEach(trafficToHttpTransactionAccumulator::accept);
        }
    }
}
