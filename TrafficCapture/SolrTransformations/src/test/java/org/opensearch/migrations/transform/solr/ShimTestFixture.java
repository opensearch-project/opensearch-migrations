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
import java.time.Duration;
import java.util.List;

import org.opensearch.migrations.transform.shim.TransformationLibrary.TransformationPair;
import org.opensearch.migrations.transform.shim.TransformationShimProxy;
import org.opensearch.testcontainers.OpensearchContainer;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Test fixture that manages Solr + OpenSearch containers and a TransformationShimProxy.
 * <p>
 * Usage:
 * <pre>{@code
 * try (var fixture = new ShimTestFixture("mirror.gcr.io/library/solr:8", "mirror.gcr.io/opensearchproject/opensearch:3.3.0", transforms)) {
 *     fixture.start();
 *     // fixture.getSolrBaseUrl(), fixture.getOpenSearchBaseUrl(), fixture.getProxyBaseUrl()
 *     // fixture.httpGet(...), fixture.httpPost(...), fixture.httpPut(...)
 * }
 * }</pre>
 */
@Slf4j
public class ShimTestFixture implements AutoCloseable {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    private final GenericContainer<?> solr;
    private final OpensearchContainer<?> opensearch;
    private final TransformationPair transforms;
    private TransformationShimProxy proxy;

    @Getter private String solrBaseUrl;
    @Getter private String openSearchBaseUrl;
    @Getter private String proxyBaseUrl;

    public ShimTestFixture(String solrImage, String opensearchImage, TransformationPair transforms) {
        this.solr = createSolrContainer(solrImage);
        this.opensearch = createOpenSearchContainer(opensearchImage);
        this.transforms = transforms;
    }

    /** Start containers and proxy. */
    public void start() throws Exception {
        start(List.of());
    }

    /** Start containers and proxy, installing the given Solr plugins first. */
    public void start(List<String> plugins) throws Exception {
        solr.start();
        opensearch.start();

        if (plugins != null) {
            for (var plugin : plugins) {
                installSolrPlugin(plugin);
            }
        }

        solrBaseUrl = "http://" + solr.getHost() + ":" + solr.getMappedPort(8983);
        openSearchBaseUrl = "http://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200);

        int proxyPort = findFreePort();
        proxy = new TransformationShimProxy(
            proxyPort, URI.create(openSearchBaseUrl),
            transforms.request(), transforms.response());
        proxy.start();
        proxyBaseUrl = "http://localhost:" + proxyPort;
    }

    /** Install a Solr plugin by name (e.g. "analysis-icu"). */
    private void installSolrPlugin(String pluginName) throws Exception {
        var result = solr.execInContainer("bin/solr", "plugin", "install", pluginName);
        log.info("install plugin {} → exit={}, stdout={}", pluginName, result.getExitCode(), result.getStdout());
        if (result.getExitCode() != 0) {
            log.warn("Plugin install stderr: {}", result.getStderr());
        }
    }

    /** Create a Solr core and wait for it to be ready. */
    public void createSolrCore(String coreName) throws Exception {
        var result = solr.execInContainer("solr", "create_core", "-c", coreName);
        log.info("create_core {} → exit={}, stdout={}", coreName, result.getExitCode(), result.getStdout());
        // Poll until core is ready instead of sleeping
        var coreStatusUrl = solrBaseUrl + "/solr/" + coreName + "/admin/ping";
        for (int i = 0; i < 30; i++) {
            try {
                var resp = HTTP.send(
                    HttpRequest.newBuilder().uri(URI.create(coreStatusUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) return;
            } catch (Exception ignored) {}
            Thread.sleep(500);
        }
        throw new RuntimeException("Solr core '" + coreName + "' not ready after 15s");
    }

    public String httpGet(String url) throws Exception {
        return HTTP.send(
            HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        ).body();
    }

    public String httpPost(String url, String body) throws Exception {
        var resp = HTTP.send(
            HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        log.info("POST {} → {}", url, resp.statusCode());
        return resp.body();
    }

    public String httpPut(String url, String body) throws Exception {
        var resp = HTTP.send(
            HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        log.info("PUT {} → {}", url, resp.statusCode());
        return resp.body();
    }

    public String httpDelete(String url) throws Exception {
        var resp = HTTP.send(
            HttpRequest.newBuilder().uri(URI.create(url)).DELETE().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        log.info("DELETE {} → {}", url, resp.statusCode());
        return resp.body();
    }

    public int httpHead(String url) throws Exception {
        var resp = HTTP.send(
            HttpRequest.newBuilder().uri(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding()
        );
        log.info("HEAD {} → {}", url, resp.statusCode());
        return resp.statusCode();
    }

    @Override
    public void close() throws Exception {
        if (proxy != null) proxy.stop();
        solr.stop();
        opensearch.stop();
    }

    @SuppressWarnings("resource")
    private static GenericContainer<?> createSolrContainer(String image) {
        var imageName = DockerImageName.parse(image);
        if (!image.startsWith("solr")) {
            imageName = imageName.asCompatibleSubstituteFor("solr");
        }
        return new GenericContainer<>(imageName)
            .withExposedPorts(8983)
            .waitingFor(Wait.forHttp("/solr/admin/info/system")
                .forPort(8983).forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    @SuppressWarnings("resource")
    private static OpensearchContainer<?> createOpenSearchContainer(String image) {
        var imageName = DockerImageName.parse(image);
        if (!image.startsWith("opensearchproject/opensearch")) {
            imageName = imageName.asCompatibleSubstituteFor("opensearchproject/opensearch");
        }
        return new OpensearchContainer<>(imageName)
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", "Admin123!")
            .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(2)));
    }

    private static int findFreePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
