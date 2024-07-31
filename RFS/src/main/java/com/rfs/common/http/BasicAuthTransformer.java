package com.rfs.common.http;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import reactor.core.publisher.Mono;

@AllArgsConstructor
public class BasicAuthTransformer implements RequestTransformer {
    private final String username;
    private final String password;

    @Override
    public Mono<TransformedRequest> transform(String method, String path, Map<String, List<String>> headers, Mono<ByteBuffer> body) {
        Map<String, List<String>> newHeaders = new HashMap<>(headers);
        String credentials = username + ":" + password;
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
        List<String> authHeader = new ArrayList<>();
        authHeader.add("Basic " + encodedCredentials);
        newHeaders.put("Authorization", authHeader);
        return Mono.just(new TransformedRequest(newHeaders, body));
    }
}
