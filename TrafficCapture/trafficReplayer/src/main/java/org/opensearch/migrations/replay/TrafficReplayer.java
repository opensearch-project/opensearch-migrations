package org.opensearch.migrations.replay;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import lombok.extern.log4j.Log4j2;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.transform.CompositeJsonTransformer;
import org.opensearch.migrations.transform.JoltJsonTransformer;
import org.opensearch.migrations.transform.JsonTransformer;
import org.opensearch.migrations.transform.TypeMappingJsonTransformer;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Log4j2
public class TrafficReplayer {

    private final PacketToTransformingProxyHandlerFactory packetHandlerFactory;
    private Duration timeout = Duration.ofSeconds(20);

    public static JsonTransformer buildDefaultJsonTransformer(String newHostName) {
        var joltJsonTransformer = JoltJsonTransformer.newBuilder()
                .addHostSwitchOperation(newHostName)
                .build();
        return new CompositeJsonTransformer(joltJsonTransformer, new TypeMappingJsonTransformer());
    }

    public TrafficReplayer(URI serverUri) {
        if (serverUri.getPort() < 0) {
            throw new RuntimeException("Port not present for URI: "+serverUri);
        }
        if (serverUri.getHost() == null) {
            throw new RuntimeException("Hostname not present for URI: "+serverUri);
        }
        if (serverUri.getScheme() == null) {
            throw new RuntimeException("Scheme (http|https) is not present for URI: "+serverUri);
        }
        var jsonTransformer = buildDefaultJsonTransformer(serverUri.getHost());
        packetHandlerFactory = new PacketToTransformingProxyHandlerFactory(serverUri, jsonTransformer);
    }


    static class Parameters {
        @Parameter(required = true,
                arity = 1,
                description = "URI of the target cluster/domain")
        String targetUriString;
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
            return null;
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var params = parseArgs(args);
        URI uri;
        System.err.println("Starting Traffic Replayer");
        try {
            uri = new URI(params.targetUriString);
        } catch (Exception e) {
            System.err.println("Exception parsing "+params.targetUriString);
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        var tr = new TrafficReplayer(uri);
        try (OutputStream outputStream = params.outputFilename == null ? System.out :
                new FileOutputStream(params.outputFilename, true)) {
            try (var bufferedOutputStream = new BufferedOutputStream(outputStream)) {
                try (var closeableStream = CloseableTrafficStreamWrapper.getLogEntriesFromFileOrStdin(params.inputFilename)) {
                    runReplayWithIOStreams(tr, closeableStream.stream(), bufferedOutputStream);
                }
            }
        }
    }

    private static void runReplayWithIOStreams(TrafficReplayer tr, Stream<TrafficStream> trafficChunkStream,
                                               BufferedOutputStream bufferedOutputStream)
            throws IOException, InterruptedException {
        var tripleWriter = new SourceTargetCaptureTuple.TupleToFileWriter(bufferedOutputStream);
        WeakHashMap<HttpMessageAndTimestamp, CompletableFuture<AggregatedRawResponse>> requestFutureMap =
                new WeakHashMap<>();
        ReplayEngine replayEngine = new ReplayEngine(request ->
                requestFutureMap.put(request, tr.writeToSocketAndClose(request)),
                rrPair -> {
                    requestFutureMap.get(rrPair.requestData)
                            .whenComplete((summary, t) -> {
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
                                }
                            });
                }
        );
        tr.runReplay(trafficChunkStream, replayEngine);
    }

    private CompletableFuture writeToSocketAndClose(HttpMessageAndTimestamp request)
    {
        try {
            log.debug("Assembled request/response - starting to write to socket");
            var packetHandler = packetHandlerFactory.create();
            for (var packetData : request.packetBytes) {
                packetHandler.consumeBytes(packetData);
            }
            return packetHandler.finalizeRequest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void runReplay(Stream<TrafficStream> trafficChunkStream, ReplayEngine replayEngine) throws IOException, InterruptedException {
        try {
            trafficChunkStream
                    .forEach(ts->ts.getSubStreamList().stream()
                            .forEach(o->replayEngine.accept(ts.getId(), o)));
        } finally {
            packetHandlerFactory.stopGroup();
        }
    }

}
