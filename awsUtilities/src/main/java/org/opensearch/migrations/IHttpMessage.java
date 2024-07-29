package org.opensearch.migrations;

import java.util.List;
import java.util.Map;

public interface IHttpMessage {
    String method();

    String path();

    String protocol();

    Map<String, List<String>> headers();

    default String getFirstHeader(String key) {
        var all = getAllMatchingHeaders(key);
        return all == null ? null : all.get(0);
    }
    default List<String> getAllMatchingHeaders(String key) {
        return headers().get(key);
    }
}
