package org.opensearch.migrations.bulkload.common;

import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

public class AzureSnapshotCreatorTest {

    private SnapshotCreator createAzureCreator(String azureUri, Integer maxRate, boolean compress) {
        return createAzureCreator(azureUri, maxRate, compress, null);
    }

    private SnapshotCreator createAzureCreator(String azureUri, Integer maxRate, boolean compress, String endpoint) {
        return new SnapshotCreator(
            "snap", "repo", mock(OpenSearchClient.class),
            RepoUri.parse(azureUri),
            List.of(), null, compress, true,
            null, endpoint, maxRate, null
        );
    }

    @Test
    void GetRequestBodyForRegisterRepo_ExtractsContainerAndPath() {
        var creator = createAzureCreator("azure://myaccount/my-container/base/path", null, false);
        ObjectNode body = creator.getRequestBodyForRegisterRepo();
        ObjectNode settings = (ObjectNode) body.get("settings");

        assertEquals("azure", body.get("type").asText());
        assertEquals("my-container", settings.get("container").asText());
        assertEquals("base/path", settings.get("base_path").asText());

        // repository-azure names its bucket "container", so the s3/gcs "bucket" key must
        // not appear; if it did, the container name would land in a setting the plugin
        // ignores and the repo would silently register against its default container.
        assertNull(settings.get("bucket"));

        // The storage account must not be forwarded. repository-azure's "client" setting
        // falls back to its deprecated "account" setting, and BOTH name a keystore client
        // alias (azure.client.<client>.account), not a storage account. Putting
        // "myaccount" in either field would point the repo at a client that does not exist.
        assertNull(settings.get("account"));
        assertNull(settings.get("client"));
    }

    @Test
    void GetRequestBodyForRegisterRepo_ContainerOnlyNoBasePath() {
        var creator = createAzureCreator("azure://myaccount/my-container", null, false);
        ObjectNode body = creator.getRequestBodyForRegisterRepo();
        ObjectNode settings = (ObjectNode) body.get("settings");

        assertEquals("my-container", settings.get("container").asText());
        assertNull(settings.get("base_path"));
    }

    @Test
    void GetRequestBodyForRegisterRepo_StripsTrailingSlash() {
        var creator = createAzureCreator("azure://myaccount/my-container/path/", null, false);
        ObjectNode body = creator.getRequestBodyForRegisterRepo();
        ObjectNode settings = (ObjectNode) body.get("settings");

        assertEquals("path", settings.get("base_path").asText());
    }

    @Test
    void GetRequestBodyForRegisterRepo_ProducesCorrectJson() {
        var creator = createAzureCreator("azure://myaccount/my-container/snapshots", null, true);
        ObjectNode body = creator.getRequestBodyForRegisterRepo();

        assertEquals("azure", body.get("type").asText());
        ObjectNode settings = (ObjectNode) body.get("settings");
        assertEquals("my-container", settings.get("container").asText());
        assertEquals("snapshots", settings.get("base_path").asText());
        assertEquals(true, settings.get("compress").asBoolean());
        assertNull(settings.get("max_snapshot_bytes_per_sec"));
        // Azure needs no region, and there is no role to assume.
        assertNull(settings.get("region"));
        assertNull(settings.get("role_arn"));
    }

    @Test
    void GetRequestBodyForRegisterRepo_IncludesSnapshotRate() {
        var creator = createAzureCreator("azure://myaccount/my-container/path", 40, false);
        ObjectNode body = creator.getRequestBodyForRegisterRepo();
        ObjectNode settings = (ObjectNode) body.get("settings");

        assertEquals("40mb", settings.get("max_snapshot_bytes_per_sec").asText());
        assertEquals(false, settings.get("compress").asBoolean());
    }

    @Test
    void GetRequestBodyForRegisterRepo_DoesNotForwardEndpoint() {
        // repository-azure has no repository-level "endpoint" setting, exactly like
        // repository-gcs (and unlike repository-s3). Even when an endpoint is supplied on
        // the command line, it must not appear in the repo registration body, where the
        // plugin would silently ignore it. An emulator endpoint reaches the reader side
        // (AzureRepo) only; the writing cluster resolves its own storage endpoint.
        var creator = createAzureCreator("azure://myaccount/my-container/path", null, false,
            "http://azurite:10000");
        ObjectNode body = creator.getRequestBodyForRegisterRepo();
        ObjectNode settings = (ObjectNode) body.get("settings");

        assertNull(settings.get("endpoint"));
    }
}
