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
import org.opensearch.migrations.transform.CompositeJsonTransformer;
import org.opensearch.migrations.transform.JoltJsonTransformer;
import org.opensearch.migrations.transform.JsonTransformer;
import org.opensearch.migrations.transform.TypeMappingJsonTransformer;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class TrafficReplayer {

    private final PacketToTransformingHttpHandlerFactory packetHandlerFactory;

    public static JsonTransformer buildDefaultJsonTransformer(String newHostName,
                                                              String authorizationHeader) {
        var joltJsonTransformerBuilder = JoltJsonTransformer.newBuilder()
                .addHostSwitchOperation(newHostName);
        if (authorizationHeader != null) {
            joltJsonTransformerBuilder = joltJsonTransformerBuilder.addAuthorizationOperation(authorizationHeader);
        }
        var joltJsonTransformer = joltJsonTransformerBuilder.build();
        return new CompositeJsonTransformer(joltJsonTransformer, new TypeMappingJsonTransformer());
    }

    public TrafficReplayer(URI serverUri, String authorizationHeader, boolean allowInsecureConnections)
            throws SSLException {
        this(serverUri, authorizationHeader, allowInsecureConnections, 
                buildDefaultJsonTransformer(serverUri.getHost(), authorizationHeader));
    }
    
    public TrafficReplayer(URI serverUri, String authorizationHeader, boolean allowInsecureConnections,
                           JsonTransformer jsonTransformer)
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
        packetHandlerFactory = new PacketToTransformingHttpHandlerFactory(serverUri, jsonTransformer,
                loadSslContext(serverUri, allowInsecureConnections));
    }

    private static SslContext loadSslContext(URI serverUri, boolean allowInsecureConnections) throws SSLException {
        if (serverUri.getScheme().toLowerCase().equals("https")) {
            var sslContextBuilder = SslContextBuilder.forClient();
            if (allowInsecureConnections) {
                sslContextBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            }
            return sslContextBuilder.build();
        } else {
            return null;
        }
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
                names = {"--auth-header-value"},
                arity = 1,
                description = "Value to use for the \"authorization\" header of each request")
        String authHeaderValue;
        @Parameter(required = false,
                names = {"-o", "--output"},
                arity=1,
                description = "output file to hold the request/response traces for the source and target cluster")
        String outputFilename;
        @Parameter(required = false,
                names = {"-i", "--input"},
                arity=1,
                description = "input file to read the request/response traces for the source cluster")
        String inputFilename;
        @Parameter(required = false,
                names = {"-t", "--packet-timeout-seconds"},
                arity = 1,
                description = "assume that connections were terminated after this many " +
                        "seconds of inactivity observed in the captured stream")
        int observedPacketConnectionTimeout = 30;
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

        var tr = new TrafficReplayer(uri, params.authHeaderValue, params.allowInsecureConnections);
        try (OutputStream outputStream = params.outputFilename == null ? System.out :
                new FileOutputStream(params.outputFilename, true)) {
            try (var bufferedOutputStream = new BufferedOutputStream(outputStream)) {
                try (var closeableStream = CloseableTrafficStreamWrapper.getLogEntriesFromFileOrStdin(params.inputFilename)) {
                    tr.runReplayWithIOStreams(Duration.ofSeconds(params.observedPacketConnectionTimeout),
                            closeableStream.stream(), bufferedOutputStream);
                    log.info("reached the end of the ingestion output stream");
                }
            }
        }
    }

    private void runReplayWithIOStreams(Duration observedPacketConnectionTimeout,
                                        Stream<TrafficStream> trafficChunkStream,
                                        BufferedOutputStream bufferedOutputStream)
            throws IOException, InterruptedException, ExecutionException {
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger exceptionCount = new AtomicInteger();
        var tupleWriter = new SourceTargetCaptureTuple.TupleToFileWriter(bufferedOutputStream);
        ConcurrentHashMap<HttpMessageAndTimestamp, DiagnosticTrackableCompletableFuture<String,AggregatedTransformedResponse>> requestFutureMap =
                new ConcurrentHashMap<>();
        ConcurrentHashMap<HttpMessageAndTimestamp, DiagnosticTrackableCompletableFuture<String,AggregatedTransformedResponse>>
                requestToFinalWorkFuturesMap = new ConcurrentHashMap<>();
        CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator =
                new CapturedTrafficToHttpTransactionAccumulator(observedPacketConnectionTimeout,
                        (connId,request) -> {
                    var requestPushFuture = writeToSocketAndClose(request, connId.toString());
                            requestFutureMap.put(request, requestPushFuture);
                            try {
                                var rval = requestPushFuture.get();
                                log.error("value returned="+rval);
                            } catch (ExecutionException e) {
                                log.warn("Got an ExecutionException: ", e);
                                // eating this exception is the RIGHT thing to do here!  Future invocations
                                // of get() or chained invocations will continue to expose this exception, which
                                // is where/how the exception should be handled.
                            } catch (InterruptedException e) {
                                log.warn("Got an interrupted exception while waiting for a request to be handled.  " +
                                        "Assuming that this request should silently fail and that the " +
                                        "calling context has more awareness than we do here.");
                            }
                        },
                        rrPair -> {
                            if (log.isTraceEnabled()) {
                                log.trace("Done receiving captured stream for this "+rrPair.requestData);
                            }
                            DiagnosticTrackableCompletableFuture<String,AggregatedTransformedResponse> resultantCf =
                                    requestFutureMap.get(rrPair.requestData)
                                            .map(f->
                                                    f.handle((summary, t) -> {
                                                        try {
                                                            AggregatedTransformedResponse rval =
                                                                    packageAndWriteResponse(tupleWriter, rrPair, summary, t);
                                                            successCount.incrementAndGet();
                                                            return rval;
                                                        } catch (Exception e) {
                                                            exceptionCount.incrementAndGet();
                                                            throw e;
                                                        } finally {
                                                            requestToFinalWorkFuturesMap.remove(rrPair.requestData);
                                                            log.trace("removed rrPair.requestData to " +
                                                                    "requestToFinalWorkFuturesMap for " +
                                                                    rrPair.connectionId);
                                                        }
                                                    }), ()->"TrafficReplayer.runReplayWithIOStreams.progressTracker");
                            if (!resultantCf.future.isDone()) {
                                log.error("Adding " + rrPair.connectionId + " to requestToFinalWorkFuturesMap");
                                requestToFinalWorkFuturesMap.put(rrPair.requestData, resultantCf);
                                if (resultantCf.future.isDone()) {
                                    requestToFinalWorkFuturesMap.remove(rrPair.requestData);
                                }
                            }
                        }
        );
        try {
            runReplay(trafficChunkStream, trafficToHttpTransactionAccumulator);
        } catch (Exception e) {
            log.warn("Terminating runReplay due to", e);
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
                    log.info("stopping packetHandlerFactory's group");
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
        log.trace("done sending and finalizing data to the packet handler");
        SourceTargetCaptureTuple requestResponseTriple;
        if (t != null) {
            log.error("Got exception in CompletableFuture callback: ", t);
            requestResponseTriple = new SourceTargetCaptureTuple(rrPair,
                    new ArrayList<>(), new ArrayList<>(),
                    AggregatedTransformedResponse.HttpRequestTransformationStatus.ERROR, null, Duration.ZERO
            );
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
            log.info("Source/Shadow Request/Response tuple: " + requestResponseTriple);
            tripleWriter.writeJSON(requestResponseTriple);
        } catch (IOException e) {
            log.error("Caught an IOException while writing triples output.");
            e.printStackTrace();
            throw new CompletionException(e);
        }

        if (t !=null) { throw new CompletionException(t); }
        return summary;
    }

    private DiagnosticTrackableCompletableFuture<String,AggregatedTransformedResponse>
    writeToSocketAndClose(HttpMessageAndTimestamp request, String diagnosticLabel)
    {
        try {
            log.debug("Assembled request/response - starting to write to socket");
            var packetHandler = packetHandlerFactory.create(diagnosticLabel);
            for (var packetData : request.packetBytes) {
                log.debug("sending "+packetData.length+" bytes to the packetHandler");
                var consumeFuture = packetHandler.consumeBytes(packetData);
                log.debug("consumeFuture = " + consumeFuture);
            }
            log.debug("done sending bytes, now finalizing the request");
            var lastRequestFuture = packetHandler.finalizeRequest();
            log.debug("finalizeRequest future = "  + lastRequestFuture);
            return lastRequestFuture;
        } catch (Exception e) {
            log.debug("Caught exception in writeToSocketAndClose, so failing future");
            return StringTrackableCompletableFuture.failedFuture(e, ()->"TrafficReplayer.writeToSocketAndClose");
        }
    }

    public void runReplay(Stream<TrafficStream> trafficChunkStream,
                          CapturedTrafficToHttpTransactionAccumulator trafficToHttpTransactionAccumulator) {
        trafficChunkStream
                .forEach(ts-> ts.getSubStreamList().stream()
                        .forEach(o ->
                                trafficToHttpTransactionAccumulator.accept(ts.getNodeId(), ts.getConnectionId(), o))
                );
    }
}
