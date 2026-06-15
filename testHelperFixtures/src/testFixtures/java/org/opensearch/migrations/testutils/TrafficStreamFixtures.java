package org.opensearch.migrations.testutils;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;

public class TrafficStreamFixtures {
    public static final String BYOC_PUT_RECORD_KEY = "byoc-put-1";
    public static final String BYOC_PUT_NODE_ID = "byoc-fixture";
    public static final String BYOC_PUT_CONNECTION_ID = "conn-1";
    public static final String BYOC_PUT_REQUEST =
        "PUT /byoc-e2e/_doc/1 HTTP/1.1\r\n"
            + "Host: opensearch-cluster-master-headless\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 2\r\n"
            + "\r\n"
            + "{}";
    public static final String BYOC_PUT_RESPONSE =
        "HTTP/1.1 201 Created\r\n"
            + "Content-Length: 0\r\n"
            + "\r\n";

    private TrafficStreamFixtures() {}

    public static void main(String[] args) throws IOException {
        var outputPath = args.length > 0
            ? Path.of(args[0])
            : Path.of("build", "byoc-fixtures", "byoc-put.proto.gz");
        writeByocPutKafkaExportGzip(outputPath);
        System.out.println(outputPath.toAbsolutePath());
    }

    public static void writeByocPutKafkaExportGzip(Path outputPath) throws IOException {
        writeKafkaExportGzip(outputPath, BYOC_PUT_RECORD_KEY, makeByocPutTrafficStream());
    }

    public static void writeKafkaExportGzip(Path outputPath, String recordKey, TrafficStream trafficStream)
        throws IOException {
        var parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (
            var gzipStream = new GZIPOutputStream(Files.newOutputStream(outputPath));
            var writer = new OutputStreamWriter(gzipStream, StandardCharsets.UTF_8)
        ) {
            writer.write(makeKafkaExportLine(recordKey, trafficStream));
            writer.write('\n');
        }
    }

    public static String makeByocPutKafkaExportLine() {
        return makeKafkaExportLine(BYOC_PUT_RECORD_KEY, makeByocPutTrafficStream());
    }

    public static String makeKafkaExportLine(String recordKey, TrafficStream trafficStream) {
        return recordKey + "|" + Base64.getEncoder().encodeToString(trafficStream.toByteArray());
    }

    public static TrafficStream makeByocPutTrafficStream() {
        return makeHttpRequestResponseTrafficStream(
            BYOC_PUT_NODE_ID,
            BYOC_PUT_CONNECTION_ID,
            BYOC_PUT_REQUEST,
            BYOC_PUT_RESPONSE
        );
    }

    public static TrafficStream makeHttpRequestTrafficStream(String nodeId, String connectionId, String request) {
        var timestamp = Timestamp.newBuilder().setSeconds(1).build();
        return TrafficStream.newBuilder()
            .setNodeId(nodeId)
            .setConnectionId(connectionId)
            .setNumberOfThisLastChunk(0)
            .addSubStream(makeReadObservation(timestamp, request))
            .addSubStream(makeCloseObservation(timestamp))
            .build();
    }

    public static TrafficStream makeHttpRequestResponseTrafficStream(
        String nodeId,
        String connectionId,
        String request,
        String response
    ) {
        var timestamp = Timestamp.newBuilder().setSeconds(1).build();
        return TrafficStream.newBuilder()
            .setNodeId(nodeId)
            .setConnectionId(connectionId)
            .setNumberOfThisLastChunk(0)
            .addSubStream(makeReadObservation(timestamp, request))
            .addSubStream(makeEndOfMessageObservation(timestamp))
            .addSubStream(makeWriteObservation(timestamp, response))
            .addSubStream(makeCloseObservation(timestamp))
            .build();
    }

    public static TrafficObservation makeReadObservation(Timestamp timestamp, String data) {
        return TrafficObservation.newBuilder()
            .setTs(timestamp)
            .setRead(ReadObservation.newBuilder()
                .setData(ByteString.copyFrom(data, StandardCharsets.UTF_8)))
            .build();
    }

    public static TrafficObservation makeWriteObservation(Timestamp timestamp, String data) {
        return TrafficObservation.newBuilder()
            .setTs(timestamp)
            .setWrite(WriteObservation.newBuilder()
                .setData(ByteString.copyFrom(data, StandardCharsets.UTF_8)))
            .build();
    }

    public static TrafficObservation makeEndOfMessageObservation(Timestamp timestamp) {
        return TrafficObservation.newBuilder()
            .setTs(timestamp)
            .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder())
            .build();
    }

    public static TrafficObservation makeCloseObservation(Timestamp timestamp) {
        return TrafficObservation.newBuilder()
            .setTs(timestamp)
            .setClose(CloseObservation.getDefaultInstance())
            .build();
    }
}
