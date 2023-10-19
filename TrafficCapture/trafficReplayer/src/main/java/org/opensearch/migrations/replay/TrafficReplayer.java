package org.opensearch.migrations.replay;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import io.netty.buffer.Unpooled;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamLimiter;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.JsonCompositeTransformer;
import org.opensearch.migrations.transform.JsonJoltTransformer;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JsonTypeMappingTransformer;
import org.opensearch.migrations.transform.RemovingAuthTransformerFactory;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import javax.net.ssl.SSLException;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class TrafficReplayer {

    public static final String SIGV_4_AUTH_HEADER_SERVICE_REGION_ARG = "--sigv4-auth-header-service-region";
    public static final String AUTH_HEADER_VALUE_ARG = "--auth-header-value";
    public static final String REMOVE_AUTH_HEADER_VALUE_ARG = "--remove-auth-header";
    public static final String AWS_AUTH_HEADER_USER_AND_SECRET_ARG = "--auth-header-user-and-secret";
    public static final String PACKET_TIMEOUT_SECONDS_PARAMETER_NAME = "--packet-timeout-seconds";
    public static final int MAX_ITEMS_TO_SHOW_FOR_LEFTOVER_WORK_AT_INFO_LEVEL = 10;
    private final PacketToTransformingHttpHandlerFactory inputRequestTransformerFactory;
    private final ClientConnectionPool clientConnectionPool;
    private final TrafficStreamLimiter liveTrafficStreamLimiter;
    private final AtomicInteger successCount;
    private final AtomicInteger exceptionCount;
    private ConcurrentHashMap<UniqueRequestKey,
            DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>> requestFutureMap;
    private ConcurrentHashMap<UniqueRequestKey,
            DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>> requestToFinalWorkFuturesMap;

    public static IJsonTransformer buildDefaultJsonTransformer(String newHostName) {
        var joltJsonTransformerBuilder = JsonJoltTransformer.newBuilder()
                .addHostSwitchOperation(newHostName);
        var joltJsonTransformer = joltJsonTransformerBuilder.build();
        return new JsonCompositeTransformer(joltJsonTransformer, new JsonTypeMappingTransformer());
    }

    public TrafficReplayer(URI serverUri,
                           IAuthTransformerFactory authTransformerFactory,
                           boolean allowInsecureConnections)
            throws SSLException {
        this(serverUri, authTransformerFactory, allowInsecureConnections, 0, 1024);
    }


    public TrafficReplayer(URI serverUri,
                           IAuthTransformerFactory authTransformerFactory,
                           boolean allowInsecureConnections,
                           int numSendingThreads, int maxConcurrentOutstandingRequests)
            throws SSLException {
        this(serverUri, authTransformerFactory, allowInsecureConnections,
                numSendingThreads, maxConcurrentOutstandingRequests,
                buildDefaultJsonTransformer(serverUri.getHost())
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
            throw new RuntimeException("Port not present for URI: "+serverUri);
        }
        if (serverUri.getHost() == null) {
            throw new RuntimeException("Hostname not present for URI: "+serverUri);
        }
        if (serverUri.getScheme() == null) {
            throw new RuntimeException("Scheme (http|https) is not present for URI: "+serverUri);
        }
        inputRequestTransformerFactory = new PacketToTransformingHttpHandlerFactory(jsonTransformer, authTransformer);
        clientConnectionPool = new ClientConnectionPool(serverUri,
                loadSslContext(serverUri, allowInsecureConnections), numSendingThreads);
        requestFutureMap = new ConcurrentHashMap<>();
        requestToFinalWorkFuturesMap = new ConcurrentHashMap<>();
        successCount = new AtomicInteger();
        exceptionCount = new AtomicInteger();
        liveTrafficStreamLimiter = new TrafficStreamLimiter(maxConcurrentOutstandingRequests);
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
                names = {"-o", "--output"},
                arity=1,
                description = "output file to hold the request/response traces for the source and target cluster")
        String outputFilename;

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
    }

    public static Parameters parseArgs(String[] args) {
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


    public static void main(String[] args) throws IOException, InterruptedException, ExecutionException {
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

        try (OutputStream outputStream = params.outputFilename == null ? System.out :
                new FileOutputStream(params.outputFilename, true)) {
            try (var bufferedOutputStream = new BufferedOutputStream(outputStream)) {
                try (var blockingTrafficStream = TrafficCaptureSourceFactory.createTrafficCaptureSource(params,
                        Duration.ofSeconds(params.lookaheadTimeSeconds))) {
                    var tr = new TrafficReplayer(uri, buildAuthTransformerFactory(params),
                            params.allowInsecureConnections, params.numClientThreads,  params.maxConcurrentRequests);
                    tr.runReplayWithIOStreams(Duration.ofSeconds(params.observedPacketConnectionTimeout),
                            blockingTrafficStream, bufferedOutputStream, new TimeShifter(params.speedupFactor));
                    log.info("reached the end of the ingestion output stream");
                }
            }
        }
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
            throw new RuntimeException("Cannot specify more than one auth option: " +
                    formatAuthArgFlagsAsString());
        }

        var authHeaderValue = params.authHeaderValue;
        if (params.awsAuthHeaderUserAndSecret != null) {
            if (params.awsAuthHeaderUserAndSecret.size() != 2) {
                throw new ParameterException(AWS_AUTH_HEADER_USER_AND_SECRET_ARG +
                        " must specify two arguments, <USERNAME> <SECRET_ARN>");
            }
            try (AWSAuthService awsAuthService = new AWSAuthService()) {
                authHeaderValue = awsAuthService.getBasicAuthHeaderFromSecret(params.awsAuthHeaderUserAndSecret.get(0),
                        params.awsAuthHeaderUserAndSecret.get(1));
            }
        }

        if (authHeaderValue != null) {
            return new StaticAuthTransformerFactory(authHeaderValue);
        } else if (params.useSigV4ServiceAndRegion != null) {
            var serviceAndRegion = params.useSigV4ServiceAndRegion.split(",");
            if (serviceAndRegion.length != 2) {
                throw new RuntimeException("Format for " + SIGV_4_AUTH_HEADER_SERVICE_REGION_ARG + " must be " +
                        "'SERVICE_NAME,REGION', such as 'es,us-east-1'");
            }
            String serviceName = serviceAndRegion[0];
            String region = serviceAndRegion[1];
            DefaultCredentialsProvider defaultCredentialsProvider = DefaultCredentialsProvider.create();

            return httpMessage -> new SigV4Signer(defaultCredentialsProvider, serviceName, region, "https", null);
        } else if (params.removeAuthHeader) {
            return RemovingAuthTransformerFactory.instance;
        } else {
            return null; // default is to do nothing to auth headers
        }
    }

    void runReplayWithIOStreams(Duration observedPacketConnectionTimeout,
                                        BlockingTrafficSource trafficChunkStream,
                                        BufferedOutputStream bufferedOutputStream,
                                        TimeShifter timeShifter)
            throws InterruptedException, ExecutionException {

        var senderOrchestrator = new RequestSenderOrchestrator(clientConnectionPool);
        var replayEngine = new ReplayEngine(senderOrchestrator, trafficChunkStream, timeShifter);

        var tupleWriter = new SourceTargetCaptureTuple.TupleToFileWriter(bufferedOutputStream);
        CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator =
                new CapturedTrafficToHttpTransactionAccumulator(observedPacketConnectionTimeout,
                        "(see " + PACKET_TIMEOUT_SECONDS_PARAMETER_NAME + ")",
                        getRecordedRequestReconstructCompleteHandler(replayEngine),
                        getRecordedRequestAndResponseReconstructCompleteHandler(tupleWriter),
                        (requestKey, timestamp) -> {
                            replayEngine.setFirstTimestamp(timestamp);
                            replayEngine.closeConnection(requestKey, timestamp);
                        });
        try {
            pullReplayFromSourceToAccumulator(trafficChunkStream, trafficToHttpTransactionAccumulator);
        } catch (Exception e) {
            log.warn("Terminating runReplay due to", e);
            throw e;
        } finally {
            trafficToHttpTransactionAccumulator.close();
            var PRIMARY_LOG_LEVEL = Level.INFO;
            var SECONDARY_LOG_LEVEL = Level.WARN;
            var logLevel = PRIMARY_LOG_LEVEL;
            for (var timeout = Duration.ofSeconds(60); ; timeout = timeout.multipliedBy(2)) {
                try {
                    waitForRemainingWork(logLevel, timeout, replayEngine);
                    break;
                } catch (TimeoutException e) {
                    log.atLevel(logLevel).log("Timed out while waiting for the remaining " +
                            "requests to be finalized...");
                    logLevel = SECONDARY_LOG_LEVEL;
                }
            }
            if (exceptionCount.get() > 0) {
                log.warn(exceptionCount.get() + " requests to the target threw an exception; " +
                        successCount.get() + " requests were successfully processed.");
            } else {
                log.info(successCount.get() + " requests were successfully processed.");
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
            assert requestToFinalWorkFuturesMap.size() == 0 :
                    "expected to wait for all of the in flight requests to fully flush and self destruct themselves";
        }
    }

    ConcurrentHashMap<UniqueRequestKey, Boolean> liveRequests = new ConcurrentHashMap<>();

    private BiConsumer<UniqueRequestKey, HttpMessageAndTimestamp>
    getRecordedRequestReconstructCompleteHandler(ReplayEngine replayEngine) {
        return (requestKey, request) -> {
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
        };
    }

    private final static Instant startTime = Instant.now();

    private Consumer<RequestResponsePacketPair>
    getRecordedRequestAndResponseReconstructCompleteHandler(SourceTargetCaptureTuple.TupleToFileWriter tupleWriter) {
        return rrPair -> {
            log.atTrace().setMessage(()->"Done receiving captured stream for this " + rrPair.requestData).log();
            var resultantCf = requestFutureMap.remove(rrPair.requestKey)
                    .map(f ->
                            f.handle((summary, t) -> {
                                TransformedTargetRequestAndResponse rval = null;
                                try {
                                    rval = packageAndWriteResponse(tupleWriter, rrPair, summary, t);
                                    successCount.incrementAndGet();
                                    return rval;
                                } catch (Exception e) {
                                    log.atInfo().setCause(e).setMessage("Exception for request {}: ")
                                            .addArgument(rrPair.requestKey).log();
                                    exceptionCount.incrementAndGet();
                                    throw e;
                                } finally {
                                    requestToFinalWorkFuturesMap.remove(rrPair.requestKey);
                                    log.trace("removed rrPair.requestData to " +
                                            "targetTransactionInProgressMap for " +
                                            rrPair.requestKey);
                                }
                            }), () -> "TrafficReplayer.runReplayWithIOStreams.progressTracker");
            if (!resultantCf.future.isDone()) {
                log.trace("Adding " + rrPair.requestKey + " to targetTransactionInProgressMap");
                requestToFinalWorkFuturesMap.put(rrPair.requestKey, resultantCf);
                if (resultantCf.future.isDone()) {
                    requestToFinalWorkFuturesMap.remove(rrPair.requestKey);
                }
            }
        };
    }

    private void waitForRemainingWork(Level logLevel,
                                      @NonNull Duration timeout,
                                      ReplayEngine replayEngine)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map.Entry<UniqueRequestKey, DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>>[]
                allRemainingWorkArray = requestToFinalWorkFuturesMap.entrySet().toArray(Map.Entry[]::new);
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

        // remember, this block is ONLY for the leftover items.  Lots of other items have been processed
        // and were removed from the live map (hopefully)
        DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>[] allCompletableFuturesArray =
                Arrays.stream(allRemainingWorkArray)
                        .map(kvp->kvp.getValue()).toArray(DiagnosticTrackableCompletableFuture[]::new);
        var allWorkFuture = StringTrackableCompletableFuture.allOf(allCompletableFuturesArray,
                () -> "TrafficReplayer.AllWorkFinished");
        try {
            allWorkFuture.get(timeout);
        } catch (TimeoutException e) {
            var didCancel = allWorkFuture.future.cancel(true);
            if (!didCancel) {
                assert allWorkFuture.future.isDone() : "expected future to have finished if cancel didn't succeed";
                // continue with the rest of the function
            } else {
                throw e;
            }
        }
        allWorkFuture.getDeferredFutureThroughHandle((t, v) -> {
                    log.info("stopping packetHandlerFactory's group");
                    replayEngine.close();
                    // squash exceptions for individual requests
                    return StringTrackableCompletableFuture.completedFuture(null, () -> "finished all work");
                }, () -> "TrafficReplayer.PacketHandlerFactory->stopGroup")
                .get(); // allWorkFuture already completed - here we're just going to wait for the
                        // rest of the cleanup to finish, as per the name of the function
    }

    private static String formatWorkItem(DiagnosticTrackableCompletableFuture<String,?> cf) {
        try {
            var resultValue = cf.get();
            if (resultValue instanceof TransformedTargetRequestAndResponse) {
                return "" + ((TransformedTargetRequestAndResponse) resultValue).getTransformationStatus();
            }
            return null;
        } catch (ExecutionException | InterruptedException e) {
            return e.getMessage();
        }
    }

    private static TransformedTargetRequestAndResponse
    packageAndWriteResponse(SourceTargetCaptureTuple.TupleToFileWriter tupleWriter,
                            RequestResponsePacketPair rrPair,
                            TransformedTargetRequestAndResponse summary,
                            Throwable t) {
        log.trace("done sending and finalizing data to the packet handler");

        try (var requestResponseTuple = getSourceTargetCaptureTuple(rrPair, summary, t)) {
            log.atInfo().setMessage(()->"Source/Target Request/Response tuple: " + requestResponseTuple).log();
            tupleWriter.writeJSON(requestResponseTuple);
        } catch (IOException e) {
            log.error("Caught an IOException while writing triples output.");
            e.printStackTrace();
            throw new CompletionException(e);
        }

        if (t != null) { throw new CompletionException(t); }
        if (summary.getError() != null) { throw new CompletionException(summary.getError()); }
        if (summary.getTransformationStatus() == HttpRequestTransformationStatus.ERROR) {
            throw new CompletionException(new RuntimeException("Unknown error transforming the request"));
        }
        return summary;
    }

    private static SourceTargetCaptureTuple getSourceTargetCaptureTuple(RequestResponsePacketPair rrPair,
                                                                        TransformedTargetRequestAndResponse summary,
                                                                        Throwable t) {
        SourceTargetCaptureTuple requestResponseTriple;
        if (t != null) {
            log.error("Got exception in CompletableFuture callback: ", t);
            requestResponseTriple = new SourceTargetCaptureTuple(rrPair,
                    new TransformedPackets(), new ArrayList<>(),
                    HttpRequestTransformationStatus.ERROR, t, Duration.ZERO);
        } else {
            requestResponseTriple = new SourceTargetCaptureTuple(rrPair,
                    summary.requestPackets,
                    summary.getReceiptTimeAndResponsePackets()
                            .map(entry -> entry.getValue()).collect(Collectors.toList()),
                    summary.getTransformationStatus(),
                    summary.getError(),
                    summary.getResponseDuration()
            );
        }
        return requestResponseTriple;
    }

    private DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>
    transformAndSendRequest(ReplayEngine replayEngine, HttpMessageAndTimestamp request, UniqueRequestKey requestKey) {
        return transformAndSendRequest(inputRequestTransformerFactory, replayEngine,
                request.getFirstPacketTimestamp(), request.getLastPacketTimestamp(), requestKey,
                ()->request.packetBytes.stream());
    }

    public static DiagnosticTrackableCompletableFuture<String, TransformedTargetRequestAndResponse>
    transformAndSendRequest(PacketToTransformingHttpHandlerFactory inputRequestTransformerFactory,
                            ReplayEngine replayEngine,
                            Instant start, Instant end,
                            UniqueRequestKey requestKey,
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
            var sendFuture = transformationCompleteFuture.thenCompose(transformedResult ->
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
            return sendFuture;
        } catch (Exception e) {
            log.debug("Caught exception in writeToSocket, so failing future");
            return StringTrackableCompletableFuture.failedFuture(e, ()->"TrafficReplayer.writeToSocketAndClose");
        }
    }

    private static <R> DiagnosticTrackableCompletableFuture<String, R>
    transformAllData(IPacketFinalizingConsumer<R> packetHandler, Supplier<Stream<byte[]>> packetSupplier) {
        try {
            var logLabel = packetHandler.getClass().getSimpleName();
            var packets = packetSupplier.get().map(b-> Unpooled.wrappedBuffer(b));
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

    public void pullReplayFromSourceToAccumulator(
            ITrafficCaptureSource trafficChunkStream,
            CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator) {
        try {
            while (true) {
                log.trace("Reading next chunk from TrafficStream supplier");
                var trafficStreams = trafficChunkStream.readNextTrafficStreamChunk().get();
                if (log.isInfoEnabled()) {
                    Optional.of(trafficStreams.stream()
                                    .map(ts->TrafficStreamUtils.summarizeTrafficStream(ts.getStream()))
                                    .collect(Collectors.joining(";")))
                            .filter(s->!s.isEmpty())
                            .ifPresent(s->log.atInfo().log("TrafficStream Summary: {" + s + "}"));
                }
                trafficStreams.forEach(ts->trafficToHttpTransactionAccumulator.accept(ts));
            }
        } catch (ExecutionException ee) {
            if (ee.getCause() instanceof EOFException) {
                log.atWarn().setCause(ee.getCause()).setMessage("Got an EOF on the stream.  " +
                        "Done reading traffic streams.").log();
            } else {
                throw new RuntimeException(ee.getCause());
            }
        } catch (InterruptedException ex) {
            log.atInfo().setCause(ex).setMessage("Interrupted.  Done reading traffic streams.").log();
        }
    }
}
