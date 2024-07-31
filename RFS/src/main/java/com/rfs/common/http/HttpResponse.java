package com.rfs.common.http;

import java.util.Map;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class HttpResponse {
    public final int code;
    public final String body;
    public final String message;
    public final Map<String, String> headers;
}
