package org.opensearch.migrations.bulkload.pipeline.spi;

/**
 * Source credentials, held apart from the spec because a spec is written into workflow configuration
 * and these arrive from a Kubernetes Secret.
 *
 * @param azureStorageAccountKey storage account key for an {@code azure://} repo, else null
 * @param azureStorageSasToken   SAS token for an {@code azure://} repo, else null
 */
public record SourceSecrets(String azureStorageAccountKey, String azureStorageSasToken) {

    public static final SourceSecrets NONE = new SourceSecrets(null, null);

    @Override
    public String toString() {
        return "SourceSecrets[redacted]";
    }
}
