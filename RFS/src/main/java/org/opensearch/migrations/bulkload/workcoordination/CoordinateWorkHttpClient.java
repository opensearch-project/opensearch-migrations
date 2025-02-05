package org.opensearch.migrations.bulkload.workcoordination;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;

import io.netty.handler.codec.http.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Slf4j
public class CoordinateWorkHttpClient implements AbstractedHttpClient {

    private final RestClient restClient;

    private static final int DEFAULT_MAX_RETRY_ATTEMPTS = 3;
    private static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(10);
    public static final Retry COORDINATION_RETRY_STRATEGY =
        Retry.backoff(DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_BACKOFF)
            .maxBackoff(DEFAULT_MAX_BACKOFF);

    @Getter
    @AllArgsConstructor
    public static class Response implements AbstractedHttpClient.AbstractHttpResponse {
        int statusCode;
        String statusText;
        List<Map.Entry<String, String>> headersList;
        byte[] payloadBytes;

        @Override
        public Stream<Map.Entry<String, String>> getHeaders() {
            return headersList.stream();
        }

        @Override
        public byte[] getPayloadBytes() {
            return payloadBytes;
        }
    }

    public CoordinateWorkHttpClient(ConnectionContext connectionContext) {
        this.restClient = new RestClient(connectionContext);
    }

    @Override
    public AbstractHttpResponse makeRequest(String method, String path, Map<String, String> headers, String payload) {
        HttpMethod httpMethod = HttpMethod.valueOf(method);
        var response = restClient.asyncRequestWithFlatHeaderValues(httpMethod, path, payload, headers, null)
            .flatMap(resp -> {
                if (resp.statusCode == HttpURLConnection.HTTP_NOT_FOUND ||
                    resp.statusCode == HttpURLConnection.HTTP_OK ||
                    resp.statusCode == HttpURLConnection.HTTP_NO_CONTENT ||
                    resp.statusCode == HttpURLConnection.HTTP_CREATED ||
                    resp.statusCode == HttpURLConnection.HTTP_PARTIAL)
                {
                    return Mono.just(resp);
                } else {
                    String errorMessage = "Could not make coordinator call: " + path + ". " + getString(resp);
                    return Mono.error(new OpenSearchClient.OperationFailed(errorMessage, resp));
                }
            })
            .doOnError(e -> log.warn(e.getMessage()))
            .retryWhen(COORDINATION_RETRY_STRATEGY)
            .doOnError(e -> log.atError().setMessage("Failed all retries with exception for worker coordination.")
                .setCause(e)
                .log())
            .block();
        assert response != null;
        return new Response(
            response.statusCode,
            response.statusText,
            new ArrayList<>(response.headers.entrySet()),
            response.body != null ? response.body.getBytes(StandardCharsets.UTF_8) : null
        );
    }

    private static String getString(HttpResponse resp) {
        return "Response Code: "
            + resp.statusCode
            + ", Response Message: "
            + resp.statusText
            + ", Response Body: "
            + resp.body;
    }
}
