package com.rfs.common.http;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import reactor.core.publisher.Mono;

public interface AuthTransformer {
    Mono<TransformedRequest> transform(String method, String path, Map<String, List<String>> headers, Mono<String> body);

    static AuthTransformer basicAuth(String username, String password) {
        return (method, path, headers, body) -> {
            Map<String, List<String>> newHeaders = new HashMap<>(headers);
            String credentials = username + ":" + password;
            String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
            List<String> authHeader = new ArrayList<>();
            authHeader.add("Basic " + encodedCredentials);
            newHeaders.put("Authorization", authHeader);
            return Mono.just(new TransformedRequest(newHeaders, body));
        };
    }

    static AuthTransformer noAuth() {
        return (method, path, headers, body) -> Mono.just(new TransformedRequest(new HashMap<>(headers), body));
    }

    class TransformedRequest {
        public final Map<String, List<String>> headers;
        public final Mono<String> body;

        public TransformedRequest(Map<String, List<String>> headers, Mono<String> body) {
            this.headers = headers;
            this.body = body;
        }
    }
}
