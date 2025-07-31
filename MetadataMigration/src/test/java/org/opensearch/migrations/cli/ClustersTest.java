package org.opensearch.migrations.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.opensearch.migrations.matchers.HasLineCount.hasLineCount;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.text.StringContainsInOrder.stringContainsInOrder;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.common.S3Uri;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.version_os_2_11.RemoteWriter_OS_2_11;
import org.opensearch.migrations.bulkload.version_universal.RemoteReader;
import org.opensearch.migrations.cluster.ClusterReader;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;
import org.opensearch.migrations.cluster.ClusterWriter;

public class ClustersTest {
    @Test
    void testOutput_Empty() {
        var clusters = new Clusters(null, null);
        var strResult = clusters.asCliOutput();
        assertThat(strResult, containsString("Clusters:"));
        assertThat(strResult, not(containsString("Source:")));
        assertThat(strResult, not(containsString("Target:")));
        assertThat(strResult, hasLineCount(1));
  
        var jsonNode = clusters.asJsonOutput();
        assertNotNull(jsonNode);
    }

    @Test
    void testOutput_withS3Source() {
        var clusters = Clusters.builder()
            .source(mockS3SnapshotReaderOS78())
            .build();

        var strResult = clusters.asCliOutput();
        assertThat(strResult, containsString("Clusters:"));
        assertThat(strResult, containsString("Source:"));
        assertThat(strResult, containsString("Type: MockS3SnapshotReader (OPENSEARCH 78.0.0)"));
        assertThat(strResult, containsString("S3 repository: s3://s3.aws.com/repo"));
        assertThat(strResult, not(containsString("Target:")));
        assertThat(strResult, hasLineCount(4));

        var jsonNode = clusters.asJsonOutput();
        assertTrue(jsonNode.has("source"));
        assertTrue(jsonNode.has("target"));
        assertEquals("TestSource", jsonNode.get("source").get("type").asText());
        assertEquals("TestTarget", jsonNode.get("target").get("type").asText());
        assertEquals("1.0", jsonNode.get("source").get("version").asText());
        assertEquals("2.0", jsonNode.get("target").get("version").asText());

    }

    @Test
    void testOutput_withLocalSource() {
        var clusters = Clusters.builder()
            .source(mockLocalSnapshotReaderOS87())
            .build();

        var strResult = clusters.asCliOutput();
        assertThat(strResult, containsString("Clusters:"));
        assertThat(strResult, containsString("Source:"));
        assertThat(strResult, containsString("Type: MockSnapshotReader (OPENSEARCH 87.0.0)"));
        assertThat(strResult, containsString("Local repository: /tmp/snapshots"));
        assertThat(strResult, not(containsString("Target:")));
        assertThat(strResult, hasLineCount(4));

        var jsonNode = clusters.asJsonOutput();
        assertTrue(jsonNode.has("source"));
        assertEquals("SnapshotReader", jsonNode.get("source").get("type").asText());
        assertEquals("1.0", jsonNode.get("source").get("version").asText());
        assertEquals("s3://test-bucket", jsonNode.get("source").get("s3Repository").asText());
    }


    @Test
    void testAsString_withRemoteSourceAndTarget() {
        var clusters = Clusters.builder()
            .source(mockRemoteReaderOS54())
            .target(mockRemoteWriterOS2000())
            .build();

        var strResult = clusters.asCliOutput();
        assertThat(strResult, containsString("Clusters:"));
        assertThat(strResult, containsString("Source:"));
        assertThat(strResult, containsString("Source:"));
        assertThat(strResult, stringContainsInOrder("Type: MockRemoteReader (OPENSEARCH 54.0.0)",
                                                 "Uri: http://remote.source",
                                                 "TLS Verification: Disabled"));
        assertThat(strResult, containsString("Target:"));
        assertThat(strResult, stringContainsInOrder("Type: MockClusterWriter (OPENSEARCH 2000.0.0)",
                                                 "Uri: http://remote.target",
                                                 "TLS Verification: Enabled"));
        assertThat(strResult, hasLineCount(12));

        var jsonNode = clusters.asJsonOutput();
        assertTrue(jsonNode.has("source"));
        assertTrue(jsonNode.has("target"));
        assertEquals("source-host", jsonNode.get("source").get("host").asText());
        assertEquals("9200", jsonNode.get("source").get("port").asText());
        assertEquals("target-host", jsonNode.get("target").get("host").asText());
        assertEquals("9201", jsonNode.get("target").get("port").asText());
    }


    private ClusterReader mockS3SnapshotReaderOS78() {
        var clusterReader = mock(ClusterSnapshotReader.class);
        when(clusterReader.getFriendlyTypeName()).thenReturn("MockS3SnapshotReader");
        when(clusterReader.getVersion()).thenReturn(Version.fromString("OS 78.0"));
        var s3Repo = mock(S3Repo.class);
        when(s3Repo.getS3RepoUri()).thenReturn(new S3Uri("s3://s3.aws.com/repo"));
        when(clusterReader.getSourceRepo()).thenReturn(s3Repo);
        return clusterReader;
    }

    private ClusterReader mockLocalSnapshotReaderOS87() {
        var clusterReader = mock(ClusterSnapshotReader.class);
        when(clusterReader.getFriendlyTypeName()).thenReturn("MockSnapshotReader");
        when(clusterReader.getVersion()).thenReturn(Version.fromString("OS 87.0"));
        var fileRepo = mock(FileSystemRepo.class);
        when(fileRepo.getRepoRootDir()).thenReturn(Path.of("/tmp/snapshots"));
        when(clusterReader.getSourceRepo()).thenReturn(fileRepo);
        return clusterReader;
    }

    private ClusterReader mockRemoteReaderOS54() {
        var clusterReader = mock(RemoteReader.class);
        when(clusterReader.getFriendlyTypeName()).thenReturn("MockRemoteReader");
        when(clusterReader.getVersion()).thenReturn(Version.fromString("OS 54.0"));
        var targetArgs = new ConnectionContext.SourceArgs();
        targetArgs.host = "http://remote.source";
        targetArgs.insecure = true;
        when(clusterReader.getConnection()).thenReturn(targetArgs.toConnectionContext());
        return clusterReader;
    }

    private ClusterWriter mockRemoteWriterOS2000() {
        var clusterWriter = mock(RemoteWriter_OS_2_11.class);
        when(clusterWriter.getFriendlyTypeName()).thenReturn("MockClusterWriter");
        when(clusterWriter.getVersion()).thenReturn(Version.fromString("OS 2000"));
        var targetArgs = new ConnectionContext.TargetArgs();
        targetArgs.host = "http://remote.target";
        when(clusterWriter.getConnection()).thenReturn(targetArgs.toConnectionContext());
        return clusterWriter;
    }
}
