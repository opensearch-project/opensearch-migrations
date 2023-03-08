package org.opensearch.migrations.replay;

import com.google.common.io.CharSource;
import com.google.common.primitives.Bytes;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class TrafficReplayer {
    final static int LOG_PREAMBLE_SIZE = 75;
    // current log fmt: [%d{ISO8601}][%-5p][%-25c{1.}] [%node_name]%marker %m%n
    final static Pattern LINE_MATCHER =
            Pattern.compile("^\\[(.*),(.[0-9]*)\\]\\[.*\\]\\[.*\\] \\[(.*)\\] \\[id: 0x([0-9a-f]*), .*\\] " +
                    "(ACTIVE|FLUSH|INACTIVE|READ COMPLETE|REGISTERED|UNREGISTERED|WRITABILITY|((READ|WRITE)( ([0-9]+):(.*))))");
    final static int TIMESTAMP_GROUP        = 1;
    final static int TIMESTAMP_MS_GROUP     = 2;
    final static int CLUSTER_GROUP          = 3;
    final static int CHANNEL_ID_GROUP       = 4;
    final static int SIMPLE_OPERATION_GROUP = 5;
    final static int RW_OPERATION_GROUP     = 7;
    final static int SIZE_GROUP             = 9;
    final static int PAYLOAD_GROUP          = 10;
    public static final String READ_OPERATION     = "READ";
    public static final String WRITE_OPERATION    = "WRITE";
    public static final String FINISHED_OPERATION = "UNREGISTERED";

    private final NettyPacketToHttpHandlerFactory packetHandlerFactory;

    public TrafficReplayer(URI serverUri)
    {
        packetHandlerFactory = new NettyPacketToHttpHandlerFactory(serverUri);
    }

    private static void exitWithUsageAndCode(int statusCode) {
        System.err.println("Usage: <log directory> <destination_url>");
        System.exit(statusCode);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 3) {
            exitWithUsageAndCode(1);
        }

        URI uri;
        try {
            uri = new URI(args[1]);
        } catch (Exception e) {
            exitWithUsageAndCode(2);
            return;
        }
        Path directoryPath;
        try {
            directoryPath = Paths.get(args[0]);
        } catch (Exception e) {
            exitWithUsageAndCode(3);
            return;
        }
        Path outputPath;
        try {
            outputPath = Paths.get(args[2]);
        } catch (Exception e) {
            exitWithUsageAndCode(4);
            return;
        }

        var tr = new TrafficReplayer(uri);
        try (var fileTriplesStream = new FileOutputStream(outputPath.toFile(), true);
             var bufferedTriplesStream = new BufferedOutputStream(fileTriplesStream)) {
            var tripleWriter = new RequestResponseResponseTriple.TripleToFileWriter(bufferedTriplesStream);
            ReplayEngine replayEngine = new ReplayEngine(rp -> tr.writeToSocketAndClose(rp,
                    triple -> {
                        try {
                            tripleWriter.writeJSON(triple);
                        } catch (IOException e) {
                            System.err.println("Caught an IOException while writing triples to file.");
                            e.printStackTrace();
                        }
                    }
            )
            );
            tr.runReplay(directoryPath, replayEngine);
        }
    }

    private static String aggregateByteStreamToString(Stream<byte[]> bStream) {
        return new String(Bytes.concat(bStream.toArray(byte[][]::new)), Charset.defaultCharset());
    }

    static int nReceived = 0;
    private void writeToSocketAndClose(RequestResponsePacketPair requestResponsePacketPair,
                                       Consumer<RequestResponseResponseTriple> onResponseReceivedCallback) {
        try {
            log("Assembled request/response - starting to write to socket");
            var packetHandler = packetHandlerFactory.create();
            for (var packetData : requestResponsePacketPair.requestData) {
                packetHandler.consumeBytes(packetData);
            }
            AtomicBoolean waiter = new AtomicBoolean(true);
            packetHandler.finalizeRequest(summary-> {
                System.err.println("Summary(#"+(nReceived++)+")="+summary);
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

    private void log(String s) {
        System.err.println(s);
    }

    public void runReplay(Path directory, ReplayEngine replayEngine) throws IOException, InterruptedException {
        try {
            try (var dirStream = Files.newDirectoryStream(directory)) {
                var sortedDirStream = StreamSupport.stream(dirStream.spliterator(), false)
                        .sorted(Comparator.comparing(Path::toString));
                sortedDirStream.forEach(filePath -> {
                    try {
                        consumeLogLinesForFile(filePath, replayEngine);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } finally {
            packetHandlerFactory.stopGroup();
        }
    }

    private enum Operation {
        Read, Write, Close
    }

    public static class ReplayEntry {
        public final String id;
        public final Operation operation;
        public final byte[] data;
        public final Instant timestamp;

        public ReplayEntry(String id, String timestampStr, Operation operation, byte[] data) {
            this.id = id;
            this.operation = operation;
            this.timestamp = Instant.parse(timestampStr);
            this.data = data;
        }
    }

    public static class RequestResponsePacketPair {
        final Instant firstTimeStamp;

        final ArrayList<byte[]> requestData;
        final ArrayList<byte[]> responseData;

        private Instant lastTimeStamp;

        private Operation lastOperation;

        public Duration getTotalDuration() {
            return Duration.between(firstTimeStamp, lastTimeStamp);
        }

        public RequestResponsePacketPair(Instant requestStartTime) {
            this.requestData = new ArrayList<>();
            this.responseData = new ArrayList<>();
            this.lastOperation = Operation.Close;
            this.firstTimeStamp = requestStartTime;
        }

        public Operation getLastOperation() {
            return lastOperation;
        }

        public void addRequestData(Instant packetTimeStamp, byte[] data) {
            requestData.add(data);
            this.lastOperation = Operation.Read;
        }
        public void addResponseData(Instant packetTimeStamp, byte[] data) {
            responseData.add(data);
            lastTimeStamp = packetTimeStamp;
            this.lastOperation = Operation.Write;
        }

        public Stream<byte[]> getRequestDataStream() {
            return requestData.stream();
        }

        public Stream<byte[]> getResponseDataStream() {
            return responseData.stream();
        }
    }

    public static class ReplayEngine implements Consumer<ReplayEntry> {
        private final Map<String, RequestResponsePacketPair> liveStreams;
        private final Consumer<RequestResponsePacketPair> fullDataHandler;

        public ReplayEngine(Consumer<RequestResponsePacketPair> fullDataHandler) {
            liveStreams = new HashMap<>();
            this.fullDataHandler = fullDataHandler;
        }

        @Override
        public void accept(ReplayEntry e) {
            if (e.operation.equals(Operation.Close)) {
                publishAndClear(e.id);
            } else if (e.operation.equals(Operation.Write)) {
                var priorBuffers = liveStreams.get(e.id);
                if (priorBuffers == null) {
                    throw new RuntimeException("Apparent out of order exception - " +
                            "found a purported write to a socket before a read!");
                }
                priorBuffers.addResponseData(e.timestamp, e.data);
            } else if (e.operation.equals(Operation.Read)) {
                var runningList =
                        liveStreams.putIfAbsent(e.id, new RequestResponsePacketPair(e.timestamp));
                if (runningList==null) {
                    runningList = liveStreams.get(e.id);
                } else if (runningList.lastOperation == Operation.Write) {
                    publishAndClear(e.id);
                    accept(e);
                    return;
                }
                runningList.addRequestData(e.timestamp, e.data);
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

    public void consumeLogLinesForFile(Path p, Consumer<ReplayEntry> replayEntryConsumer) throws IOException {
        try (FileInputStream fis = new FileInputStream(p.toString())) {
            try (InputStreamReader r = new InputStreamReader(fis)) {
                try (BufferedReader br = new BufferedReader(r)) {
                    consumeLinesForReader(br, replayEntryConsumer);
                }
            }
        }
    }

    // as per https://github.com/google/guava/issues/5376.  Yuck
    public static InputStream readerToStream(Reader reader, Charset charset) throws IOException {
        return new CharSource() {
            @Override
            public Reader openStream() {
                return reader;
            }
        }.asByteSource(charset).openStream();
    }

    public void consumeLinesForReader(BufferedReader lineReader, Consumer<ReplayEntry> replayEntryConsumer) throws IOException {
        while (true) {
            String line = lineReader.readLine();
            if (line == null) { break; }
            var m = LINE_MATCHER.matcher(line);
            if (!m.matches()) {
                continue;
            }

            String uniqueConnectionKey = m.group(CLUSTER_GROUP) + "," + m.group(CHANNEL_ID_GROUP);
            String simpleOperation = m.group(SIMPLE_OPERATION_GROUP);
            String timestampStr = m.group(TIMESTAMP_GROUP) + "." + m.group(TIMESTAMP_MS_GROUP) + "Z";

            if (simpleOperation != null) {
                var rwOperation = m.group(RW_OPERATION_GROUP);
                int packetDataSize = Optional.ofNullable(m.group(SIZE_GROUP)).map(s->Integer.parseInt(s)).orElse(0);
                System.err.println("read op="+rwOperation+" size="+packetDataSize);
                if (rwOperation != null) {
                    var packetData = Base64.getDecoder().decode(m.group(10));
                    if (rwOperation.equals(READ_OPERATION)) {
                        replayEntryConsumer.accept(new ReplayEntry(uniqueConnectionKey, timestampStr, Operation.Read, packetData));
                    } else if (rwOperation.equals(WRITE_OPERATION)) {
                        replayEntryConsumer.accept(new ReplayEntry(uniqueConnectionKey, timestampStr, Operation.Write, packetData));
                    }
                } else {
                    System.err.println("read op="+simpleOperation);
                    if (simpleOperation.equals(FINISHED_OPERATION)) {
                        replayEntryConsumer.accept(new ReplayEntry(uniqueConnectionKey, timestampStr, Operation.Close, null));
                    }
                }
            }
        }
    }
}
