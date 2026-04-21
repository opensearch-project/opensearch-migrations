/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.migrations.transform.solr;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.shim.ShimProxy;
import org.opensearch.migrations.transform.shim.netty.BasicAuthSigningHandler;
import org.opensearch.migrations.transform.shim.validation.Target;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Test fixture that manages an auth-enabled Solr 8 container and a ShimProxy
 * configured with a {@link BasicAuthSigningHandler} for the Solr target.
 * <p>
 * Solr 8's BasicAuthPlugin uses SHA-256(password + salt) for credential storage.
 * The hash and salt are computed at fixture construction time and embedded in
 * {@code security.json} which is copied into the container before startup.
 */
@Slf4j
public class AuthShimTestFixture implements AutoCloseable {

    // Solr credentials
    public static final String SOLR_USER = "solr_admin";
    public static final String SOLR_PASS = "solr_pass";

    private static final String SOLR_IMAGE = "mirror.gcr.io/library/solr:8";

    private final HttpClient httpClient;
    private final GenericContainer<?> solr;
    private ShimProxy proxy;

    @Getter private String solrBaseUrl;
    @Getter private String proxyBaseUrl;

    public AuthShimTestFixture() {
        this.solr = createSolrAuthContainer();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    // ---- Solr SHA-256 credential hashing ----

    /**
     * Compute the double-SHA-256 credential pair that Solr 8's BasicAuthPlugin expects.
     * Algorithm: SHA-256(SHA-256(salt + password)). Returns "base64(hash) base64(salt)".
     * Based on Solr's Sha256AuthenticationProvider.
     */
    static String computeSolrCredential(String password) {
        try {
            var random = new SecureRandom();
            byte[] salt = new byte[32];
            random.nextBytes(salt);

            // First round: SHA-256(salt + password)
            var digest1 = MessageDigest.getInstance("SHA-256");
            digest1.update(salt);
            byte[] hash1 = digest1.digest(password.getBytes(StandardCharsets.UTF_8));

            // Second round: SHA-256(hash1)
            var digest2 = MessageDigest.getInstance("SHA-256");
            byte[] hash2 = digest2.digest(hash1);

            var encoder = Base64.getEncoder();
            return encoder.encodeToString(hash2) + " " + encoder.encodeToString(salt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute Solr credential hash", e);
        }
    }

    // ---- Container creation ----

    /**
     * Create a Solr 8 container with basic auth enabled via security.json.
     * The credential hash is computed at construction time.
     */
    @SuppressWarnings("resource")
    private static GenericContainer<?> createSolrAuthContainer() {
        var credential = computeSolrCredential(SOLR_PASS);
        var securityJson = "{\n"
            + "  \"authentication\": {\n"
            + "    \"blockUnknown\": true,\n"
            + "    \"class\": \"solr.BasicAuthPlugin\",\n"
            + "    \"credentials\": {\n"
            + "      \"" + SOLR_USER + "\": \"" + credential + "\"\n"
            + "    }\n"
            + "  },\n"
            + "  \"authorization\": {\n"
            + "    \"class\": \"solr.RuleBasedAuthorizationPlugin\",\n"
            + "    \"permissions\": [\n"
            + "      { \"name\": \"all\", \"role\": \"admin\" }\n"
            + "    ],\n"
            + "    \"user-role\": {\n"
            + "      \"" + SOLR_USER + "\": \"admin\"\n"
            + "    }\n"
            + "  }\n"
            + "}";

        log.info("Solr security.json:\n{}", securityJson);

        var imageName = DockerImageName.parse(SOLR_IMAGE).asCompatibleSubstituteFor("solr");
        return new GenericContainer<>(imageName)
            .withExposedPorts(8983)
            .withCopyToContainer(
                Transferable.of(securityJson),
                "/var/solr/data/security.json"
            )
            .waitingFor(
                Wait.forHttp("/solr/admin/info/system")
                    .forPort(8983)
                    .forStatusCode(401)
                    .withStartupTimeout(Duration.ofMinutes(2))
            );
    }

    // ---- Lifecycle ----

    public void start() throws Exception {
        solr.start();
        solrBaseUrl = "http://" + solr.getHost() + ":" + solr.getMappedPort(8983);

        // Verify auth is active
        var verifyResp = httpGetRaw(solrBaseUrl + "/solr/admin/info/system");
        log.info("Auth verification: unauthenticated request → HTTP {}", verifyResp.statusCode());

        // Verify authenticated request works
        var authResp = authenticatedSolrGet(solrBaseUrl + "/solr/admin/info/system");
        log.info("Auth verification: authenticated request succeeded (body length={})", authResp.length());

        // Build Base64-encoded auth header for Solr target
        var solrAuthHeader = "Basic " + Base64.getEncoder()
            .encodeToString((SOLR_USER + ":" + SOLR_PASS).getBytes(StandardCharsets.UTF_8));

        var solrTarget = new Target(
            "solr",
            URI.create(solrBaseUrl),
            null,  // no request transform (passthrough)
            null,  // no response transform
            () -> new BasicAuthSigningHandler(solrAuthHeader)
        );

        int proxyPort = findFreePort();
        proxy = new ShimProxy(
            proxyPort,
            Map.of("solr", solrTarget),
            "solr",
            List.of()
        );
        proxy.start();
        proxyBaseUrl = "http://localhost:" + proxyPort;

        log.info("AuthShimTestFixture started: solr={}, proxy={}", solrBaseUrl, proxyBaseUrl);
    }

    @Override
    public void close() throws Exception {
        if (proxy != null) {
            proxy.stop();
        }
        solr.stop();
    }

    // ---- Unauthenticated HTTP helpers ----

    public String httpGet(String url) throws Exception {
        return httpClient.send(
            HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        ).body();
    }

    public HttpResponse<String> httpGetRaw(String url) throws Exception {
        return httpClient.send(
            HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    public String httpPost(String url, String body) throws Exception {
        var resp = httpClient.send(
            HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        log.info("POST {} → {}", url, resp.statusCode());
        return resp.body();
    }

    // ---- Authenticated Solr HTTP helpers ----

    public String authenticatedSolrGet(String url) throws Exception {
        var authHeader = "Basic " + Base64.getEncoder()
            .encodeToString((SOLR_USER + ":" + SOLR_PASS).getBytes(StandardCharsets.UTF_8));
        return httpClient.send(
            HttpRequest.newBuilder().uri(URI.create(url))
                .header("Authorization", authHeader)
                .GET().build(),
            HttpResponse.BodyHandlers.ofString()
        ).body();
    }

    public String authenticatedSolrPost(String url, String body) throws Exception {
        var authHeader = "Basic " + Base64.getEncoder()
            .encodeToString((SOLR_USER + ":" + SOLR_PASS).getBytes(StandardCharsets.UTF_8));
        var resp = httpClient.send(
            HttpRequest.newBuilder().uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        log.info("Authenticated Solr POST {} → {}", url, resp.statusCode());
        return resp.body();
    }

    // ---- Solr core management ----

    public void createSolrCore(String coreName) throws Exception {
        // Pass credentials via SOLR_AUTH_TYPE and basicauth system property
        // so the Solr CLI can authenticate its internal HTTP calls
        var result = solr.execInContainer(
            "bash", "-c",
            "SOLR_AUTH_TYPE=basic SOLR_AUTHENTICATION_OPTS='-Dbasicauth=" + SOLR_USER + ":" + SOLR_PASS + "' "
            + "solr create_core -c " + coreName
        );
        log.info("create_core {} → exit={}, stdout={}, stderr={}",
            coreName, result.getExitCode(), result.getStdout(), result.getStderr());

        // Poll until core is ready via authenticated ping
        var pingUrl = solrBaseUrl + "/solr/" + coreName + "/admin/ping";
        for (int i = 0; i < 30; i++) {
            try {
                var resp = authenticatedSolrGet(pingUrl);
                if (resp.contains("OK")) break;
            } catch (Exception ignored) {}
            Thread.sleep(500);
        }
    }

    // ---- Internal helpers ----

    private static int findFreePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
