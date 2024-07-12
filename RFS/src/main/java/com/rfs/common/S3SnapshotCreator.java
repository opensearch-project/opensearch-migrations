package com.rfs.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class S3SnapshotCreator extends SnapshotCreator {
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String s3Uri;
    private final String s3Region;
    private final Integer maxSnapshotRateMBPerNode;

    public S3SnapshotCreator(String snapshotName, OpenSearchClient client, String s3Uri, String s3Region) {
        this(snapshotName, client, s3Uri, s3Region, null);
    }

    public S3SnapshotCreator(
        String snapshotName,
        OpenSearchClient client,
        String s3Uri,
        String s3Region,
        Integer maxSnapshotRateMBPerNode
    ) {
        super(snapshotName, client);
        this.s3Uri = s3Uri;
        this.s3Region = s3Region;
        this.maxSnapshotRateMBPerNode = maxSnapshotRateMBPerNode;
    }

    @Override
    public ObjectNode getRequestBodyForRegisterRepo() {
        // Assemble the request body
        ObjectNode settings = mapper.createObjectNode();
        settings.put("bucket", getBucketName());
        settings.put("region", s3Region);
        settings.put("base_path", getBasePath());
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
