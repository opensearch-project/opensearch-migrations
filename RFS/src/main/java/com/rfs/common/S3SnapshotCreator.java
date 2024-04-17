package com.rfs.common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class S3SnapshotCreator extends SnapshotCreator {
    private static final Logger logger = LogManager.getLogger(S3SnapshotCreator.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ConnectionDetails connectionDetails;
    private final String snapshotName;
    private final String s3Uri;
    private final String s3Region;

    public S3SnapshotCreator(String snapshotName, ConnectionDetails connectionDetails, String s3Uri, String s3Region) {
        super(snapshotName, connectionDetails);
        this.snapshotName = snapshotName;
        this.connectionDetails = connectionDetails;
        this.s3Uri = s3Uri;
        this.s3Region = s3Region;
    }

    @Override
    public ObjectNode getRequestBodyForRegisterRepo() {
        // Assemble the request body
        ObjectNode settings = mapper.createObjectNode();
        settings.put("bucket", getBucketName());
        settings.put("region", s3Region);
        settings.put("base_path", getBasePath());

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
