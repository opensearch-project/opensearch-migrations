package org.opensearch.migrations.replay;

import org.apache.http.HttpException;
import org.apache.http.impl.conn.DefaultHttpResponseParser;
import org.apache.http.impl.io.HttpTransportMetricsImpl;
import org.apache.http.impl.io.SessionInputBufferImpl;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
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
    public static final String READ_OPERATION = "READ";
    public static final String WRITE_OPERATION = "WRITE";
    public static final String UNREGISTERED_OPERATION = "UNREGISTERED";

    private static void exitWithUsageAndCode(int statusCode) {
        System.err.println("Usage: <log directory> <destination_url>");
        System.exit(statusCode);
    }

    public static void main(String[] args) throws IOException {
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
        ReplayEngine replayEngine = new ReplayEngine(b->
                System.out.println("done gathering"));
                //writeToSocketAndClose(uri, b));
        tr.runReplay(directoryPath, replayEngine);
    }


    public TrafficReplayer() {
    }

    private static void writeToSocketAndClose(URI serverUri, Stream<byte[]> byteArrays) {
        try {
            var socket = new Socket(serverUri.getHost(), serverUri.getPort());
            var socketOutput = socket.getOutputStream();
            var socketInput = socket.getInputStream();
            byteArrays.forEach(b-> {
                try {
                    socketOutput.write(b);
                    socketOutput.flush();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            // pull in the apache http client library to parse through the response so that we can consume
            // it and move on
            SessionInputBufferImpl wrappedResponseStream =
                    new SessionInputBufferImpl(new HttpTransportMetricsImpl(), 1024*1024);
            wrappedResponseStream.bind(socketInput);
            DefaultHttpResponseParser responseParser = new DefaultHttpResponseParser(wrappedResponseStream);
            responseParser.parse();
            socket.close();
        } catch (IOException | HttpException e) {
            throw new RuntimeException(e);
        }
    }

    public void runReplay(Path directory, ReplayEngine replayEngine) throws IOException {
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
    }

    private enum Operation {
        Read, Close
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

    public static class ReplayEngine implements Consumer<ReplayEntry> {
        private final Map<String, ArrayList<byte[]>> liveStreams;
        private final Consumer<Stream<byte[]>> fullDataHandler;

        public ReplayEngine(Consumer<Stream<byte[]>> fullDataHandler) {
            liveStreams = new HashMap<>();
            this.fullDataHandler = fullDataHandler;
        }

        @Override
        public void accept(ReplayEntry e) {
            if (e.operation.equals(Operation.Close)) {
                var priorBuffers = liveStreams.get(e.id);
                if (priorBuffers != null) {
                    fullDataHandler.accept(priorBuffers.stream());
                    liveStreams.remove(e.id);
                }
            } else if (e.operation.equals(Operation.Read)) {
                var runningList = liveStreams.putIfAbsent(e.id, new ArrayList<>());
                if (runningList==null) { runningList = liveStreams.get(e.id); }
                runningList.add(e.data);
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
            System.out.println("line="+line);
            if (!m.matches()) {
                continue;
            }
            String uniqueConnectionKey = m.group(CLUSTER_GROUP) + "," + m.group(CHANNEL_ID_GROUP);
            int charsToRead = Optional.ofNullable(m.group(SIZE_GROUP)).map(s->Integer.parseInt(s)).orElse(0);
            String operation = m.group(OPERATION_GROUP);
            String timestampStr = m.group(TIMESTAMP_GROUP);

            if (operation != null) {
                if (operation.equals(READ_OPERATION)) {
                    byte[] packetData = readPacketData(lineReader, charsToRead);
                    replayEntryConsumer.accept(new ReplayEntry(uniqueConnectionKey, timestampStr, Operation.Read, packetData));
                } else if (operation.equals(WRITE_OPERATION)) {
                    readPacketData(lineReader, charsToRead); // just advance the reader
                } else if (operation.equals(UNREGISTERED_OPERATION)) {
                    replayEntryConsumer.accept(new ReplayEntry(uniqueConnectionKey, timestampStr, Operation.Close, null));
                }
            }
        }
    }

    private byte[] readPacketData(BufferedReader lineReader, int sizeRemaining) throws IOException {
        String nextLine = lineReader.readLine();
        return Base64.getDecoder().decode(nextLine);
    }
}
