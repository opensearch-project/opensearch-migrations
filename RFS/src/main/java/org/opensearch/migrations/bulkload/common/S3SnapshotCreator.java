package org.opensearch.migrations.bulkload.common;

import java.util.List;

import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class S3SnapshotCreator extends SnapshotCreator {
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String s3Uri;
    private final String s3Region;
    private final String s3Endpoint;
    private final Integer maxSnapshotRateMBPerNode;
    private final String snapshotRoleArn;

    public S3SnapshotCreator(
        String snapshotName,
        String snapshotRepoName,
        OpenSearchClient client,
        String s3Uri,
        String s3Region,
        List<String> indexAllowlist,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        this(snapshotName, snapshotRepoName, client, s3Uri, s3Region, null, indexAllowlist, null, null, context, false, true);
    }

    public S3SnapshotCreator(
        String snapshotName,
        String snapshotRepoName,
        OpenSearchClient client,
        String s3Uri,
        String s3Region,
        String s3Endpoint,
        List<String> indexAllowlist,
        Integer maxSnapshotRateMBPerNode,
        String snapshotRoleArn,
        IRfsContexts.ICreateSnapshotContext context,
        boolean compressionEnabled,
        boolean includeGlobalState
    ) {
        super(snapshotName, snapshotRepoName, indexAllowlist, client, context, compressionEnabled, includeGlobalState);
        this.s3Uri = s3Uri;
        this.s3Region = s3Region;
        this.s3Endpoint = s3Endpoint;
        this.maxSnapshotRateMBPerNode = maxSnapshotRateMBPerNode;
        this.snapshotRoleArn = snapshotRoleArn;
    }

    @Override
    public ObjectNode getRequestBodyForRegisterRepo() {
        // Assemble the request body
        ObjectNode settings = mapper.createObjectNode();
        settings.put("bucket", getBucketName());
        settings.put("region", s3Region);
        settings.put("base_path", getBasePath());
        settings.put("compress", compressionEnabled);
        if (snapshotRoleArn != null) {
            settings.put("role_arn", snapshotRoleArn);
        }

        if (s3Endpoint != null) {
            settings.put("endpoint", s3Endpoint);
        }

        if (maxSnapshotRateMBPerNode != null) {
            settings.put("max_snapshot_bytes_per_sec", maxSnapshotRateMBPerNode + "mb");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("type", "s3");
        body.set("settings", settings);
        return body;
    }

    /*
     * Extracts the bucket name from the S3 URI
     * s3://my-bucket-name/my-folder/my-nested-folder => my-bucket-name
     */
    public String getBucketName() {
        return s3Uri.split("/")[2];
    }

    /*
     * Extracts the base path from the S3 URI; could be nested arbitrarily deep
     * s3://my-bucket-name/my-folder/my-nested-folder => my-folder/my-nested-folder
     */
    public String getBasePath() {
        int thirdSlashIndex = s3Uri.indexOf('/', 5);
        if (thirdSlashIndex == -1) {
            // Nothing after the bucket name
            return null;
        }

        // Extract everything after the third "/", excluding any final "/"
        String rawBasePath = s3Uri.substring(thirdSlashIndex + 1);
        if (rawBasePath.endsWith("/")) {
            return rawBasePath.substring(0, rawBasePath.length() - 1);
        } else {
            return rawBasePath;
        }
    }

}
