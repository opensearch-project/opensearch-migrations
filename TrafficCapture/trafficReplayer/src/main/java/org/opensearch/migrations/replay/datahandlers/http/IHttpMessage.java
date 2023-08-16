package org.opensearch.migrations.replay.datahandlers.http;

import java.util.List;
import java.util.Map;

public interface IHttpMessage {
    String method();

    String path();

    String protocol();

    Map<String,Object> headersMap();

    default String getFirstHeader(String key) {
        return getAllMatchingHeaders(key).get(0);
    }

    List<String> getAllMatchingHeaders(String key);
}
