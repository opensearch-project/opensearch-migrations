package org.opensearch.migrations.transform.shim.netty;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.shim.reporting.MetricsReceiver;
import org.opensearch.migrations.transform.shim.validation.Target;
import org.opensearch.migrations.transform.shim.validation.TargetResponse;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link MultiTargetRoutingHandler}.
 */
@ExtendWith(MockitoExtension.class)
class MultiTargetRoutingHandlerTest {

    private static final URI DUMMY_URI = URI.create("http://localhost:9200");

    @Mock
    private MetricsReceiver mockMetricsReceiver;

    // --- buildPrimaryResponse tests ---

    private MultiTargetRoutingHandler handler() {
        return new MultiTargetRoutingHandler(
            Map.of(), "primary", Set.of(), List.of(),
            Duration.ofSeconds(5), null, 10485760, new AtomicInteger()
        );
    }

    private FullHttpResponse invokeBuildPrimaryResponse(TargetResponse response) throws Exception {
        Method method = MultiTargetRoutingHandler.class.getDeclaredMethod(
            "buildPrimaryResponse", TargetResponse.class);
        method.setAccessible(true);
        return (FullHttpResponse) method.invoke(handler(), response);
    }

    @Test
    void transformException_returns500() throws Exception {
        var error = new TransformException("transform failed", new RuntimeException("cause"));
        var response = TargetResponse.error("primary", Duration.ofMillis(1), error);
        FullHttpResponse result = invokeBuildPrimaryResponse(response);
        try {
            assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), result.status().code());
        } finally {
            result.release();
        }
    }

    @Test
    void nonTransformException_returns502() throws Exception {
        var error = new RuntimeException("connection refused");
        var response = TargetResponse.error("primary", Duration.ofMillis(1), error);
        FullHttpResponse result = invokeBuildPrimaryResponse(response);
        try {
            assertEquals(HttpResponseStatus.BAD_GATEWAY.code(), result.status().code());
        } finally {
            result.release();
        }
    }

    @Test
    void successResponse_returnsCorrectStatus() throws Exception {
        byte[] body = "{\"ok\":true}".getBytes();
        var response = new TargetResponse(
            "primary", 200, body, Map.of("ok", true),
            Duration.ofMillis(10), Duration.ZERO, Duration.ZERO, null
        );
        FullHttpResponse result = invokeBuildPrimaryResponse(response);
        try {
            assertEquals(200, result.status().code());
            byte[] resultBytes = new byte[result.content().readableBytes()];
            result.content().readBytes(resultBytes);
            assertEquals("{\"ok\":true}", new String(resultBytes));
        } finally {
            result.release();
        }
    }

    @Test
    void errorResponse_bodyContainsTargetName() throws Exception {
        var error = new RuntimeException("something broke");
        var response = TargetResponse.error("myTarget", Duration.ofMillis(1), error);
        FullHttpResponse result = invokeBuildPrimaryResponse(response);
        try {
            byte[] resultBytes = new byte[result.content().readableBytes()];
            result.content().readBytes(resultBytes);
            assertTrue(new String(resultBytes).contains("myTarget"));
        } finally {
            result.release();
        }
    }

    private static final IJsonTransformer IDENTITY_TRANSFORM = new IJsonTransformer() {
        @Override
        public Object transformJson(Object incomingJson) { return incomingJson; }
        @Override
        public void close() {}
    };

    @SuppressWarnings("unchecked")
    private static IJsonTransformer metricsEmittingTransform(Map<String, Object> metrics) {
        return new IJsonTransformer() {
            @Override
            public Object transformJson(Object incomingJson) {
                if (incomingJson instanceof Map) {
                    ((Map<String, Object>) incomingJson).put("_metrics", metrics);
                }
                return incomingJson;
            }
            @Override
            public void close() {}
        };
    }

    private static Map<String, Object> baseRequestMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("method", "GET");
        map.put("URI", "/test?q=hello");
        return map;
    }

    private Object invokeDispatchAll(
        MultiTargetRoutingHandler handler, Map<String, Object> requestMap
    ) throws Exception {
        Method method = MultiTargetRoutingHandler.class.getDeclaredMethod(
            "dispatchAll", Map.class,
            Class.forName("org.opensearch.migrations.transform.shim.tracing.ShimRequestContext"));
        method.setAccessible(true);
        return method.invoke(handler, requestMap, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> getPerTargetTransformedRequests(Object dr) throws Exception {
        Field f = dr.getClass().getDeclaredField("perTargetTransformedRequests");
        f.setAccessible(true);
        return (Map<String, Map<String, Object>>) f.get(dr);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> getPerTargetTransformMetrics(Object dr) throws Exception {
        Field f = dr.getClass().getDeclaredField("perTargetTransformMetrics");
        f.setAccessible(true);
        return (Map<String, Map<String, Object>>) f.get(dr);
    }

    @Test
    void perTargetTransformedRequests_containsOnlyTargetsWithTransform() throws Exception {
        Target baseline = new Target("baseline", DUMMY_URI);
        Target candidate = new Target("candidate", DUMMY_URI, IDENTITY_TRANSFORM, null, null);
        var handler = new MultiTargetRoutingHandler(
            Map.of("baseline", baseline, "candidate", candidate),
            "baseline", Set.of("baseline", "candidate"),
            List.of(), Duration.ofSeconds(5), null, 10485760, new AtomicInteger());

        Object result = invokeDispatchAll(handler, baseRequestMap());
        Map<String, Map<String, Object>> transformed = getPerTargetTransformedRequests(result);
        assertTrue(transformed.containsKey("candidate"));
        assertFalse(transformed.containsKey("baseline"));
        assertEquals(1, transformed.size());
    }

    @Test
    void perTargetTransformMetrics_emptyWhenNoTransformsEmitMetrics() throws Exception {
        Target baseline = new Target("baseline", DUMMY_URI);
        Target candidate = new Target("candidate", DUMMY_URI, IDENTITY_TRANSFORM, null, null);
        var handler = new MultiTargetRoutingHandler(
            Map.of("baseline", baseline, "candidate", candidate),
            "baseline", Set.of("baseline", "candidate"),
            List.of(), Duration.ofSeconds(5), null, 10485760, new AtomicInteger());

        Object result = invokeDispatchAll(handler, baseRequestMap());
        Map<String, Map<String, Object>> metrics = getPerTargetTransformMetrics(result);
        assertTrue(metrics.containsKey("candidate"));
        assertFalse(metrics.containsKey("baseline"));
        assertTrue(metrics.get("candidate").isEmpty());
    }

    @Test
    void perTargetTransformMetrics_capturesMetricsFromTransformResult() throws Exception {
        Map<String, Object> emittedMetrics = Map.of("hitCount", 42, "queryTime", 15);
        Target baseline = new Target("baseline", DUMMY_URI);
        Target candidate = new Target("candidate", DUMMY_URI,
            metricsEmittingTransform(emittedMetrics), null, null);
        var handler = new MultiTargetRoutingHandler(
            Map.of("baseline", baseline, "candidate", candidate),
            "baseline", Set.of("baseline", "candidate"),
            List.of(), Duration.ofSeconds(5), null, 10485760, new AtomicInteger());

        Object result = invokeDispatchAll(handler, baseRequestMap());
        Map<String, Map<String, Object>> metrics = getPerTargetTransformMetrics(result);
        assertNotNull(metrics.get("candidate"));
        assertEquals(42, metrics.get("candidate").get("hitCount"));
        assertEquals(15, metrics.get("candidate").get("queryTime"));
    }

    // --- MetricsReceiver integration tests ---

    private static TargetResponse successResponse(String targetName) {
        byte[] body = "{\"status\":\"ok\"}".getBytes();
        return new TargetResponse(targetName, 200, body, Map.of("status", "ok"),
            Duration.ofMillis(50), Duration.ZERO, Duration.ZERO, null);
    }

    private MultiTargetRoutingHandler buildHandler(
        Map<String, Target> targets, String primary,
        Set<String> active, MetricsReceiver metricsReceiver
    ) {
        return new MultiTargetRoutingHandler(targets, primary, active, List.of(),
            Duration.ofSeconds(5), null, 10485760, new AtomicInteger(), null, metricsReceiver);
    }

    private Object createDispatchResult(
        Map<String, CompletableFuture<TargetResponse>> futures,
        Map<String, Map<String, Object>> transformedReqs,
        Map<String, Map<String, Object>> transformMetrics
    ) throws Exception {
        Class<?> drClass = Class.forName(
            "org.opensearch.migrations.transform.shim.netty.MultiTargetRoutingHandler$DispatchResult");
        Constructor<?> ctor = drClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(futures, transformedReqs, transformMetrics);
    }

    private FullHttpResponse runHandlerScenario(
        MultiTargetRoutingHandler handler,
        Map<String, CompletableFuture<TargetResponse>> futures,
        Map<String, Map<String, Object>> transformedReqs,
        Map<String, Map<String, Object>> transformMetrics,
        Map<String, Object> requestMap
    ) throws Exception {
        Object dr = createDispatchResult(futures, transformedReqs, transformMetrics);
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        ChannelHandlerContext ctx = channel.pipeline().firstContext();

        Class<?> drClass = Class.forName(
            "org.opensearch.migrations.transform.shim.netty.MultiTargetRoutingHandler$DispatchResult");
        Method method = MultiTargetRoutingHandler.class.getDeclaredMethod(
            "handlePrimaryCompletion", ChannelHandlerContext.class, drClass,
            boolean.class, Map.class, long.class,
            Class.forName("org.opensearch.migrations.transform.shim.tracing.ShimRequestContext"));
        method.setAccessible(true);
        method.invoke(handler, ctx, dr, true, requestMap, 1L, null);
        channel.runPendingTasks();
        return channel.readOutbound();
    }

    @Test
    void metricsReceiverProcessCalled_inDualMode() throws Exception {
        var handler = buildHandler(
            Map.of("baseline", new Target("baseline", DUMMY_URI),
                   "candidate", new Target("candidate", DUMMY_URI)),
            "baseline", Set.of("baseline", "candidate"), mockMetricsReceiver);

        Map<String, Object> requestMap = baseRequestMap();
        var transformedReqs = Map.of("candidate", Map.<String, Object>of("method", "GET", "URI", "/transformed"));
        var transformMetrics = Map.of("candidate", Map.<String, Object>of("hitCount", 42));
        var futures = new LinkedHashMap<String, CompletableFuture<TargetResponse>>();
        futures.put("baseline", CompletableFuture.completedFuture(successResponse("baseline")));
        futures.put("candidate", CompletableFuture.completedFuture(successResponse("candidate")));

        FullHttpResponse response = runHandlerScenario(handler, futures, transformedReqs, transformMetrics, requestMap);
        verify(mockMetricsReceiver).process(eq(requestMap), eq(transformedReqs), any(Map.class), eq(transformMetrics));
        assertNotNull(response);
        response.release();
    }

    @Test
    void handlerOperatesNormally_whenMetricsReceiverIsNull() throws Exception {
        var handler = buildHandler(
            Map.of("baseline", new Target("baseline", DUMMY_URI),
                   "candidate", new Target("candidate", DUMMY_URI)),
            "baseline", Set.of("baseline", "candidate"), null);

        var futures = new LinkedHashMap<String, CompletableFuture<TargetResponse>>();
        futures.put("baseline", CompletableFuture.completedFuture(successResponse("baseline")));
        futures.put("candidate", CompletableFuture.completedFuture(successResponse("candidate")));

        FullHttpResponse response = runHandlerScenario(handler, futures, Map.of(), Map.of(), baseRequestMap());
        assertNotNull(response);
        response.release();
    }

    @Test
    void primaryResponseReturned_whenMetricsReceiverThrows() throws Exception {
        doThrow(new RuntimeException("boom")).when(mockMetricsReceiver).process(any(), any(), any(), any());
        var handler = buildHandler(
            Map.of("baseline", new Target("baseline", DUMMY_URI),
                   "candidate", new Target("candidate", DUMMY_URI)),
            "baseline", Set.of("baseline", "candidate"), mockMetricsReceiver);

        var futures = new LinkedHashMap<String, CompletableFuture<TargetResponse>>();
        futures.put("baseline", CompletableFuture.completedFuture(successResponse("baseline")));
        futures.put("candidate", CompletableFuture.completedFuture(successResponse("candidate")));

        FullHttpResponse response = runHandlerScenario(
            handler, futures, Map.of("candidate", Map.of()), Map.of(), baseRequestMap());
        assertNotNull(response);
        assertEquals(200, response.status().code());
        response.release();
    }

    @Test
    void metricsReceiverNotCalled_inSingleTargetMode() throws Exception {
        var handler = buildHandler(
            Map.of("baseline", new Target("baseline", DUMMY_URI)),
            "baseline", Set.of("baseline"), mockMetricsReceiver);

        var futures = new LinkedHashMap<String, CompletableFuture<TargetResponse>>();
        futures.put("baseline", CompletableFuture.completedFuture(successResponse("baseline")));

        FullHttpResponse response = runHandlerScenario(handler, futures, Map.of(), Map.of(), baseRequestMap());
        verify(mockMetricsReceiver, never()).process(any(), any(), any(), any());
        assertNotNull(response);
        response.release();
    }
}
