package org.opensearch.migrations.replay;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.io.CharSource;
import com.google.common.primitives.Bytes;
import lombok.extern.log4j.Log4j2;
import org.opensearch.migrations.trafficcapture.protos.Read;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import javax.annotation.Nullable;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Log4j2
public class TrafficReplayer {

    private final NettyPacketToHttpHandlerFactory packetHandlerFactory;

    public TrafficReplayer(URI serverUri)
    {
        packetHandlerFactory = new NettyPacketToHttpHandlerFactory(serverUri);
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

    public static CloseableTrafficStreamWrapper getLogEntriesFromInputStream(InputStream is) throws IOException {
        return CloseableTrafficStreamWrapper.generateTrafficStreamFromInputStream(is);
    }

    public static CloseableTrafficStreamWrapper getLogEntriesFromFile(String filename) throws IOException {
        FileInputStream fis = new FileInputStream(filename);
        try {
            return getLogEntriesFromInputStream(fis);
        } catch (Exception e) {
            fis.close();
            throw e;
        }
    }

    public static CloseableTrafficStreamWrapper getLogEntriesFromFileOrStdin(String filename) throws IOException {
        return filename == null ? getLogEntriesFromInputStream(System.in) :
                getLogEntriesFromFile(filename);
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
                try (var closeableStream = getLogEntriesFromFileOrStdin(params.inputFilename)) {
                    runReplayWithIOStreams(tr, closeableStream.stream(), bufferedOutputStream);
                }
            }
        }
    }

    private static void runReplayWithIOStreams(TrafficReplayer tr, Stream<TrafficStream> trafficChunkStream,
                                               BufferedOutputStream bufferedOutputStream)
            throws IOException, InterruptedException {
        var tripleWriter = new RequestResponseResponseTriple.TripleToFileWriter(bufferedOutputStream);
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

    private static String aggregateByteStreamToString(Stream<byte[]> bStream) {
        return new String(Bytes.concat(bStream.toArray(byte[][]::new)), Charset.defaultCharset());
    }

    static int nReceived = 0;
    private void writeToSocketAndClose(RequestResponsePacketPair requestResponsePacketPair,
                                       Consumer<RequestResponseResponseTriple> onResponseReceivedCallback) {
        try {
            log.debug("Assembled request/response - starting to write to socket");
            var packetHandler = packetHandlerFactory.create();
            for (var packetData : requestResponsePacketPair.requestData) {
                packetHandler.consumeBytes(packetData);
            }
            AtomicBoolean waiter = new AtomicBoolean(true);
            packetHandler.finalizeRequest(summary-> {
                log.info("Summary(#"+(nReceived++)+")="+summary);
                RequestResponseResponseTriple requestResponseTriple = new RequestResponseResponseTriple(requestResponsePacketPair,
                        summary.getReceiptTimeAndResponsePackets().map(entry -> entry.getValue()).collect(Collectors.toList()),
                        summary.getResponseDuration()
                );

                onResponseReceivedCallback.accept(requestResponseTriple);

                synchronized (waiter) {
                    waiter.set(false);
                    waiter.notify();
                }
            });
            synchronized (waiter) {
                if (waiter.get()) {
                    waiter.wait(20 * 1000);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (IPacketToHttpHandler.InvalidHttpStateException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
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

    public static class RequestResponsePacketPair {
        Instant firstTimeStampForRequest;
        Instant lastTimeStampForRequest;
        Instant firstTimeStampForResponse;
        Instant lastTimeStampForResponse;

        final ArrayList<byte[]> requestData;
        final ArrayList<byte[]> responseData;

        public Duration getTotalRequestDuration() {
            return Duration.between(firstTimeStampForRequest, lastTimeStampForRequest);
        }
        public Duration getTotalResponseDuration() {
            return Duration.between(firstTimeStampForResponse, lastTimeStampForResponse);
        }

        public RequestResponsePacketPair() {
            this.requestData = new ArrayList<>();
            this.responseData = new ArrayList<>();
        }

        public void addRequestData(Instant packetTimeStamp, byte[] data) {
            log.trace(()->(this+" Adding request data: "+new String(data, Charset.defaultCharset())));
            requestData.add(data);
            if (firstTimeStampForRequest == null) {
                firstTimeStampForRequest = packetTimeStamp;
            }
            lastTimeStampForRequest = packetTimeStamp;
        }
        public void addResponseData(Instant packetTimeStamp, byte[] data) {
            log.trace(()->(this+" Adding response data (len="+responseData.size()+"): "+
                    new String(data, Charset.defaultCharset())));
            responseData.add(data);
            lastTimeStampForResponse = packetTimeStamp;
        }

        public Stream<byte[]> getRequestDataStream() {
            return requestData.stream();
        }

        public Stream<byte[]> getResponseDataStream() {
            return responseData.stream();
        }
    }

    public static class ReplayEngine implements BiConsumer<String, TrafficObservation> {
        private final Map<String, RequestResponsePacketPair> liveStreams;
        private final Consumer<RequestResponsePacketPair> fullDataHandler;

        public ReplayEngine(Consumer<RequestResponsePacketPair> fullDataHandler) {
            liveStreams = new HashMap<>();
            this.fullDataHandler = fullDataHandler;
        }

        @Override
        public void accept(String id, TrafficObservation observation) {
            boolean updateFirstResponseTimestamp = false;
            RequestResponsePacketPair runningList = null;
            var pbts = observation.getTs();
            var timestamp = Instant.ofEpochSecond(pbts.getSeconds(), pbts.getNanos());
            if (observation.hasEndOfMessageIndicator()) {
                publishAndClear(id);
            } else if (observation.hasRead()) {
                runningList =
                        liveStreams.putIfAbsent(id, new RequestResponsePacketPair());
                if (runningList==null) {
                    runningList = liveStreams.get(id);
                    // TODO - eliminate the byte[] and use the underlying nio buffer
                    runningList.addRequestData(timestamp, observation.getRead().getData().toByteArray());
                }
            } else if (observation.hasReadSegment()) {
                throw new RuntimeException("Not implemented yet.");
            } else if (observation.hasWrite()) {
                updateFirstResponseTimestamp = true;
                runningList = liveStreams.get(id);
                if (runningList == null) {
                    throw new RuntimeException("Apparent out of order exception - " +
                            "found a purported write to a socket before a read!");
                }
                runningList.addResponseData(timestamp, observation.getWrite().toByteArray());
            } else if (observation.hasWriteSegment()) {
                updateFirstResponseTimestamp = true;
                throw new RuntimeException("Not implemented yet.");
            } else if (observation.hasRequestReleasedDownstream()) {
                updateFirstResponseTimestamp = true;
            }
            if (updateFirstResponseTimestamp && runningList != null) {
                if (runningList.firstTimeStampForResponse == null) {
                    runningList.firstTimeStampForResponse = timestamp;
                }
            }
        }

        private void publishAndClear(String id) {
            var priorBuffers = liveStreams.get(id);
            if (priorBuffers != null) {
                fullDataHandler.accept(priorBuffers);
                liveStreams.remove(id);
            }
        }
    }
}
