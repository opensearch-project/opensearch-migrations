package org.opensearch.migrations.replay.datahandlers.http;

public interface IHttpMessageMetadata {
    String method();

    String uri();

    String protocol();

    ListKeyAdaptingCaseInsensitiveHeadersMap headers();
}
