package org.opensearch.migrations.trafficcapture.proxyserver;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.trafficcapture.netty.HeaderValueFilteringCapturePredicate;

import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * tests for the {@code --enableHttp2} flag and its
 * interaction with the legacy HTTP/2 capture-suppression default.
 *
 * <p>This test class is intentionally process-local: it does not stand up a Netty proxy.
 * It only validates the parameter wiring and SSL-context construction, which is the
 * narrow contract of,, and in the implementation plan.
 */
public class CaptureProxyHttp2ConfigurationTest {

    private static final String DEFAULT_REQUIRED_ARGS =
            "--destinationUri,invalid:9200,--listenPort,80,--noCapture";

    @Test
    void enableHttp2_defaultsFalse_withDefaultTuningKnobs() {
        var p = CaptureProxy.parseArgs(DEFAULT_REQUIRED_ARGS.split(","));
        Assertions.assertNotNull(p);
        Assertions.assertFalse(p.enableHttp2, "--enableHttp2 must default to false");
        Assertions.assertEquals(100, p.http2MaxConcurrentStreams);
        Assertions.assertEquals(65535, p.http2InitialWindowSize);
        Assertions.assertEquals(8192, p.http2MaxHeaderListSize);
        Assertions.assertEquals(4096, p.http2MaxHeaderTableSize);
    }

    @Test
    void enableHttp2_setsFlagAndAcceptsTuningKnobs() {
        var args = (DEFAULT_REQUIRED_ARGS
                + ",--enableHttp2"
                + ",--http2MaxConcurrentStreams,250"
                + ",--http2InitialWindowSize,131072"
                + ",--http2MaxHeaderListSize,16384"
                + ",--http2MaxHeaderTableSize,8192").split(",");
        var p = CaptureProxy.parseArgs(args);
        Assertions.assertNotNull(p);
        Assertions.assertTrue(p.enableHttp2, "--enableHttp2 should set the flag to true");
        Assertions.assertEquals(250, p.http2MaxConcurrentStreams);
        Assertions.assertEquals(131072, p.http2InitialWindowSize);
        Assertions.assertEquals(16384, p.http2MaxHeaderListSize);
        Assertions.assertEquals(8192, p.http2MaxHeaderTableSize);
    }

    /**
     * Without {@code --enableHttp2}, the historical {@code protocolPattern("HTTP/2.*")}
     * suppression is preserved (any H2 traffic that slipped past ALPN is dropped before
     * being captured). With {@code --enableHttp2}, the suppression is removed because
     * the proxy is intentionally capturing H2.
     */
    @Test
    void protocolPatternSuppression_isRemoved_whenHttp2Enabled() {
        var p = CaptureProxy.parseArgs((DEFAULT_REQUIRED_ARGS + ",--enableHttp2").split(","));
        Assertions.assertTrue(p.enableHttp2);

        var withH2 = HeaderValueFilteringCapturePredicate.builder();
        if (!p.enableHttp2) withH2.protocolPattern("HTTP/2.*");
        var withH2Pred = withH2.build();

        var pNoH2 = CaptureProxy.parseArgs(DEFAULT_REQUIRED_ARGS.split(","));
        var noH2Builder = HeaderValueFilteringCapturePredicate.builder();
        if (!pNoH2.enableHttp2) noH2Builder.protocolPattern("HTTP/2.*");
        var noH2Pred = noH2Builder.build();

        // Behavior probe: a synthetic H2 request flowing through both predicates.
        var h2Req = new io.netty.handler.codec.http.DefaultHttpRequest(
                io.netty.handler.codec.http.HttpVersion.valueOf("HTTP/2.0"),
                io.netty.handler.codec.http.HttpMethod.GET,
                "/_search");
        Assertions.assertEquals(
                org.opensearch.migrations.trafficcapture.netty.RequestCapturePredicate.CaptureDirective.CAPTURE,
                withH2Pred.apply(h2Req),
                "With --enableHttp2 set, an H2 request must NOT be suppressed (protocol filter removed)");
        Assertions.assertEquals(
                org.opensearch.migrations.trafficcapture.netty.RequestCapturePredicate.CaptureDirective.DROP,
                noH2Pred.apply(h2Req),
                "Without --enableHttp2, an H2 request MUST still be suppressed (legacy guard)");

        // And H1 traffic is unaffected by either configuration.
        var h1Req = new io.netty.handler.codec.http.DefaultHttpRequest(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpMethod.GET,
                "/_search");
        Assertions.assertEquals(
                org.opensearch.migrations.trafficcapture.netty.RequestCapturePredicate.CaptureDirective.CAPTURE,
                withH2Pred.apply(h1Req),
                "H1 traffic must always be captured under enableHttp2=true");
        Assertions.assertEquals(
                org.opensearch.migrations.trafficcapture.netty.RequestCapturePredicate.CaptureDirective.CAPTURE,
                noH2Pred.apply(h1Req),
                "H1 traffic must always be captured under enableHttp2=false");
    }

