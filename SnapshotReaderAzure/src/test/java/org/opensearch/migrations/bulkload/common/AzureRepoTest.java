package org.opensearch.migrations.bulkload.common;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AzureRepoTest {

    private static final String REPO_URI = "azure://devstoreaccount1/snapshot-container/repo";

    @Test
    void makeAzureUri_buildsBlobUriRelativeToLocalDir(@TempDir Path localDir) {
        var repo = new AzureRepo(localDir, new AzureUri(REPO_URI), null, null);

        var topLevel = repo.makeAzureUri(localDir.resolve("index-0"));
        assertEquals("azure://devstoreaccount1/snapshot-container/repo/index-0", topLevel.uri);
        assertEquals("devstoreaccount1", topLevel.accountName);
        assertEquals("snapshot-container", topLevel.containerName);
        assertEquals("repo/index-0", topLevel.key);

        var nested = repo.makeAzureUri(localDir.resolve("indices").resolve("idx1").resolve("meta.dat"));
        assertEquals("repo/indices/idx1/meta.dat", nested.key);

        assertThrows(
            IllegalArgumentException.class,
            () -> repo.makeAzureUri(Path.of("/definitely/not/under/localdir/index-0"))
        );

        assertEquals("AzureRepo [uri=" + REPO_URI + "]", repo.toString());
    }
}
