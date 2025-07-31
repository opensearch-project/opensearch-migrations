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
import org.opensearch.migrations.utils.JsonUtils;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.text.StringContainsInOrder.stringContainsInOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.opensearch.migrations.matchers.HasLineCount.hasLineCount;

public class ClustersTest {
    /**
     * Tests both string and JSON output formats for each test case to make comparisons clearer
     * and assertions more uniform across formats.
     */
    
    @Test
    @DisplayName("Empty Clusters - Both Output Formats")
    void testEmpty() throws Exception {
        var clusters = Clusters.builder().build();
        
        // Test String Output
        var stringOutput = clusters.asCliOutput();
        
        // Test JSON Output
        String jsonOutput = clusters.asJsonOutput();
        JsonNode jsonNode = JsonUtils.getObjectMapper().readTree(jsonOutput);
        
        // Assertions grouped by format type
        // String output assertions
        assertThat(stringOutput, containsString("Clusters:"));
        assertThat(stringOutput, not(containsString("Source:")));
        assertThat(stringOutput, not(containsString("Target:")));
        assertThat(stringOutput, hasLineCount(1));
        
        // JSON output assertions
        assertThat(jsonNode, is(notNullValue()));
        assertThat(jsonNode.isObject(), is(true));
        assertEquals(0, jsonNode.size());
    }

    @Test
    @DisplayName("Clusters with S3 Source - Both Output Formats")
    void testWithS3Source() throws Exception {
        var clusters = Clusters.builder()
            .source(mockS3SnapshotReaderOS78())
            .build();

        // Test String Output
        var stringOutput = clusters.asCliOutput();
        
        // Test JSON Output
        String jsonOutput = clusters.asJsonOutput();
        JsonNode jsonNode = JsonUtils.getObjectMapper().readTree(jsonOutput);
        JsonNode source = jsonNode.get("source");
        
        // String output assertions
        assertThat(stringOutput, containsString("Clusters:"));
        assertThat(stringOutput, containsString("Source:"));
        assertThat(stringOutput, containsString("Type: MockS3SnapshotReader (OPENSEARCH 78.0.0)"));
        assertThat(stringOutput, containsString("S3 repository: s3://s3.aws.com/repo"));
        assertThat(stringOutput, not(containsString("Target:")));
        assertThat(stringOutput, hasLineCount(4));
        
        // JSON output assertions
        assertNotNull(jsonNode.get("source"), "JSON should contain source element");
        assertEquals("MockS3SnapshotReader", source.get("type").asText());
        assertEquals("OPENSEARCH 78.0.0", source.get("version").asText());
        assertEquals("s3://s3.aws.com/repo", source.get("s3Repository").asText());
    }

    @Test
    @DisplayName("Clusters with Local Source - Both Output Formats")
    void testWithLocalSource() throws Exception {
        var clusters = Clusters.builder()
            .source(mockLocalSnapshotReaderOS87())
            .build();

        // Test String Output
        var stringOutput = clusters.asCliOutput();
        
        // Test JSON Output
        String jsonOutput = clusters.asJsonOutput();
        JsonNode jsonNode = JsonUtils.getObjectMapper().readTree(jsonOutput);
        JsonNode source = jsonNode.get("source");
        
        // String output assertions
        assertThat(stringOutput, containsString("Clusters:"));
        assertThat(stringOutput, containsString("Source:"));
        assertThat(stringOutput, containsString("Type: MockSnapshotReader (OPENSEARCH 87.0.0)"));
        assertThat(stringOutput, containsString("Local repository: /tmp/snapshots"));
        assertThat(stringOutput, not(containsString("Target:")));
        assertThat(stringOutput, hasLineCount(4));
        
        // JSON output assertions
        assertNotNull(jsonNode.get("source"), "JSON should contain source element");
        assertEquals("MockSnapshotReader", source.get("type").asText());
        assertEquals("OPENSEARCH 87.0.0", source.get("version").asText());
        assertTrue(source.get("localRepository").asText().endsWith("/tmp/snapshots"), 
               "Path should end with /tmp/snapshots but was: " + source.get("localRepository").asText());
    }

    @Test
    @DisplayName("Clusters with Remote Source and Target - Both Output Formats")
    void testWithRemoteSourceAndTarget() throws Exception {
        var clusters = Clusters.builder()
            .source(mockRemoteReaderOS54())
            .target(mockRemoteWriterOS2000())
            .build();

        // Test String Output
        var stringOutput = clusters.asCliOutput();
        
        // Test JSON Output
        String jsonOutput = clusters.asJsonOutput();
        JsonNode jsonNode = JsonUtils.getObjectMapper().readTree(jsonOutput);
        JsonNode source = jsonNode.get("source");
        JsonNode target = jsonNode.get("target");
        
        // String output assertions
        assertThat(stringOutput, containsString("Clusters:"));
        assertThat(stringOutput, containsString("Source:"));
        assertThat(stringOutput, stringContainsInOrder("Type: MockRemoteReader (OPENSEARCH 54.0.0)",
                                                 "Uri: http://remote.source",
                                                 "TLS Verification: Disabled"));
        assertThat(stringOutput, containsString("Target:"));
        assertThat(stringOutput, stringContainsInOrder("Type: MockClusterWriter (OPENSEARCH 2000.0.0)",
                                                 "Uri: http://remote.target",
                                                 "TLS Verification: Enabled"));
        assertThat(stringOutput, hasLineCount(12));
        
        // JSON output assertions - Source
        assertNotNull(jsonNode.get("source"), "JSON should contain source element");
        assertEquals("MockRemoteReader", source.get("type").asText());
        assertEquals("OPENSEARCH 54.0.0", source.get("version").asText());
        assertEquals("http://remote.source", source.get("Uri").asText());
        assertEquals("Disabled", source.get("TLS Verification").asText());
        
        // JSON output assertions - Target
        assertNotNull(jsonNode.get("target"), "JSON should contain target element");
        assertEquals("MockClusterWriter", target.get("type").asText());
        assertEquals("OPENSEARCH 2000.0.0", target.get("version").asText());
        assertEquals("http://remote.target", target.get("Uri").asText());
        assertEquals("Enabled", target.get("TLS Verification").asText());
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
