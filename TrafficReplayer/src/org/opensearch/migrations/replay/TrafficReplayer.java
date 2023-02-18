package org.opensearch.migrations.replay;

import com.google.common.primitives.Bytes;
import org.apache.http.HttpException;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.conn.DefaultHttpResponseParser;
import org.apache.http.impl.io.HttpTransportMetricsImpl;
import org.apache.http.impl.io.SessionInputBufferImpl;
import org.opensearch.migrations.replay.netty.NettyScanningHttpProxy;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class TrafficReplayer {
    final static int LOG_PREAMBLE_SIZE = 75;
    // current log fmt: [%d{ISO8601}][%-5p][%-25c{1.}] [%node_name]%marker %m%n
    final static Pattern LINE_MATCHER =
            Pattern.compile("^\\[(.*),(.[0-9]*)\\]\\[.*\\]\\[.*\\] \\[(.*)\\] \\[id: 0x([0-9a-f]*), .*\\] (([A-Z ]+))( ([0-9]+))?$");
    final static int TIMESTAMP_GROUP     = 1;
    final static int TIMESTAMP_MS_GROUP  = 2;
    final static int CLUSTER_GROUP       = 3;
    final static int CHANNEL_ID_GROUP    = 4;
    final static int OPERATION_GROUP     = 6;
    final static int SIZE_GROUP          = 8;
    public static final String READ_OPERATION     = "READ";
    public static final String WRITE_OPERATION    = "WRITE";
    public static final String FINISHED_OPERATION = "READ COMPLETE";

    private final NettyScanningHttpProxy scanningHttpProxy;

    public TrafficReplayer() {
        scanningHttpProxy = new NettyScanningHttpProxy(25000);
    }

    private static void exitWithUsageAndCode(int statusCode) {
        System.err.println("Usage: <log directory> <destination_url>");
        System.exit(statusCode);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 2) {
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
        var tr = new TrafficReplayer();
        ReplayEngine replayEngine = new ReplayEngine(rp->tr.writeToSocketAndClose(rp));
        tr.runReplay(directoryPath, uri, replayEngine);
    }

    private static String aggregateByteStreamToString(Stream<byte[]> bStream) {
        return new String(Bytes.concat(bStream.toArray(byte[][]::new)), Charset.defaultCharset());
    }

    private void writeToSocketAndClose(RequestResponsePacketPair requestResponsePacketPair) {
        try {
            log("Assembled request/response - starting to write to socket");
            var packetHandler = new NettyPacketToHttpHandler(scanningHttpProxy.getProxyPort());
            for (var packetData : requestResponsePacketPair.requestData) {
                packetHandler.consumeBytes(packetData);
            }
            AtomicBoolean waiter = new AtomicBoolean(true);
            packetHandler.finalizeRequest(summary->{
                System.err.println("Summary="+summary);
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

    public void runReplay(Path directory, URI targetServerUri, ReplayEngine replayEngine) throws IOException, InterruptedException {
        scanningHttpProxy.start(targetServerUri.getHost(), targetServerUri.getPort());
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
            scanningHttpProxy.stop();
        }
    }

    private enum Operation {
        Read, Write, Close
    }

    public static class ReplayEntry {
        public final String id;
        public final Operation operation;
        public final byte[] data;
        public final Date timestamp;

        public ReplayEntry(String id, String timestampStr, Operation operation, byte[] data) {
            this.id = id;
            this.operation = operation;
//          var d = DateTimeFormatter.ISO_LOCAL_DATE_TIME.parse(timestampStr);
//          this.timestamp = Date.from(Instant.from(d));
            this.timestamp = null;
            this.data = data;
        }
    }

    public static class RequestResponsePacketPair {
        final ArrayList<byte[]> requestData;
        final ArrayList<byte[]> responseData;

        private Operation lastOperation;

        public RequestResponsePacketPair() {
            this.requestData = new ArrayList<>();
            this.responseData = new ArrayList<>();
            this.lastOperation = Operation.Close;
        }

        public Operation getLastOperation() {
            return lastOperation;
        }

        public void addRequestData(byte[] data) {
            requestData.add(data);
            this.lastOperation = Operation.Read;
        }
        public void addResponseData(byte[] data) {
            responseData.add(data);
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
                priorBuffers.addResponseData(e.data);
            } else if (e.operation.equals(Operation.Read)) {
                var runningList = liveStreams.putIfAbsent(e.id, new RequestResponsePacketPair());
                if (runningList==null) {
                    runningList = liveStreams.get(e.id);
                } else if (runningList.lastOperation == Operation.Write) {
                    publishAndClear(e.id);
                    accept(e);
                    return;
                }
                runningList.addRequestData(e.data);
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

    public void consumeLinesForReader(BufferedReader lineReader, Consumer<ReplayEntry> replayEntryConsumer) throws IOException {
        while (true) {
            String line = lineReader.readLine();
            if (line == null) { break; }
            var m = LINE_MATCHER.matcher(line);
            System.err.println("line="+line);
            if (!m.matches()) {
                continue;
            }
            String uniqueConnectionKey = m.group(CLUSTER_GROUP) + "," + m.group(CHANNEL_ID_GROUP);
            int charsToRead = Optional.ofNullable(m.group(SIZE_GROUP)).map(s->Integer.parseInt(s)).orElse(0);
            String operation = m.group(OPERATION_GROUP);
            String timestampStr = m.group(TIMESTAMP_GROUP);

            if (operation != null) {
                if (operation.equals(READ_OPERATION)) {
                    var packetData = readPacketData(lineReader, charsToRead);
                    replayEntryConsumer.accept(new ReplayEntry(uniqueConnectionKey, timestampStr, Operation.Read, packetData));
                } else if (operation.equals(WRITE_OPERATION)) {
                    var packetData = readPacketData(lineReader, charsToRead); // advance the log reader
                    replayEntryConsumer.accept(new ReplayEntry(uniqueConnectionKey, timestampStr, Operation.Write, packetData));
                } else if (operation.equals(FINISHED_OPERATION)) {
                    replayEntryConsumer.accept(new ReplayEntry(uniqueConnectionKey, timestampStr, Operation.Close, null));
                }
            }
        }
    }

    private byte[] readPacketData(BufferedReader lineReader, int sizeRemaining) throws IOException {
        if (sizeRemaining == 0) { return new byte[0]; }
        String nextLine = lineReader.readLine();
        var packetData = Base64.getDecoder().decode(nextLine);
        System.err.println("packetData: "+new String(packetData, Charset.defaultCharset()));
        return packetData;
    }
}
