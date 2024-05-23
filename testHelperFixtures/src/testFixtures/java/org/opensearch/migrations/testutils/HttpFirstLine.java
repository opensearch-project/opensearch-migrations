package org.opensearch.migrations.testutils;

import java.net.URI;

public interface HttpFirstLine {
    String verb();
    URI path();
    String version();
}
