package org.opensearch.migrations.bulkload.common;

import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

public class GcsSnapshotCreatorTest {

    @Test
    void GetBucketName_ExtractsBucketFromUri() {
        var creator = new GcsSnapshotCreator(
            "snap", "repo", mock(OpenSearchClient.class),
            "gs://my-bucket/base/path",
            List.of(), null
        );
        assertEquals("my-bucket", creator.getBucketName());
    }

    @Test
    void GetBasePath_ExtractsNestedPath() {
        var creator = new GcsSnapshotCreator(
            "snap", "repo", mock(OpenSearchClient.class),
            "gs://my-bucket/base/path",
            List.of(), null
        );
        assertEquals("base/path", creator.getBasePath());
    }

    @Test
    void GetBasePath_ReturnsNullForBucketOnly() {
        var creator = new GcsSnapshotCreator(
            "snap", "repo", mock(OpenSearchClient.class),
            "gs://my-bucket",
            List.of(), null
        );
        assertNull(creator.getBasePath());
    }

    @Test
    void GetBasePath_StripsTrailingSlash() {
        var creator = new GcsSnapshotCreator(
            "snap", "repo", mock(OpenSearchClient.class),
            "gs://my-bucket/path/",
            List.of(), null
        );
        assertEquals("path", creator.getBasePath());
    }

    @Test
    void GetRequestBodyForRegisterRepo_ProducesCorrectJson() {
        var creator = new GcsSnapshotCreator(
            "snap", "repo", mock(OpenSearchClient.class),
            "gs://my-bucket/snapshots",
            List.of(), null, null, true, true
        );

        ObjectNode body = creator.getRequestBodyForRegisterRepo();

        assertEquals("gcs", body.get("type").asText());
        ObjectNode settings = (ObjectNode) body.get("settings");
        assertEquals("my-bucket", settings.get("bucket").asText());
        assertEquals("snapshots", settings.get("base_path").asText());
        assertEquals(true, settings.get("compress").asBoolean());
        assertNull(settings.get("max_snapshot_bytes_per_sec"));
    }

    @Test
    void GetRequestBodyForRegisterRepo_IncludesSnapshotRate() {
        var creator = new GcsSnapshotCreator(
            "snap", "repo", mock(OpenSearchClient.class),
            "gs://my-bucket/path",
            List.of(), 40, null, false, true
        );

        ObjectNode body = creator.getRequestBodyForRegisterRepo();
        ObjectNode settings = (ObjectNode) body.get("settings");

        assertEquals("40mb", settings.get("max_snapshot_bytes_per_sec").asText());
        assertEquals(false, settings.get("compress").asBoolean());
    }
}
