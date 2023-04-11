package org.opensearch.migrations.trafficcapture.netty;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;

public class PassThruHttpHeaders extends DefaultHttpHeaders {

    private static boolean headerNameShouldBeTracked(CharSequence name) {
        return name.equals(Names.CONTENT_LENGTH);
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