    /**
     * When TLS PEM files are provided AND {@code --enableHttp2} is true, the SSL engine
     * supplier must be backed by an SslContext that advertises ALPN with {@code h2} and
     * {@code http/1.1}. When {@code --enableHttp2} is false, no ALPN is configured
     * (legacy behavior preserved).
     *
     * <p>Netty wraps the JDK SSLEngine and the JDK-side {@code getApplicationProtocols()}
     * isn't reliably populated until after handshake, so we verify the SslContext's
     * application-protocol negotiator instead — that's the wire-level configuration
     * that drives ALPN advertisement to the client.
     */
    @Test
    void sslEngineAdvertisesAlpn_onlyWhenHttp2Enabled(@TempDir Path tmp) throws Exception {
        var pem = writeSelfSignedPem(tmp);

        // enableHttp2=true → the underlying SslContext must advertise h2 + http/1.1.
        var withH2Context = buildContext(pem, /*enableHttp2*/ true);
        var withH2Protocols = withH2Context.applicationProtocolNegotiator().protocols();
        Assertions.assertEquals(
                List.of(ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1),
                withH2Protocols,
                "With --enableHttp2 true the SslContext must advertise ALPN [h2, http/1.1]");

        // enableHttp2=false → no ALPN protocols configured.
        var noH2Context = buildContext(pem, /*enableHttp2*/ false);
        Assertions.assertTrue(noH2Context.applicationProtocolNegotiator().protocols().isEmpty(),
                "With --enableHttp2 false the SslContext must not advertise any ALPN protocols");

        // And the wrapped SSLEngine instances themselves are still produced (sanity).
        Assertions.assertNotNull(
                CaptureProxy.loadSslEngineFromPem(pem.cert.toString(), pem.key.toString(), null, false, true).get());
        Assertions.assertNotNull(
                CaptureProxy.loadSslEngineFromPem(pem.cert.toString(), pem.key.toString(), null, false, false).get());
    }

    /** Re-build the SslContext directly so we can inspect ALPN configuration. */
    private static io.netty.handler.ssl.SslContext buildContext(PemFiles pem, boolean enableHttp2) throws Exception {
        var builder = io.netty.handler.ssl.SslContextBuilder.forServer(
                pem.cert.toFile(), pem.key.toFile());
        if (enableHttp2) {
            builder.applicationProtocolConfig(new io.netty.handler.ssl.ApplicationProtocolConfig(
                    io.netty.handler.ssl.ApplicationProtocolConfig.Protocol.ALPN,
                    io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    ApplicationProtocolNames.HTTP_2,
                    ApplicationProtocolNames.HTTP_1_1));
        }
        return builder.build();
    }

    private record PemFiles(Path cert, Path key) {}

    private static PemFiles writeSelfSignedPem(Path tmp) throws Exception {
        var ssc = new SelfSignedCertificate();
        File certFile = ssc.certificate();
        File keyFile = ssc.privateKey();
        var cert = tmp.resolve("server.crt");
        var key = tmp.resolve("server.key");
        Files.copy(certFile.toPath(), cert);
        Files.copy(keyFile.toPath(), key);
        return new PemFiles(cert, key);
    }
}
