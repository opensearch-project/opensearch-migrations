package org.opensearch.migrations.replay;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.JsonCompositeTransformer;
import org.opensearch.migrations.transform.JsonJoltTransformer;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JsonTypeMappingTransformer;
import org.opensearch.migrations.transform.RemovingAuthTransformerFactory;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.slf4j.event.Level;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import javax.net.ssl.SSLException;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Slf4j
public class TrafficReplayer {

    public static final String SIGV_4_AUTH_HEADER_SERVICE_REGION_ARG = "--sigv4-auth-header-service-region";
    public static final String AUTH_HEADER_VALUE_ARG = "--auth-header-value";
    public static final String REMOVE_AUTH_HEADER_VALUE_ARG = "--remove-auth-header";
    public static final String AWS_AUTH_HEADER_USER_AND_SECRET_ARG = "--auth-header-user-and-secret";
    private final PacketToTransformingHttpHandlerFactory packetHandlerFactory;

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
        this(serverUri, allowInsecureConnections,
                buildDefaultJsonTransformer(serverUri.getHost()),
                authTransformerFactory);
    }
    
    public TrafficReplayer(URI serverUri,
                           boolean allowInsecureConnections,
                           IJsonTransformer jsonTransformer,
                           IAuthTransformerFactory authTransformer)
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
        packetHandlerFactory = new PacketToTransformingHttpHandlerFactory(serverUri, jsonTransformer, authTransformer,
                loadSslContext(serverUri, allowInsecureConnections));
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
                names = {"-t", "--packet-timeout-seconds"},
                arity = 1,
                description = "assume that connections were terminated after this many " +
                        "seconds of inactivity observed in the captured stream")
        int observedPacketConnectionTimeout = 30;

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

        log.atError().log("err1");
        log.atError().setMessage("err2").log();
        log.atError().setCause(new RuntimeException()).setMessage("err3").log();
        log.atError().setCause(new RuntimeException()).setMessage("err4").log();

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

        var tr = new TrafficReplayer(uri, buildAuthTransformerFactory(params), params.allowInsecureConnections);
        try (OutputStream outputStream = params.outputFilename == null ? System.out :
                new FileOutputStream(params.outputFilename, true)) {
            try (var bufferedOutputStream = new BufferedOutputStream(outputStream)) {
                try (var closeableStream = TrafficCaptureSourceFactory.createTrafficCaptureSource(params)) {
                    tr.runReplayWithIOStreams(Duration.ofSeconds(params.observedPacketConnectionTimeout),
                            closeableStream.supplyTrafficFromSource(), bufferedOutputStream);
                    log.atInfo().setMessage(()->"reached the end of the ingestion output stream").log();
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

    private void runReplayWithIOStreams(Duration observedPacketConnectionTimeout,
                                        Stream<TrafficStream> trafficChunkStream,
                                        BufferedOutputStream bufferedOutputStream)
            throws InterruptedException, ExecutionException {
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger exceptionCount = new AtomicInteger();
        var tupleWriter = new SourceTargetCaptureTuple.TupleToFileWriter(bufferedOutputStream);
        ConcurrentHashMap<HttpMessageAndTimestamp, DiagnosticTrackableCompletableFuture<String,AggregatedTransformedResponse>> requestFutureMap =
                new ConcurrentHashMap<>();
        ConcurrentHashMap<HttpMessageAndTimestamp, DiagnosticTrackableCompletableFuture<String,AggregatedTransformedResponse>>
                requestToFinalWorkFuturesMap = new ConcurrentHashMap<>();
        CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator =
                new CapturedTrafficToHttpTransactionAccumulator(observedPacketConnectionTimeout,
                        getRecordedRequestReconstructCompleteHandler(requestFutureMap),
                        getRecordedRequestAndResponseReconstructCompleteHandler(successCount, exceptionCount,
                                tupleWriter, requestFutureMap, requestToFinalWorkFuturesMap)
        );
        try {
            runReplay(trafficChunkStream, trafficToHttpTransactionAccumulator);
        } catch (Exception e) {
            log.atWarn().setCause(e).setMessage(()->"Terminating runReplay due to exception").log();
            throw e;
        } finally {
            trafficToHttpTransactionAccumulator.close();
            var PRIMARY_LOG_LEVEL = Level.INFO;
            var SECONDARY_LOG_LEVEL = Level.WARN;
            var logLevel = PRIMARY_LOG_LEVEL;
            for (var timeout = Duration.ofSeconds(1); ; timeout = timeout.multipliedBy(2)) {
                try {
                    waitForRemainingWork(logLevel, timeout, requestToFinalWorkFuturesMap);
                    break;
                } catch (TimeoutException e) {
                    log.atLevel(logLevel).log("Caught timeout exception while waiting for the remaining " +
                            "requests to be finalized.");
                    logLevel = SECONDARY_LOG_LEVEL;
                }
            }
            if (exceptionCount.get() > 0) {
                log.atWarn().setMessage(()->exceptionCount.get() + " requests to the target threw an exception; " +
                        successCount.get() + " requests were successfully processed.");
            } else {
                log.atInfo().setMessage(()->successCount.get() + " requests were successfully processed.");
            }
            log.atInfo().setMessage(()->String.format(
                    "# of connections created: {}; # of requests on reused keep-alive connections: {}; " +
                            "# of expired connections: {}; # of connections closed: {}; " +
                            "# of connections terminated upon accumulator termination: {}",
                    trafficToHttpTransactionAccumulator.numberOfConnectionsCreated(),
                    trafficToHttpTransactionAccumulator.numberOfRequestsOnReusedConnections(),
                    trafficToHttpTransactionAccumulator.numberOfConnectionsExpired(),
                    trafficToHttpTransactionAccumulator.numberOfConnectionsClosed(),
                    trafficToHttpTransactionAccumulator.numberOfRequestsTerminatedUponAccumulatorClose())
            );
            assert requestToFinalWorkFuturesMap.size() == 0 :
                    "expected to wait for all of the in flight requests to fully flush and self destruct themselves";
        }
    }

    private BiConsumer<UniqueRequestKey, HttpMessageAndTimestamp>
    getRecordedRequestReconstructCompleteHandler(ConcurrentHashMap<HttpMessageAndTimestamp,
            DiagnosticTrackableCompletableFuture<String, AggregatedTransformedResponse>> requestFutureMap) {
        return (connId, request) -> {
            var requestPushFuture = writeToSocketAndClose(request, connId.toString());
            requestFutureMap.put(request, requestPushFuture);
            try {
                var rval = requestPushFuture.get();
                log.atTrace().setMessage(()->"Summary response value for " + connId + " returned=" + rval).log();
            } catch (ExecutionException e) {
                log.atWarn().setCause(e).setMessage(()->"Got an ExecutionException for " + connId).log();
                // eating this exception is the RIGHT thing to do here!  Future invocations
                // of get() or chained invocations will continue to expose this exception, which
                // is where/how the exception should be handled.
            } catch (InterruptedException e) {
                log.atWarn().setMessage(()->"Got an interrupted exception while waiting for a request to be handled.  " +
                        "Assuming that this request should silently fail and that the " +
                        "calling context has more awareness than we do here.").log();
            }
        };
    }

    private static Consumer<RequestResponsePacketPair>
    getRecordedRequestAndResponseReconstructCompleteHandler(
            AtomicInteger successCount,
            AtomicInteger exceptionCount,
            SourceTargetCaptureTuple.TupleToFileWriter tupleWriter,
            ConcurrentHashMap<HttpMessageAndTimestamp, DiagnosticTrackableCompletableFuture<String, AggregatedTransformedResponse>>
                    responseInProgressMap,
            ConcurrentHashMap<HttpMessageAndTimestamp, DiagnosticTrackableCompletableFuture<String, AggregatedTransformedResponse>>
                    targetTransactionInProgressMap) {
        return rrPair -> {
            log.atTrace().setMessage(()->"Done receiving captured stream for this " + rrPair.requestData).log();
            var requestData = rrPair.requestData;
            var resultantCf = responseInProgressMap.remove(requestData)
                    .map(f ->
                            f.handle((summary, t) -> {
                                try {
                                    AggregatedTransformedResponse rval =
                                            packageAndWriteResponse(tupleWriter, rrPair, summary, t);
                                    successCount.incrementAndGet();
                                    return rval;
                                } catch (Exception e) {
                                    log.atInfo().setMessage(()->"base64 gzipped traffic stream: " +
                                            Utils.packetsToCompressedTrafficStream(rrPair.requestData.stream()));
                                    exceptionCount.incrementAndGet();
                                    throw e;
                                } finally {
                                    targetTransactionInProgressMap.remove(rrPair.requestData);
                                    log.atTrace().setMessage(()->"removed rrPair.requestData to " +
                                            "targetTransactionInProgressMap for " +
                                            rrPair.connectionId).log();
                                }
                            }), () -> "TrafficReplayer.runReplayWithIOStreams.progressTracker");
            if (!resultantCf.future.isDone()) {
                log.atError().setMessage(()->"Adding " + rrPair.connectionId + " to targetTransactionInProgressMap").log();
                targetTransactionInProgressMap.put(rrPair.requestData, resultantCf);
                if (resultantCf.future.isDone()) {
                    targetTransactionInProgressMap.remove(rrPair.requestData);
                }
            }
        };
    }

    private void
    waitForRemainingWork(Level logLevel, @NonNull Duration timeout,
                         @NonNull ConcurrentHashMap<HttpMessageAndTimestamp,
                                 DiagnosticTrackableCompletableFuture<String, AggregatedTransformedResponse>>
                                 requestToFinalWorkFuturesMap)
            throws ExecutionException, InterruptedException, TimeoutException {
        var allRemainingWorkArray =
                (DiagnosticTrackableCompletableFuture<String, AggregatedTransformedResponse>[])
                requestToFinalWorkFuturesMap.values().toArray(DiagnosticTrackableCompletableFuture[]::new);
        log.atLevel(logLevel).log("All remaining work to wait on " + allRemainingWorkArray.length + " items: " +
                Arrays.stream(allRemainingWorkArray)
                        .map(dcf->dcf.formatAsString(TrafficReplayer::formatWorkItem))
                        .collect(Collectors.joining("\n")));

        // remember, this block is ONLY for the leftover items.  Lots of other items have been processed
        // and were removed from the live map (hopefully)
        var allWorkFuture = StringTrackableCompletableFuture.allOf(allRemainingWorkArray, () -> "TrafficReplayer.AllWorkFinished");
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
                    log.atInfo().setMessage(()->"stopping packetHandlerFactory's group").log();
                    packetHandlerFactory.stopGroup();
                    // squash exceptions for individual requests
                    return StringTrackableCompletableFuture.completedFuture(null, () -> "finished all work");
                }, () -> "TrafficReplayer.PacketHandlerFactory->stopGroup");
    }

    private static String formatWorkItem(DiagnosticTrackableCompletableFuture<String,?> cf) {
        try {
            var resultValue = cf.get();
            if (resultValue instanceof AggregatedTransformedResponse) {
                return "" + ((AggregatedTransformedResponse) resultValue).getTransformationStatus();
            }
            return null;
        } catch (ExecutionException | InterruptedException e) {
            return e.getMessage();
        }
    }

    private static AggregatedTransformedResponse
    packageAndWriteResponse(SourceTargetCaptureTuple.TupleToFileWriter tripleWriter,
                            RequestResponsePacketPair rrPair,
                            AggregatedTransformedResponse summary,
                            Throwable t) {
        log.atTrace().setMessage(()->"done sending and finalizing data to the packet handler").log();
        SourceTargetCaptureTuple requestResponseTriple;
        if (t != null) {
            log.atError().setCause(t).setMessage(()->"Got exception in CompletableFuture callback").log();
            requestResponseTriple = new SourceTargetCaptureTuple(rrPair,
                    new ArrayList<>(), new ArrayList<>(),
                    AggregatedTransformedResponse.HttpRequestTransformationStatus.ERROR, t, Duration.ZERO);
        } else {
            requestResponseTriple = new SourceTargetCaptureTuple(rrPair,
                    summary.requestPackets,
                    summary.getReceiptTimeAndResponsePackets()
                            .map(entry -> entry.getValue()).collect(Collectors.toList()),
                    summary.getTransformationStatus(),
                    summary.getErrorCause(),
                    summary.getResponseDuration()
            );
        }

        try {
            log.atInfo().setMessage(()->"Source/Shadow Request/Response tuple: " + requestResponseTriple).log();
            tripleWriter.writeJSON(requestResponseTriple);
        } catch (IOException e) {
            log.atError().setMessage(()->"Caught an IOException while writing triples output.").log();
            e.printStackTrace();
            throw new CompletionException(e);
        }

        if (t != null) { throw new CompletionException(t); }
        if (summary.getErrorCause() != null) { throw new CompletionException(summary.getErrorCause()); }
        if (summary.getTransformationStatus() == AggregatedTransformedResponse.HttpRequestTransformationStatus.ERROR) {
            throw new CompletionException(new RuntimeException("Unknown error transforming the request"));
        }
        return summary;
    }

    private DiagnosticTrackableCompletableFuture<String,AggregatedTransformedResponse>
    writeToSocketAndClose(HttpMessageAndTimestamp request, String diagnosticLabel) {
        try {
            log.atDebug().setMessage(()->"Assembled request/response - starting to write to socket").log();
            var packetHandler = packetHandlerFactory.create(diagnosticLabel);
            for (var packetData : request.packetBytes) {
                log.atDebug().setMessage(()->"sending "+packetData.length+" bytes to the packetHandler").log();
                var consumeFuture = packetHandler.consumeBytes(packetData);
                log.atDebug().setMessage(()->"consumeFuture = " + consumeFuture).log();
            }
            log.atDebug().setMessage(()->"done sending bytes, now finalizing the request").log();
            var lastRequestFuture = packetHandler.finalizeRequest();
            log.atDebug().setMessage(()->"finalizeRequest future = "  + lastRequestFuture).log();
            return lastRequestFuture;
        } catch (Exception e) {
            log.atDebug().setMessage(()->"Caught exception in writeToSocketAndClose, so failing future").log();
            return StringTrackableCompletableFuture.failedFuture(e, ()->"TrafficReplayer.writeToSocketAndClose");
        }
    }

    public void runReplay(Stream<TrafficStream> trafficChunkStream,
                          CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator) {
        trafficChunkStream
                .forEach(ts-> trafficToHttpTransactionAccumulator.accept(ts));
    }

}
