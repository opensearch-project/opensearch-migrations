package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.transform.IJsonPredicate;
import org.opensearch.migrations.transform.IJsonTransformer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FilteringTransformerWrapperTest extends InstrumentationTest {

    private static final IJsonTransformer IDENTITY = input -> input;

    // --- Unit tests for FilteringTransformerWrapper ---

    @Test
    void predicateAccepts_delegatesToTransformer() {
        IJsonPredicate alwaysAccept = obj -> true;
        var wrapper = new FilteringTransformerWrapper(IDENTITY, alwaysAccept);

        var input = new LinkedHashMap<>(Map.of("URI", "/solr/test/select", "method", "GET"));
        var result = wrapper.transformJson(input);

        Assertions.assertSame(input, result);
    }

    @Test
    void predicateRejects_throwsRequestFilteredException() {
        IJsonPredicate alwaysReject = obj -> false;
        var wrapper = new FilteringTransformerWrapper(IDENTITY, alwaysReject);

        var input = new LinkedHashMap<>(Map.of("URI", "/solr/test/admin", "method", "GET"));

        Assertions.assertThrows(RequestFilteredException.class, () -> wrapper.transformJson(input));
    }

    @Test
    void predicateFiltersOnUri_selectiveBehavior() {
        IJsonPredicate selectOnly = obj -> {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) obj;
            var uri = (String) map.get("URI");
            return uri != null && uri.contains("/select");
        };
        var wrapper = new FilteringTransformerWrapper(IDENTITY, selectOnly);

        // /select passes through
        var selectReq = new LinkedHashMap<>(Map.of("URI", "/solr/test/select?q=*:*", "method", "GET"));
        Assertions.assertSame(selectReq, wrapper.transformJson(selectReq));

        // /admin is filtered
        var adminReq = new LinkedHashMap<>(Map.of("URI", "/solr/test/admin/cores", "method", "GET"));
        Assertions.assertThrows(RequestFilteredException.class, () -> wrapper.transformJson(adminReq));
    }

    @Test
    void predicateFiltersOnMethod_postOnly() {
        IJsonPredicate postOnly = obj -> {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) obj;
            return "POST".equals(map.get("method"));
        };
        var wrapper = new FilteringTransformerWrapper(IDENTITY, postOnly);

        var postReq = new LinkedHashMap<>(Map.of("URI", "/update", "method", "POST"));
        Assertions.assertSame(postReq, wrapper.transformJson(postReq));

        var getReq = new LinkedHashMap<>(Map.of("URI", "/select", "method", "GET"));
        Assertions.assertThrows(RequestFilteredException.class, () -> wrapper.transformJson(getReq));
    }

    @Test
    void predicateReceivesFullRequestMap() {
        var receivedKeys = new java.util.ArrayList<String>();
        IJsonPredicate inspector = obj -> {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) obj;
            receivedKeys.addAll(map.keySet());
            return true;
        };
        var wrapper = new FilteringTransformerWrapper(IDENTITY, inspector);

        var input = new LinkedHashMap<>(Map.of("URI", "/test", "method", "GET", "headers", Map.of()));
        wrapper.transformJson(input);

        Assertions.assertTrue(receivedKeys.contains("URI"));
        Assertions.assertTrue(receivedKeys.contains("method"));
        Assertions.assertTrue(receivedKeys.contains("headers"));
    }

    @Test
    void transformerExceptionPropagates_notSwallowed() {
        IJsonTransformer throwingTransformer = input -> { throw new RuntimeException("transform error"); };
        var wrapper = new FilteringTransformerWrapper(throwingTransformer, obj -> true);

        var input = new LinkedHashMap<>(Map.of("URI", "/test"));
        Assertions.assertThrows(RuntimeException.class, () -> wrapper.transformJson(input));
    }

    @Test
    void close_delegatesToWrappedTransformer() throws Exception {
        var closed = new boolean[]{false};
        IJsonTransformer trackingTransformer = new IJsonTransformer() {
            @Override
            public Object transformJson(Object incomingJson) { return incomingJson; }
            @Override
            public void close() { closed[0] = true; }
        };

        var wrapper = new FilteringTransformerWrapper(trackingTransformer, obj -> true);
        wrapper.close();

        Assertions.assertTrue(closed[0]);
    }

    @Test
    void nullPredicate_throwsNPE() {
        Assertions.assertThrows(NullPointerException.class,
            () -> new FilteringTransformerWrapper(IDENTITY, null).transformJson(Map.of()));
    }

    @Test
    void predicateThrowsException_propagatesAsIs_notTreatedAsFiltered() {
        IJsonPredicate brokenPredicate = obj -> { throw new IllegalStateException("predicate crashed"); };
        var wrapper = new FilteringTransformerWrapper(IDENTITY, brokenPredicate);

        var input = new LinkedHashMap<>(Map.of("URI", "/test"));
        var thrown = Assertions.assertThrows(IllegalStateException.class, () -> wrapper.transformJson(input));
        Assertions.assertEquals("predicate crashed", thrown.getMessage());
    }

    // --- Pipeline integration test: filtered request produces SKIPPED with null output ---

    @Test
    void filteredRequest_producesSkippedWithNullOutput() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(null, 17, Duration.ZERO, List.of(), null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);

        // Predicate that rejects everything
        IJsonPredicate rejectAll = obj -> false;
        var filteringTransformer = new FilteringTransformerWrapper(IDENTITY, rejectAll);

        var transformingHandler = new HttpJsonTransformingConsumer<>(
            filteringTransformer,
            null,
            testPacketCapture,
            rootContext.getTestConnectionRequestContext(0)
        );

        // Feed a simple HTTP request
        var httpRequest = "GET /solr/test/admin/cores HTTP/1.1\r\nHost: localhost\r\n\r\n";
        transformingHandler.consumeBytes(
            io.netty.buffer.Unpooled.wrappedBuffer(httpRequest.getBytes(StandardCharsets.UTF_8)));

        var result = transformingHandler.finalizeRequest().get();

        // Verify: SKIPPED status, null output (no bytes to send)
        Assertions.assertTrue(result.transformationStatus.isSkipped());
        Assertions.assertNull(result.transformedOutput);

        // Verify: nothing was sent to the packet capture (no bytes reached the target)
        Assertions.assertEquals(0, testPacketCapture.getNumConsumes().get());
    }

    @Test
    void filteredPostRequestWithBody_producesSkippedAndReleasesBytes() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(null, 17, Duration.ZERO, List.of(), null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);

        IJsonPredicate rejectAll = obj -> false;
        var filteringTransformer = new FilteringTransformerWrapper(IDENTITY, rejectAll);

        var transformingHandler = new HttpJsonTransformingConsumer<>(
            filteringTransformer,
            null,
            testPacketCapture,
            rootContext.getTestConnectionRequestContext(0)
        );

        var body = "{\"add\":{\"doc\":{\"id\":\"1\",\"title\":\"test\"}}}";
        var httpRequest = "POST /solr/test/update HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: " + body.length() + "\r\n\r\n"
            + body;
        transformingHandler.consumeBytes(
            io.netty.buffer.Unpooled.wrappedBuffer(httpRequest.getBytes(StandardCharsets.UTF_8)));

        var result = transformingHandler.finalizeRequest().get();

        Assertions.assertTrue(result.transformationStatus.isSkipped());
        Assertions.assertNull(result.transformedOutput);
        Assertions.assertEquals(0, testPacketCapture.getNumConsumes().get());
    }

    @Test
    void acceptedRequest_producesNormalOutput() throws Exception {
        final var dummyAggregatedResponse = new AggregatedRawResponse(null, 17, Duration.ZERO, List.of(), null);
        var testPacketCapture = new TestCapturePacketToHttpHandler(Duration.ofMillis(100), dummyAggregatedResponse);

        // Predicate that accepts everything
        IJsonPredicate acceptAll = obj -> true;
        var filteringTransformer = new FilteringTransformerWrapper(IDENTITY, acceptAll);

        var transformingHandler = new HttpJsonTransformingConsumer<>(
            filteringTransformer,
            null,
            testPacketCapture,
            rootContext.getTestConnectionRequestContext(0)
        );

        var httpRequest = "GET /solr/test/select?q=*:* HTTP/1.1\r\nHost: localhost\r\n\r\n";
        transformingHandler.consumeBytes(
            io.netty.buffer.Unpooled.wrappedBuffer(httpRequest.getBytes(StandardCharsets.UTF_8)));

        var result = transformingHandler.finalizeRequest().get();

        // Verify: request passes through normally (SKIPPED means no-op transform, bytes still sent)
        Assertions.assertTrue(result.transformationStatus.isSkipped() || result.transformationStatus.isCompleted());
        Assertions.assertNotNull(result.transformedOutput);
        Assertions.assertTrue(testPacketCapture.getNumConsumes().get() > 0);
    }

    // ─── Tests that exercise actual TrafficReplayer.buildTransformerSupplier (for JaCoCo coverage) ───

    @Test
    void production_buildTransformerSupplier_noFilter_returnsBaseTransformer() {
        var transformationLoader = new org.opensearch.migrations.transform.TransformationLoader();
        var supplier = TrafficReplayer.buildTransformerSupplier(
            transformationLoader, "localhost", null, null, null);
        Assertions.assertNotNull(supplier);
        Assertions.assertNotNull(supplier.get());
    }

    @Test
    void production_buildTransformerSupplier_blankFilter_returnsBaseTransformer() {
        var transformationLoader = new org.opensearch.migrations.transform.TransformationLoader();
        var supplier = TrafficReplayer.buildTransformerSupplier(
            transformationLoader, "localhost", null, null, "   ");
        Assertions.assertNotNull(supplier);
        // Should NOT be a FilteringTransformerWrapper
        Assertions.assertFalse(supplier.get() instanceof FilteringTransformerWrapper);
    }

    @Test
    void production_buildTransformerSupplier_withFilter_returnsFilteringWrapper() {
        var transformationLoader = new org.opensearch.migrations.transform.TransformationLoader();
        // JsonJMESPathPredicateProvider expects a Map config with "script" key
        var supplier = TrafficReplayer.buildTransformerSupplier(
            transformationLoader, "localhost", null, null,
            "{\"JsonJMESPathPredicateProvider\":{\"script\":\"length(URI) > `0`\"}}");
        Assertions.assertNotNull(supplier);
        Assertions.assertInstanceOf(FilteringTransformerWrapper.class, supplier.get());
    }
}
