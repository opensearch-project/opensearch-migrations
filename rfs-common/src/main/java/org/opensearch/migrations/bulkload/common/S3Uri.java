package org.opensearch.migrations.bulkload.common;

import lombok.ToString;

@ToString
public class S3Uri {
    public final String bucketName;
    public final String key;
    public final String uri;

    public S3Uri(String rawUri) {
        String[] parts = rawUri.substring(5).split("/", 2);
        bucketName = parts[0];

        String keyRaw = parts.length > 1 ? parts[1] : "";
        if (!keyRaw.isEmpty() && keyRaw.endsWith("/")) {
            keyRaw = keyRaw.substring(0, keyRaw.length() - 1);
        }
        key = keyRaw;

        uri = rawUri.endsWith("/") ? rawUri.substring(0, rawUri.length() - 1) : rawUri;
    }
}
