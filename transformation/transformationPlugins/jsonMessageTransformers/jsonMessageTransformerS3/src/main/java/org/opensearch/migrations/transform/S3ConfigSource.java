package org.opensearch.migrations.transform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * {@link RemoteConfigSource} implementation that downloads files from S3.
 * Uses the default AWS credential chain (IAM role in EKS, env vars, etc.).
 */
@Slf4j
public class S3ConfigSource implements RemoteConfigSource {
    private static final String S3_PREFIX = "s3://";

    @Override
    public boolean canHandle(String uri) {
        return uri != null && uri.startsWith(S3_PREFIX);
    }

    @Override
    public Path download(String uri, String suffix) throws IOException {
        var withoutPrefix = uri.substring(S3_PREFIX.length());
        var slashIndex = withoutPrefix.indexOf('/');
        if (slashIndex <= 0) {
            throw new IllegalArgumentException("Invalid S3 URI: " + uri);
        }
        var bucket = withoutPrefix.substring(0, slashIndex);
        var key = withoutPrefix.substring(slashIndex + 1);

        log.atInfo().setMessage("Downloading {} from S3").addArgument(uri).log();
        var tempFile = Files.createTempFile("transformer-s3-", suffix);
        try (var s3Client = S3Client.create()) {
            s3Client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build(),
                tempFile
            );
        }
        log.atInfo().setMessage("Downloaded {} to {}").addArgument(uri).addArgument(tempFile).log();
        return tempFile;
    }
}
