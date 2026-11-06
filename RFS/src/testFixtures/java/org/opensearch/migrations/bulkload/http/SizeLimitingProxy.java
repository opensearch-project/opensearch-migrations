package org.opensearch.migrations.bulkload.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Records real HTTP bodies, enforces a limit independently of the client, and can lose an ACK after commit. */
public final class SizeLimitingProxy implements AutoCloseable {
    public record Request(String method, String path, byte[] wireBody, byte[] body) {
        public boolean completesDocument() {
            return path.contains("/_update/") && new String(body, StandardCharsets.UTF_8).contains("\"chunks\":");
        }
    }
    public record Response(int status, byte[] body) {}
    public record CompletedRequest(Request request, Response response) {}

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10)).build();
    private final URI target;
    private final long limit;
    public final List<Request> requests = new CopyOnWriteArrayList<>();
    public final List<CompletedRequest> completedRequests = new CopyOnWriteArrayList<>();
    public final AtomicBoolean loseNextUpdateAcknowledgement = new AtomicBoolean();
    public final AtomicBoolean rejectCompletion = new AtomicBoolean();
    public final AtomicReference<Throwable> failure = new AtomicReference<>();
    public volatile Consumer<Request> beforeForward = request -> {};
    public volatile Function<Request, Response> intercept = request -> null;
    /** Return null to lose an acknowledgement AFTER the real target handled the request. */
    public volatile BiFunction<Request, Response, Response> afterForward = (request, response) -> response;

    public SizeLimitingProxy(String target, long limit) throws IOException {
        this.target = URI.create(target);
        this.limit = limit;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            byte[] wire = exchange.getRequestBody().readAllBytes();
            byte[] body = wire;
            if ("gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(wire))) {
                    body = gzip.readAllBytes();
                }
            }
            var request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(), wire, body);
            requests.add(request);
            if (body.length > limit || wire.length > limit) {
                respond(exchange, 413, "{\"error\":\"request too large\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (request.completesDocument() && rejectCompletion.get()) {
                respond(exchange, 503, "{\"error\":\"completion unavailable\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            beforeForward.accept(request);
            var intercepted = intercept.apply(request);
            if (intercepted != null) {
                respond(exchange, intercepted.status(), intercepted.body());
                return;
            }
            var forwarded = HttpRequest.newBuilder(target.resolve(exchange.getRequestURI()))
                .timeout(Duration.ofSeconds(60))
                .method(request.method(), HttpRequest.BodyPublishers.ofByteArray(wire));
            for (var header : List.of("Content-Type", "Content-Encoding", "Accept-Encoding", "X-Opaque-Id")) {
                var value = exchange.getRequestHeaders().getFirst(header);
                if (value != null) {
                    forwarded.header(header, value);
                }
            }
            var forwardedResponse = client.send(forwarded.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] responseBody = forwardedResponse.body();
            if ("gzip".equals(forwardedResponse.headers().firstValue("Content-Encoding").orElse(null))) {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(responseBody))) {
                    responseBody = gzip.readAllBytes();
                }
            }
            var response = new Response(forwardedResponse.statusCode(), responseBody);
            completedRequests.add(new CompletedRequest(request, response));
            if (request.path().contains("/_update/") && response.status() == 200
                && loseNextUpdateAcknowledgement.compareAndSet(true, false)) {
                // The actual cluster applied this update. Close without sending the response.
                return;
            }
            response = afterForward.apply(request, response);
            if (response != null) {
                respond(exchange, response.status(), response.body());
            }
        } catch (Exception e) {
            failure.compareAndSet(null, e);
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        client.close();
    }
}
