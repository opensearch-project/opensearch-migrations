package org.opensearch.migrations.replay;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datatypes.MockMetricsBuilder;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.datatypes.PojoUniqueSourceRequestKey;

import java.util.Map;
import java.util.Optional;

class ParsedHttpMessagesAsDictsTest {

    private static final PojoTrafficStreamKeyAndContext TEST_TRAFFIC_STREAM_KEY =
            PojoTrafficStreamKeyAndContext.build("N","C",1, TestTrafficStreamsLifecycleContext::new);

    ParsedHttpMessagesAsDicts makeTestData() {
        return makeTestData(null, null);
    }

    ParsedHttpMessagesAsDicts makeTestData(Map<String, Object> sourceResponse, Map<String, Object> targetResponse) {
        return new ParsedHttpMessagesAsDicts(
                Optional.empty(), Optional.ofNullable(sourceResponse),
                Optional.empty(), Optional.ofNullable(targetResponse));
    }

    String getLoggedMetrics(ParsedHttpMessagesAsDicts parsedMessage) {
        var metricsBuilder = new MockMetricsBuilder();
        metricsBuilder = (MockMetricsBuilder) parsedMessage.buildStatusCodeMetrics(metricsBuilder,
                new PojoUniqueSourceRequestKey(TEST_TRAFFIC_STREAM_KEY, 0));
        return metricsBuilder.getLoggedAttributes();
    }

    @Test
    public void testMetricsAreRightWhenBothAreEmpty() {
        var loggedMetrics = getLoggedMetrics(makeTestData());
        Assertions.assertEquals("REQUEST_ID:C.0|HTTP_STATUS_MATCH:0", loggedMetrics);
    }

    @Test
    public void testMetricsAreRightWhenSourceIsEmpty() {
        var loggedMetrics = getLoggedMetrics(makeTestData(
                null,
                Map.of("Status-Code", Integer.valueOf(200))
        ));
        Assertions.assertEquals("REQUEST_ID:C.0|TARGET_HTTP_STATUS:200|HTTP_STATUS_MATCH:0", loggedMetrics);
    }

    @Test
    public void testMetricsAreRightWhenTargetIsEmpty() {
        var loggedMetrics = getLoggedMetrics(makeTestData(
                Map.of("Status-Code", Integer.valueOf(200)),
                null
        ));
        Assertions.assertEquals("REQUEST_ID:C.0|SOURCE_HTTP_STATUS:200|HTTP_STATUS_MATCH:0", loggedMetrics);
    }

    @Test
    public void testMetricsAreRightWhenDifferent() {
        var loggedMetrics = getLoggedMetrics(makeTestData(
                Map.of("Status-Code", Integer.valueOf(200)),
                Map.of("Status-Code", Integer.valueOf(200))
        ));
        Assertions.assertEquals("REQUEST_ID:C.0|SOURCE_HTTP_STATUS:200|TARGET_HTTP_STATUS:200|HTTP_STATUS_MATCH:1", loggedMetrics);
    }

    @Test
    public void testMetricsAreRightWhenMissing() {
        var loggedMetrics = getLoggedMetrics(makeTestData(
                Map.of("Status-Code", Integer.valueOf(200)),
                Map.of("Status-Code", Integer.valueOf(404))));
        Assertions.assertEquals("REQUEST_ID:C.0|SOURCE_HTTP_STATUS:200|TARGET_HTTP_STATUS:404|HTTP_STATUS_MATCH:0", loggedMetrics);
    }

    @Test
    public void testMetricsAreRightWithMissingStatusCode() {
        var loggedMetrics = getLoggedMetrics(makeTestData(
                Map.of("Sorry", "exception message..."),
                Map.of("Status-Code", Integer.valueOf(404))));
        Assertions.assertEquals("REQUEST_ID:C.0|TARGET_HTTP_STATUS:404|HTTP_STATUS_MATCH:0", loggedMetrics);
    }
}