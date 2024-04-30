package com.rfs.common;

import java.util.Base64;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.ByteBufMono;

public class RestClient {
    private static final Logger logger = LogManager.getLogger(RestClient.class);

    public static class Response {
        public final int code;
        public final String body;
        public final String message;

        public Response(int responseCode, String responseBody, String responseMessage) {
            this.code = responseCode;
            this.body = responseBody;
            this.message = responseMessage;
        }
    }

    public final ConnectionDetails connectionDetails;
    private final HttpClient client;

    public RestClient(ConnectionDetails connectionDetails) {
        this.connectionDetails = connectionDetails;

        this.client = HttpClient.create()
            .baseUrl(connectionDetails.url)
            .headers(h -> {
                h.add("Content-Type", "application/json");
                if (connectionDetails.authType == ConnectionDetails.AuthType.BASIC) {
                    String credentials = connectionDetails.username + ":" + connectionDetails.password;
                    String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
                    h.add("Authorization", "Basic " + encodedCredentials);
                }
            });
    }

    public Mono<Response> getAsync(String path, boolean quietLogging) {
        return client.get()
            .uri("/" + path)
            .responseSingle((response, bytes) -> bytes.asString()
                .map(body -> new Response(response.status().code(), body, response.status().reasonPhrase()))
                .doOnNext(resp -> logResponse(resp, quietLogging)));
    }

    public Response get(String path, boolean quietLogging) {
        return getAsync(path, quietLogging).block();
    }

    public Mono<Response> postAsync(String path, String body) {
        return client.post()
            .uri("/" + path)
            .send(ByteBufMono.fromString(Mono.just(body)))
            .responseSingle((response, bytes) -> bytes.asString()
                .map(b -> new Response(response.status().code(), b, response.status().reasonPhrase())));
    }

    public Mono<Response> putAsync(String path, String body, boolean quietLogging) {
        return client.put()
            .uri("/" + path)
            .send(ByteBufMono.fromString(Mono.just(body)))
            .responseSingle((response, bytes) -> bytes.asString()
                .map(b -> new Response(response.status().code(), b, response.status().reasonPhrase()))
                .doOnNext(resp -> logResponse(resp, quietLogging)));
    }

    public Response put(String path, String body, boolean quietLogging) {
        return putAsync(path, body, quietLogging).block();
    }

    private void logResponse(Response response, boolean quietLogging) {
        if (quietLogging || (response.code == 200 || response.code == 201)) {
            logger.debug("Response Code: " + response.code + ", Response Message: " + response.message + ", Response Body: " + response.body);
        } else {
            logger.error("Response Code: " + response.code + ", Response Message: " + response.message + ", Response Body: " + response.body);
        }
    }
}