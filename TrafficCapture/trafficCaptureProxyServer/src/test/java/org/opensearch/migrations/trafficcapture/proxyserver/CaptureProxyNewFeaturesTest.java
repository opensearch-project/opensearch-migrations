package org.opensearch.migrations.trafficcapture.proxyserver;

import java.util.stream.Stream;

import org.opensearch.migrations.trafficcapture.netty.HeaderValueFilteringCapturePredicate;
import org.opensearch.migrations.trafficcapture.proxyserver.netty.ProxyChannelInitializer;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CaptureProxyNewFeaturesTest {

    @Test
    void testNumThreadsDefaultIsZero() {
        var params = CaptureProxy.parseArgs(new String[]{
            "--destinationUri", "http://localhost:9200",
            "--listenPort", "9201",
            "--noCapture"
        });
        Assertions.assertEquals(0, params.numThreads);
    }

    @Test
    void testReliableCaptureDefaultIsTrue() {
        var params = CaptureProxy.parseArgs(new String[]{
            "--destinationUri", "http://localhost:9200",
            "--listenPort", "9201",
            "--noCapture"
        });
        Assertions.assertTrue(params.reliableCapture);
    }

    @Test
    void testReliableCaptureFalseFromArgs() {
        var params = CaptureProxy.parseArgs(new String[]{
            "--destinationUri", "http://localhost:9200",
            "--listenPort", "9201",
            "--noCapture",
            "--reliableCapture", "false"
        });
        Assertions.assertFalse(params.reliableCapture);
    }

    @Test
    void testMaxConnectionsDefaultIsZero() {
        var params = CaptureProxy.parseArgs(new String[]{
            "--destinationUri", "http://localhost:9200",
            "--listenPort", "9201",
            "--noCapture"
        });
        Assertions.assertEquals(0, params.maxConnections);
    }

    @Test
    void testMaxConnectionsFromArgs() {
        var params = CaptureProxy.parseArgs(new String[]{
            "--destinationUri", "http://localhost:9200",
            "--listenPort", "9201",
            "--noCapture",
            "--maxConnections", "500"
        });
        Assertions.assertEquals(500, params.maxConnections);
    }

    static Stream<HttpMethod> mutatingMethods() {
        return Stream.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH);
    }

    static Stream<HttpMethod> nonMutatingMethods() {
        return Stream.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);
    }

    @ParameterizedTest
    @MethodSource("mutatingMethods")
    void testShouldGuaranteeMessageOffloading_reliableCaptureTrue_mutatingMethods(HttpMethod method) {
        var predicate = HeaderValueFilteringCapturePredicate.builder().build();
        var initializer = new ProxyChannelInitializer<>(null, null, null, null, predicate, true);
        var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, "/test");
        Assertions.assertTrue(initializer.shouldGuaranteeMessageOffloading(request));
    }

    @ParameterizedTest
    @MethodSource("mutatingMethods")
    void testShouldGuaranteeMessageOffloading_reliableCaptureFalse_neverBlocks(HttpMethod method) {
        var predicate = HeaderValueFilteringCapturePredicate.builder().build();
        var initializer = new ProxyChannelInitializer<>(null, null, null, null, predicate, false);
        var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, "/test");
        Assertions.assertFalse(initializer.shouldGuaranteeMessageOffloading(request));
    }

    @ParameterizedTest
    @MethodSource("nonMutatingMethods")
    void testShouldGuaranteeMessageOffloading_nonMutatingMethods_neverBlocks(HttpMethod method) {
        var predicate = HeaderValueFilteringCapturePredicate.builder().build();
        var initializer = new ProxyChannelInitializer<>(null, null, null, null, predicate, true);
        var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, "/test");
        Assertions.assertFalse(initializer.shouldGuaranteeMessageOffloading(request));
    }
}
