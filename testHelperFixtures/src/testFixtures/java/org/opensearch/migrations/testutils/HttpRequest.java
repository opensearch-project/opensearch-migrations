package org.opensearch.migrations.testutils;

import java.net.URI;
import java.util.List;
import java.util.Map;

public interface HttpRequest {
    String getVerb();

    URI getPath();

    String getVersion();

    List<? extends Map.Entry<String,String>> getHeaders();
}
