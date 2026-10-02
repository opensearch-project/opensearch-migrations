package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * {@link FailedDocumentStreamObjectStore} over one S3 bucket.
 *
 * <p>Synchronous: the source streams a partition's objects one at a time from
 * {@code boundedElastic}, so there is nothing for an async client to overlap.
 */
@Slf4j
public class S3FailedDocumentStreamObjectStore implements FailedDocumentStreamObjectStore {

    /** Returned when a conditional write loses the race. */
    private static final int PRECONDITION_FAILED = 412;

    private final S3Client client;
    private final String bucket;
    private final boolean ownsClient;

    public S3FailedDocumentStreamObjectStore(S3Client client, String bucket) {
        this(client, bucket, false);
    }

    private S3FailedDocumentStreamObjectStore(S3Client client, String bucket, boolean ownsClient) {
        this.client = client;
        this.bucket = bucket;
        this.ownsClient = ownsClient;
    }

    /** Builds a store and the client behind it. */
    public static S3FailedDocumentStreamObjectStore create(String bucket, String region, String endpoint) {
        var builder = S3Client.builder();
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region));
        }
        if (endpoint != null && !endpoint.isBlank()) {
            var endpointUri = endpoint.contains("://") ? endpoint : "http://" + endpoint;
            builder.endpointOverride(URI.create(endpointUri));
            // Local S3 stand-ins cannot do vhost addressing.
            builder.forcePathStyle(true);
        }
        return new S3FailedDocumentStreamObjectStore(builder.build(), bucket, true);
    }

    @Override
    public List<String> listKeys(String prefix) throws IOException {
        var keys = new ArrayList<String>();
        try {
            var paginator = client.listObjectsV2Paginator(
                ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build());
            paginator.contents().forEach(object -> keys.add(object.key()));
        } catch (SdkException e) {
            throw new IOException("Could not list s3://" + bucket + "/" + prefix, e);
        }
        // Already lexicographic; sorting makes it our guarantee, not S3's detail.
        keys.sort(String::compareTo);
        return keys;
    }

    @Override
    public InputStream open(String key) throws IOException {
        try {
            return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException e) {
            throw new IOException("Could not read s3://" + bucket + "/" + key, e);
        }
    }

    @Override
    public Optional<byte[]> read(String key) throws IOException {
        try {
            return Optional.of(
                client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (SdkException e) {
            throw new IOException("Could not read s3://" + bucket + "/" + key, e);
        }
    }

    @Override
    public boolean putIfAbsent(String key, byte[] body) throws IOException {
        try {
            client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).ifNoneMatch("*").build(),
                RequestBody.fromBytes(body));
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == PRECONDITION_FAILED) {
                log.atDebug().setMessage("s3://{}/{} already existed; another writer won the seal race")
                    .addArgument(bucket).addArgument(key).log();
                return false;
            }
            throw new IOException("Could not write s3://" + bucket + "/" + key, e);
        } catch (SdkException e) {
            throw new IOException("Could not write s3://" + bucket + "/" + key, e);
        }
    }

    @Override
    public void close() {
        if (ownsClient) {
            client.close();
        }
    }
}
