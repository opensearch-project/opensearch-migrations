package org.opensearch.migrations.bulkload.common.http;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.ToString;

@AllArgsConstructor
@ToString
public class HttpResponse {
    public final int statusCode;
    public final String statusText;
    public final Map<String, String> headers;
    public final String body;
}
