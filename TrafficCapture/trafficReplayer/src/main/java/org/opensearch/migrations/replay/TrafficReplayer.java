package org.opensearch.migrations.replay;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import lombok.extern.log4j.Log4j2;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Log4j2
public class TrafficReplayer {

    private final PacketToTransformingProxyHandlerFactory packetHandlerFactory;
    private Duration timeout = Duration.ofSeconds(20);

    public TrafficReplayer(URI serverUri)
    {
        packetHandlerFactory = new PacketToTransformingProxyHandlerFactory(serverUri);
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
        ReplayEngine replayEngine = new ReplayEngine(rp -> tr.writeToSocketAndClose(rp,
                triple -> {
                    try {
                        tripleWriter.writeJSON(triple);
                    } catch (IOException e) {
                        log.error("Caught an IOException while writing triples output.");
                        e.printStackTrace();
                    }
                }
        )
        );
        tr.runReplay(trafficChunkStream, replayEngine);
    }

    private void writeToSocketAndClose(RequestResponsePacketPair requestResponsePacketPair,
                                       Consumer<SourceTargetCaptureTuple> onResponseReceivedCallback)
    {
        try {
            log.debug("Assembled request/response - starting to write to socket");
            var packetHandler = packetHandlerFactory.create();
            for (var packetData : requestResponsePacketPair.requestData) {
                packetHandler.consumeBytes(packetData);
            }
            var cf = packetHandler.finalizeRequest();
            cf.whenComplete((summary, t) -> {
                SourceTargetCaptureTuple requestResponseTriple;
                if (t != null) {
                    log.error("Got exception in CompletableFuture callback: ", t);
                    requestResponseTriple = new SourceTargetCaptureTuple(requestResponsePacketPair,
                            new ArrayList<>(), Duration.ZERO
                    );
                } else {
                    requestResponseTriple = new SourceTargetCaptureTuple(requestResponsePacketPair,
                            summary.getReceiptTimeAndResponsePackets().map(entry -> entry.getValue()).collect(Collectors.toList()),
                            summary.getResponseDuration()
                    );
                }
                onResponseReceivedCallback.accept(requestResponseTriple);
            });
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
