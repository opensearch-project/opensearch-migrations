package com.rfs.cms;

import java.io.IOException;
import java.net.URI;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.classic.methods.HttpOptions;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;

import lombok.Getter;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ApacheHttpClient implements AbstractedHttpClient {
    private final CloseableHttpClient client = HttpClients.createDefault();
    private final URI baseUri;

    public ApacheHttpClient(URI baseUri) {
        this.baseUri = baseUri;
    }

    private static HttpUriRequestBase makeRequestBase(URI baseUri, String method, String path) {
        switch (method.toUpperCase()) {
            case "GET":
                return new HttpGet(baseUri + "/" + path);
            case AbstractedHttpClient.POST_METHOD:
                return new HttpPost(baseUri + "/" + path);
            case AbstractedHttpClient.PUT_METHOD:
                return new HttpPut(baseUri + "/" + path);
            case "PATCH":
                return new HttpPatch(baseUri + "/" + path);
            case "HEAD":
                return new HttpHead(baseUri + "/" + path);
            case "OPTIONS":
                return new HttpOptions(baseUri + "/" + path);
            case "DELETE":
                return new HttpDelete(baseUri + "/" + path);
            default:
                throw new IllegalArgumentException("Cannot map method to an Apache Http Client request: " + method);
        }
    }

    @Override
    public AbstractHttpResponse makeRequest(String method, String path, Map<String, String> headers, String payload)
        throws IOException {
        var request = makeRequestBase(baseUri, method, path);
        headers.entrySet().forEach(kvp -> request.setHeader(kvp.getKey(), kvp.getValue()));
        if (payload != null) {
            request.setEntity(new StringEntity(payload));
        }
        return client.execute(request, fr -> new AbstractHttpResponse() {
            @Getter
            final byte[] payloadBytes = Optional.ofNullable(fr.getEntity()).map(e -> {
                try {
                    return e.getContent().readAllBytes();
                } catch (IOException ex) {
                    throw Lombok.sneakyThrow(ex);
                }
            }).orElse(null);

            @Override
            public String getStatusText() {
                return fr.getReasonPhrase();
            }

            @Override
            public int getStatusCode() {
                return fr.getCode();
            }

            @Override
            public Stream<Map.Entry<String, String>> getHeaders() {
                return Arrays.stream(fr.getHeaders())
                    .map(h -> new AbstractMap.SimpleEntry<>(h.getName(), h.getValue()));
            }
        });
    }

    @Override
    public void close() throws Exception {
        client.close();
    }
}
