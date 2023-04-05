package org.opensearch.migrations.replay;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.io.CharSource;
import com.google.common.primitives.Bytes;
import lombok.extern.log4j.Log4j2;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Log4j2
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

    public static CloseableStringStreamWrapper getLogLineStreamFromInputStream(InputStream is) throws IOException {
        InputStreamReader isr = new InputStreamReader(is, Charset.forName("UTF-8"));
        try {
            BufferedReader br = new BufferedReader(isr);
            return CloseableStringStreamWrapper.generateStreamFromBufferedReader(br);
        } catch (Exception e) {
            isr.close();
            throw e;
        }
    }

    public static CloseableStringStreamWrapper getLogLineStreamFromFile(String filename) throws IOException {
        FileInputStream fis = new FileInputStream(filename);
        try {
            return getLogLineStreamFromInputStream(fis);
        } catch (Exception e) {
            fis.close();
            throw e;
        }
    }

    public static CloseableStringStreamWrapper getLogLineStreamFromFileOrStdin(String filename) throws IOException {
        return filename == null ? getLogLineStreamFromInputStream(System.in) :
                getLogLineStreamFromFile(filename);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var params = parseArgs(args);

        URI uri;
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
                try (CloseableStringStreamWrapper css = getLogLineStreamFromFileOrStdin(params.inputFilename)) {
                    runReplayWithIOStreams(tr, css, bufferedOutputStream);
                }
            }
        }
    }

    private static void runReplayWithIOStreams(TrafficReplayer tr, CloseableStringStreamWrapper stringInputSequence,
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
        tr.runReplay(stringInputSequence.stream(), replayEngine);
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

    public void runReplay(Stream<String> logLinesStream, ReplayEngine replayEngine) throws IOException, InterruptedException {
        try {
            logLinesStream.forEach(s->parseAndConsumeLine(replayEngine, s));
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
            log.trace(()->(this+" Adding request data: "+new String(data, Charset.defaultCharset())));
            requestData.add(data);
            this.lastOperation = Operation.Read;
        }
        public void addResponseData(Instant packetTimeStamp, byte[] data) {
            log.trace(()->(this+" Adding response data (len="+responseData.size()+"): "+
                    new String(data, Charset.defaultCharset())));
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

    // as per https://github.com/google/guava/issues/5376.  Yuck
    public static InputStream readerToStream(Reader reader, Charset charset) throws IOException {
        return new CharSource() {
            @Override
            public Reader openStream() {
                return reader;
            }
        }.asByteSource(charset).openStream();
    }

    private void parseAndConsumeLine(Consumer<ReplayEntry> replayEntryConsumer, String line) {
        var m = LINE_MATCHER.matcher(line);
        if (!m.matches()) {
            return;
        }

        String uniqueConnectionKey = m.group(CLUSTER_GROUP) + "," + m.group(CHANNEL_ID_GROUP);
        String simpleOperation = m.group(SIMPLE_OPERATION_GROUP);
        String timestampStr = m.group(TIMESTAMP_GROUP) + "." + m.group(TIMESTAMP_MS_GROUP) + "Z";

        if (simpleOperation != null) {
            var rwOperation = m.group(RW_OPERATION_GROUP);
            int packetDataSize = Optional.ofNullable(m.group(SIZE_GROUP)).map(s->Integer.parseInt(s)).orElse(0);
            log.trace("read op="+rwOperation+" size="+packetDataSize);
            if (rwOperation != null) {
                var packetData = Base64.getDecoder().decode(m.group(10));
                log.trace(()->"line="+line);
                log.trace(()->"packetData="+new String(packetData, Charset.defaultCharset()));
                if (rwOperation.equals(READ_OPERATION)) {
                    replayEntryConsumer.accept(new ReplayEntry(uniqueConnectionKey, timestampStr, Operation.Read, packetData));
                } else if (rwOperation.equals(WRITE_OPERATION)) {
                    replayEntryConsumer.accept(new ReplayEntry(uniqueConnectionKey, timestampStr, Operation.Write, packetData));
                }
            } else {
                log.trace("read op="+simpleOperation);
                if (simpleOperation.equals(FINISHED_OPERATION)) {
                    replayEntryConsumer.accept(new ReplayEntry(uniqueConnectionKey, timestampStr, Operation.Close, null));
                }
            }
        }
    }
}
