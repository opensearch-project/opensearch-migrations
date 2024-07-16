package org.opensearch.migrations.testutils;

import java.util.Map;

import lombok.AllArgsConstructor;

/**
 * Basic components of an HTTP response.
 */
@AllArgsConstructor
public class SimpleHttpResponse {
    /**
     * In cases where there are duplicate names, only one value will be chosen.
     */
    public final Map<String, String> headers;
    public final byte[] payloadBytes;
    public final String statusText;
    public final int statusCode;
}
