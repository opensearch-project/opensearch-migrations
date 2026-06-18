package org.opensearch.migrations.utils.kafka;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

import org.opensearch.migrations.testutils.TrafficStreamFixtures;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TrafficStreamFixturesTest {
    @Test
    void byocPutFixtureIsKafkaExportCompatible() throws Exception {
        var outputPath = Files.createTempFile("byoc-put", ".proto.gz");
        TrafficStreamFixtures.writeByocPutKafkaExportGzip(outputPath);

        try (
            var gzipStream = new GZIPInputStream(Files.newInputStream(outputPath));
            var reader = new BufferedReader(new InputStreamReader(gzipStream, StandardCharsets.UTF_8))
        ) {
            var line = reader.readLine();
            Assertions.assertNotNull(line);
            Assertions.assertNull(reader.readLine());

            var parts = line.split("\\|");
            Assertions.assertEquals(2, parts.length);
            Assertions.assertEquals(TrafficStreamFixtures.BYOC_PUT_RECORD_KEY, parts[0]);

            var trafficStream = TrafficStream.parseFrom(Base64.getDecoder().decode(parts[1]));
            Assertions.assertEquals(TrafficStreamFixtures.BYOC_PUT_NODE_ID, trafficStream.getNodeId());
            Assertions.assertEquals(TrafficStreamFixtures.BYOC_PUT_CONNECTION_ID, trafficStream.getConnectionId());
            Assertions.assertEquals(4, trafficStream.getSubStreamCount());
            Assertions.assertEquals(
                TrafficStreamFixtures.BYOC_PUT_REQUEST,
                trafficStream.getSubStream(0).getRead().getData().toString(StandardCharsets.UTF_8)
            );
            Assertions.assertTrue(trafficStream.getSubStream(1).hasEndOfMessageIndicator());
            Assertions.assertEquals(
                TrafficStreamFixtures.BYOC_PUT_RESPONSE,
                trafficStream.getSubStream(2).getWrite().getData().toString(StandardCharsets.UTF_8)
            );
            Assertions.assertTrue(trafficStream.getSubStream(3).hasClose());
        }
    }
}
