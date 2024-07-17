package org.opensearch.migrations.transform;

import java.util.List;
import java.util.Map;

public interface IHttpMessage {
    String APPLICATION_JSON = "application/json";
    String CONTENT_TYPE = "content-type";

    String method();

    String path();

    String protocol();

    Map<String, Object> headersMap();

    default String getFirstHeader(String key) {
        var all = getAllMatchingHeaders(key);
        return all == null ? null : all.get(0);
    }

    default List<String> getAllMatchingHeaders(String key) {
        return ((List<String>) (headersMap().get(key)));
    }
}
