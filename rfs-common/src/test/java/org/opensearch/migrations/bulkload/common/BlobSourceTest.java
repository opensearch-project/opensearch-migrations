package org.opensearch.migrations.bulkload.common;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlobSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void fromLocalFilesystem_readsExistingFile() throws IOException {
        Path file = tempDir.resolve("test.dat");
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        Files.write(file, content);

        BlobSource source = BlobSource.fromLocalFilesystem();
        try (InputStream is = source.readBlob(file)) {
            assertArrayEquals(content, is.readAllBytes());
        }
    }

    @Test
    void fromLocalFilesystem_throwsOnMissingFile() {
        BlobSource source = BlobSource.fromLocalFilesystem();
        assertThrows(SourceRepoAccessor.CouldNotLoadRepoFile.class,
            () -> source.readBlob(tempDir.resolve("nonexistent")));
    }

    @Test
    void customBlobSource_isUsedBySourceRepoAccessor() throws IOException {
        byte[] content = "custom blob".getBytes(StandardCharsets.UTF_8);
        BlobSource custom = path -> new ByteArrayInputStream(content);

        SourceRepo mockRepo = new SourceRepo() {
            @Override public Path getRepoRootDir() { return tempDir; }
            @Override public Path getSnapshotRepoDataFilePath() { return tempDir.resolve("data"); }
            @Override public Path getGlobalMetadataFilePath(String s) { return tempDir.resolve(s); }
            @Override public Path getSnapshotMetadataFilePath(String s) { return tempDir.resolve(s); }
            @Override public Path getIndexMetadataFilePath(String i, String f) { return tempDir.resolve(i); }
            @Override public Path getShardDirPath(String i, int s) { return tempDir.resolve(i); }
            @Override public Path getShardMetadataFilePath(String s, String i, int sh) { return tempDir.resolve(s); }
            @Override public Path getBlobFilePath(String i, int s, String b) { return tempDir.resolve(b); }
        };

        var accessor = new SourceRepoAccessor(mockRepo, custom);
        try (InputStream is = accessor.getSnapshotRepoDataFile()) {
            assertNotNull(is);
            assertArrayEquals(content, is.readAllBytes());
        }
    }
}
