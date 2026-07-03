package org.opensearch.migrations.bulkload.common;

public sealed interface RepoUri permits RepoUri.FileRepoUri, RepoUri.S3RepoUri, RepoUri.GcsRepoUri {

    static RepoUri parse(String uri) {
        if (uri.startsWith("file://")) {
            return new FileRepoUri(uri.substring("file://".length()), uri);
        }
        if (uri.startsWith("s3://")) {
            return new S3RepoUri(new S3Uri(uri), uri);
        }
        if (uri.startsWith("gs://")) {
            return new GcsRepoUri(uri);
        }
        if (uri.startsWith("/")) {
            return new FileRepoUri(uri, "file://" + uri);
        }
        throw new IllegalArgumentException(
            "Unrecognized repo URI scheme: '" + uri + "'. Expected file://, s3://, gs://, or an absolute path."
        );
    }

    String rawUri();

    record FileRepoUri(String path, String rawUri) implements RepoUri {}
    record S3RepoUri(S3Uri s3Uri, String rawUri) implements RepoUri {}
    record GcsRepoUri(String rawUri) implements RepoUri {}
}
