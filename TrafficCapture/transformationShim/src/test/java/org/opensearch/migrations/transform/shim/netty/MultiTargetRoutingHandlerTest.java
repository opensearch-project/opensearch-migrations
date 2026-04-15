package org.opensearch.migrations.transform.shim.netty;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.transform.shim.validation.TargetResponse;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MultiTargetRoutingHandler}.
 */
class MultiTargetRoutingHandlerTest {

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
            String bodyStr = new String(resultBytes);
            assertTrue(bodyStr.contains("myTarget"));
        } finally {
            result.release();
        }
    }
}
