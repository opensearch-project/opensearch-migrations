package org.opensearch.migrations.trafficcapture.netty;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import lombok.NonNull;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class PassThruHttpHeaders extends DefaultHttpHeaders {

    /**
     * Use the HttpHeaders class because it does case insensitive matches.
     */
    private final HttpHeaders mapWithCaseInsensitiveHeaders;

    public static class HttpHeadersToPreserve {
        private final HttpHeaders caseInsensitiveHeadersMap;
        public HttpHeadersToPreserve(String... extraHeaderNames) {
            caseInsensitiveHeadersMap = new DefaultHttpHeaders();
            Stream.concat(Stream.of(HttpHeaderNames.CONTENT_LENGTH.toString(),
                                    HttpHeaderNames.CONTENT_TRANSFER_ENCODING.toString(),
                                    HttpHeaderNames.TRAILER.toString()),
                            Arrays.stream(extraHeaderNames))
                    .forEach(h->caseInsensitiveHeadersMap.add(h, ""));
        }
    }

    public PassThruHttpHeaders(@NonNull HttpHeadersToPreserve headersToPreserve) {
        this.mapWithCaseInsensitiveHeaders = headersToPreserve.caseInsensitiveHeadersMap;
    }

    private boolean headerNameShouldBeTracked(CharSequence name) {
        return mapWithCaseInsensitiveHeaders.contains(name);
    }

    @Override
    public HttpHeaders add(CharSequence name, Object value) {
        if (headerNameShouldBeTracked(name)) {
            return super.add(name, value);
        }
        return this;
    }

    @Override
    public HttpHeaders add(String name, Object value) {
        if (headerNameShouldBeTracked(name)) {
            return super.add(name, value);
        }
        return this;
    }

    @Override
    public HttpHeaders add(String name, Iterable<?> values) {
        if (headerNameShouldBeTracked(name)) {
            return super.add(name, values);
        }
        return null;
    }

    @Override
    public HttpHeaders addInt(CharSequence name, int value) {
        if (headerNameShouldBeTracked(name)) {
            return super.addInt(name, value);
        }
        return null;
    }

    @Override
    public HttpHeaders addShort(CharSequence name, short value) {
        if (headerNameShouldBeTracked(name)) {
            return super.addShort(name, value);
        }
        return null;
    }

    @Override
    public HttpHeaders set(String name, Object value) {
        if (headerNameShouldBeTracked(name)) {
            return super.set(name, value);
        }
        return null;
    }

    @Override
    public HttpHeaders set(String name, Iterable<?> values) {
        if (headerNameShouldBeTracked(name)) {
            return super.set(name, values);
        }
        return null;
    }

    @Override
    public HttpHeaders setInt(CharSequence name, int value) {
        if (headerNameShouldBeTracked(name)) {
            return super.setInt(name, value);
        }
        return null;
    }

    @Override
    public HttpHeaders setShort(CharSequence name, short value) {
        if (headerNameShouldBeTracked(name)) {
            return super.setShort(name, value);
        }
        return null;
    }
}
