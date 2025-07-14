package org.opensearch.migrations.cli;

import java.nio.file.Path;

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

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.opensearch.migrations.matchers.HasLineCount.hasLineCount;

public class ClustersTest {
    @Test
    void testAsString_empty() {
        var clusters = Clusters.builder().build();

        var result = clusters.asCliOutput();

        assertThat(result, containsString("Clusters:"));
        assertThat(result, not(containsString("Source:")));
        assertThat(result, not(containsString("Target:")));
        assertThat(result, hasLineCount(1));
    }

    @Test
    void testAsString_withS3Source() {
        var clusters = Clusters.builder()
            .source(mockS3SnapshotReaderOS78())
            .build();

        var result = clusters.asCliOutput();

        assertThat(result, containsString("Clusters:"));
        assertThat(result, containsString("Source:"));
        assertThat(result, containsString("Type: MockS3SnapshotReader (OPENSEARCH 78.0.0)"));
        assertThat(result, containsString("S3 repository: s3://s3.aws.com/repo"));
        assertThat(result, not(containsString("Target:")));
        assertThat(result, hasLineCount(4));
    }

    @Test
    void testAsString_withLocalSource() {
        var clusters = Clusters.builder()
            .source(mockLocalSnapshotReaderOS87())
            .build();

        var result = clusters.asCliOutput();

        assertThat(result, containsString("Clusters:"));
        assertThat(result, containsString("Source:"));
        assertThat(result, containsString("Type: MockSnapshotReader (OPENSEARCH 87.0.0)"));
        assertThat(result, containsString("Local repository: /tmp/snapshots"));
        assertThat(result, not(containsString("Target:")));
        assertThat(result, hasLineCount(4));
    }


    @Test
    void testAsString_withRemoteSourceAndTarget() {
        var clusters = Clusters.builder()
            .source(mockRemoteReaderOS54())
            .target(mockRemoteWriterOS2000())
            .build();

        var result = clusters.asCliOutput();

        assertThat(result, containsString("Clusters:"));
        assertThat(result, containsString("Source:"));
        assertThat(result, containsString("Source:"));
        assertThat(result, containsString("Type: MockRemoteReader (OPENSEARCH 54.0.0)"));
        assertThat(result, containsString("Uri: http://remote.source"));
        assertThat(result, containsString("TLS Verification: Disabled"));
        assertThat(result, containsString("Target:"));
        assertThat(result, containsString("Type: MockClusterWriter (OPENSEARCH 2000.0.0)"));
        assertThat(result, containsString("Uri: http://remote.target"));
        assertThat(result, containsString("TLS Verification: Enabled"));
        assertThat(result, hasLineCount(12));
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
        targetArgs.insecure = false;
        when(clusterReader.getConnection()).thenReturn(targetArgs.toConnectionContext());
        return clusterReader;
    }

    private ClusterWriter mockRemoteWriterOS2000() {
        var clusterWriter = mock(RemoteWriter_OS_2_11.class);
        when(clusterWriter.getFriendlyTypeName()).thenReturn("MockClusterWriter");
        when(clusterWriter.getVersion()).thenReturn(Version.fromString("OS 2000"));
        var targetArgs = new ConnectionContext.TargetArgs();
        targetArgs.host = "http://remote.target";
        targetArgs.insecure = true;
        when(clusterWriter.getConnection()).thenReturn(targetArgs.toConnectionContext());
        return clusterWriter;
    }
}
