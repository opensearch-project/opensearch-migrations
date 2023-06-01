package org.opensearch.migrations.replay;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.extern.log4j.Log4j2;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.transform.CompositeJsonTransformer;
import org.opensearch.migrations.transform.JoltJsonTransformer;
import org.opensearch.migrations.transform.JsonTransformer;
import org.opensearch.migrations.transform.TypeMappingJsonTransformer;

import javax.net.ssl.SSLException;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Log4j2
public class TrafficReplayer {

    private final PacketToTransformingProxyHandlerFactory packetHandlerFactory;
    private Duration timeout = Duration.ofSeconds(20);

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
        packetHandlerFactory = new PacketToTransformingProxyHandlerFactory(serverUri, jsonTransformer,
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
                    tr.runReplayWithIOStreams(closeableStream.stream(), bufferedOutputStream);
                    log.info("beginning to close output stream");
                }
            }
        }
    }

    private void runReplayWithIOStreams(Stream<TrafficStream> trafficChunkStream,
                                        BufferedOutputStream bufferedOutputStream)
            throws IOException, InterruptedException, ExecutionException {
        var tripleWriter = new SourceTargetCaptureTuple.TupleToFileWriter(bufferedOutputStream);
        ConcurrentHashMap<HttpMessageAndTimestamp, CompletableFuture<AggregatedRawResponse>> requestFutureMap =
                new ConcurrentHashMap<>();
        ConcurrentHashMap<HttpMessageAndTimestamp, CompletableFuture<AggregatedRawResponse>>
                requestToFinalWorkFuturesMap = new ConcurrentHashMap<>();
        ReplayEngine replayEngine = new ReplayEngine(
                request -> requestFutureMap.put(request, writeToSocketAndClose(request)),
                rrPair -> {
                    log.warn("Done receiving captured stream for this "+rrPair.requestData);
                    var resultantCf = requestFutureMap.get(rrPair.requestData)
                            .handle((summary, t) -> {
                                log.warn("done sending and finalizing data to the packet handler");
                                SourceTargetCaptureTuple requestResponseTriple;
                                if (t != null) {
                                    log.error("Got exception in CompletableFuture callback: ", t);
                                    requestResponseTriple = new SourceTargetCaptureTuple(rrPair,
                                            new ArrayList<>(), Duration.ZERO
                                    );
                                } else {
                                    requestResponseTriple = new SourceTargetCaptureTuple(rrPair,
                                            summary.getReceiptTimeAndResponsePackets().map(entry -> entry.getValue()).collect(Collectors.toList()),
                                            summary.getResponseDuration()
                                    );
                                }

                                try {
                                    tripleWriter.writeJSON(requestResponseTriple);
                                } catch (IOException e) {
                                    log.error("Caught an IOException while writing triples output.");
                                    e.printStackTrace();
                                    throw new CompletionException(e);
                                }

                                if (t!=null) { throw new CompletionException(t); }
                                return summary;
                            })
                            .whenComplete((v,t) -> requestToFinalWorkFuturesMap.remove(rrPair.requestData));
                    requestToFinalWorkFuturesMap.put(rrPair.requestData, resultantCf);
                }
        );
        try {
            runReplay(trafficChunkStream, replayEngine);
        } finally {
            replayEngine.close();
            var allRemainingWorkArray = requestToFinalWorkFuturesMap.values().toArray(CompletableFuture[]::new);
            log.info("All remaining work to wait on: " +
                    Arrays.stream(allRemainingWorkArray).map(cf->cf.toString()).collect(Collectors.joining()));
            CompletableFuture.allOf(allRemainingWorkArray)
                    .whenComplete((t, v) -> {
                        log.info("stopping packetHandlerFactory's group");
                        packetHandlerFactory.stopGroup();
                    })
                    .get();
            assert requestToFinalWorkFuturesMap.size() == 0 :
                    "expected to wait for all of the in flight requests to fully flush and self destruct themselves";
        }
    }

    private CompletableFuture writeToSocketAndClose(HttpMessageAndTimestamp request)
    {
        try {
            log.debug("Assembled request/response - starting to write to socket");
            var packetHandler = packetHandlerFactory.create();
            for (var packetData : request.packetBytes) {
                log.debug("sending "+packetData.length+" bytes to the packetHandler");
                packetHandler.consumeBytes(packetData);
            }
            log.debug("done sending bytes, now finalizing the request");
            return packetHandler.finalizeRequest();
        } catch (Exception e) {
            log.debug("Caught exception in writeToSocketAndClose, so throwing");
            throw new RuntimeException(e);
        }
    }

    public void runReplay(Stream<TrafficStream> trafficChunkStream, ReplayEngine replayEngine) throws IOException, InterruptedException {
        trafficChunkStream//.filter(ts->"22fce2ab".equals(ts.getId()))
                .forEach(ts->ts.getSubStreamList().stream()
                        .forEach(o->replayEngine.accept(ts.getId(), o)));
    }
}
