package org.opensearch.migrations.bulkload.common;

import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

public class GcsSnapshotCreatorTest {

    private SnapshotCreator createGcsCreator(String gcsUri, Integer maxRate, boolean compress) {
        return createGcsCreator(gcsUri, maxRate, compress, null);
    }

    private SnapshotCreator createGcsCreator(String gcsUri, Integer maxRate, boolean compress, String endpoint) {
        return new SnapshotCreator(
            "snap", "repo", mock(OpenSearchClient.class),
            RepoUri.parse(gcsUri),
            List.of(), null, compress, true,
            null, endpoint, maxRate, null
        );
    }

    @Test
    void GetRequestBodyForRegisterRepo_ExtractsBucketAndPath() {
        var creator = createGcsCreator("gs://my-bucket/base/path", null, false);
        ObjectNode body = creator.getRequestBodyForRegisterRepo();
        ObjectNode settings = (ObjectNode) body.get("settings");

        assertEquals("gcs", body.get("type").asText());
        assertEquals("my-bucket", settings.get("bucket").asText());
        assertEquals("base/path", settings.get("base_path").asText());
    }

    @Test
    void GetRequestBodyForRegisterRepo_BucketOnlyNoBasePath() {
        var creator = createGcsCreator("gs://my-bucket", null, false);
        ObjectNode body = creator.getRequestBodyForRegisterRepo();
        ObjectNode settings = (ObjectNode) body.get("settings");

        assertEquals("my-bucket", settings.get("bucket").asText());
        assertNull(settings.get("base_path"));
    }

    @Test
    void GetRequestBodyForRegisterRepo_StripsTrailingSlash() {
        var creator = createGcsCreator("gs://my-bucket/path/", null, false);
        ObjectNode body = creator.getRequestBodyForRegisterRepo();
        ObjectNode settings = (ObjectNode) body.get("settings");

        assertEquals("path", settings.get("base_path").asText());
    }

    @Test
    void GetRequestBodyForRegisterRepo_ProducesCorrectJson() {
        var creator = createGcsCreator("gs://my-bucket/snapshots", null, true);
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
        var creator = createGcsCreator("gs://my-bucket/path", 40, false);
        ObjectNode body = creator.getRequestBodyForRegisterRepo();
        ObjectNode settings = (ObjectNode) body.get("settings");

        assertEquals("40mb", settings.get("max_snapshot_bytes_per_sec").asText());
        assertEquals(false, settings.get("compress").asBoolean());
    }

    @Test
    void GetRequestBodyForRegisterRepo_DoesNotForwardEndpoint() {
        // repository-gcs has no repository-level "endpoint" setting (unlike
        // repository-s3); a custom endpoint must be configured cluster-side via
        // gcs.client.<client>.endpoint. Even when an endpoint is supplied, it must
        // not appear in the repo registration body, where it would be ignored.
        var creator = createGcsCreator("gs://my-bucket/path", null, false,
            "http://fake-gcs-server:4443");
        ObjectNode body = creator.getRequestBodyForRegisterRepo();
        ObjectNode settings = (ObjectNode) body.get("settings");

        assertNull(settings.get("endpoint"));
    }
}
