package com.rfs.common.http;

import java.util.Map;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class HttpResponse {
    public final int statusCode;
    public final String statusText;
    public final Map<String, String> headers;
    public final String body;
}
