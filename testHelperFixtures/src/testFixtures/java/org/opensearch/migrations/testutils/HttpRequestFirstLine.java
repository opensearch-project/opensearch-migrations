package org.opensearch.migrations.testutils;

import java.net.URI;

public interface HttpRequestFirstLine {
    String verb();

    URI path();

    String version();
}
