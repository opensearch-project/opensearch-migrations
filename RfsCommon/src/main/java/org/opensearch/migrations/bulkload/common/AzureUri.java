package org.opensearch.migrations.bulkload.common;

import lombok.ToString;

@ToString
public class AzureUri {
    public final String accountName;
    public final String containerName;
    public final String key;
    public final String uri;

    public AzureUri(String rawUri) {
        if (rawUri == null || !rawUri.startsWith("azure://")) {
            throw new IllegalArgumentException("Azure URI must start with 'azure://': " + rawUri);
        }

        // Three-way split: the scheme carries both the storage account and the container
        // before the key, where gs:// and s3:// carry only a bucket.
        String[] parts = rawUri.substring("azure://".length()).split("/", 3);
        if (parts[0].isEmpty()) {
            throw new IllegalArgumentException("Azure URI must contain a storage account name: " + rawUri);
        }
        accountName = parts[0];

        if (parts.length < 2 || parts[1].isEmpty()) {
            throw new IllegalArgumentException("Azure URI must contain a container name: " + rawUri);
        }
        containerName = parts[1];

        String keyRaw = parts.length > 2 ? parts[2] : "";
        if (!keyRaw.isEmpty() && keyRaw.endsWith("/")) {
            keyRaw = keyRaw.substring(0, keyRaw.length() - 1);
        }
        key = keyRaw;

        uri = rawUri.endsWith("/") ? rawUri.substring(0, rawUri.length() - 1) : rawUri;
    }
}
