package org.opensearch.migrations.bulkload.version_es_2_4;

import java.nio.file.Files;
import java.util.List;

import org.opensearch.migrations.bulkload.common.SourceRepo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SnapshotRepoProvider_ES_2_4Test {

    @Test
    void indexListIsReadOnceAndCachedPerSnapshot(@TempDir java.nio.file.Path tempDir) throws Exception {
        var snapshotFile = tempDir.resolve("snap-snapshot");
        Files.write(snapshotFile, compressedSmileSnapshot());
        var repo = mock(SourceRepo.class);
        when(repo.getSnapshotMetadataFilePath("snapshot")).thenReturn(snapshotFile);
        var provider = new SnapshotRepoProvider_ES_2_4(repo);

        var first = provider.getIndicesInSnapshot("snapshot");
        var second = provider.getIndicesInSnapshot("snapshot");

        assertEquals(List.of("index-one", "index-two"), first.stream().map(i -> i.getName()).toList());
        assertSame(first, second);
        verify(repo, times(1)).getSnapshotMetadataFilePath("snapshot");
    }

    private static byte[] compressedSmileSnapshot() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(ElasticsearchConstants_ES_2_4.SMILE_FACTORY);
        var root = mapper.createObjectNode();
        root.putObject("snapshot").putArray("indices").add("index-one").add("index-two");
        var smile = mapper.writeValueAsBytes(root);
        var output = new java.io.ByteArrayOutputStream();
        output.write(new byte[] { 'D', 'F', 'L', 0 });
        try (var deflater = new java.util.zip.DeflaterOutputStream(
            output,
            new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        )) {
            deflater.write(smile);
        }
        return output.toByteArray();
    }
}
