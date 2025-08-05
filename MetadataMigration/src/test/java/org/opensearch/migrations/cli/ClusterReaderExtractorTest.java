package org.opensearch.migrations.cli;

import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.cluster.ClusterReader;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;


public class ClusterReaderExtractorTest {
    @Test
    void testExtractClusterReader_noSnapshotOrRemote() {
        var args = new MigrateOrEvaluateArgs();
        var extractor = new ClusterReaderExtractor(args);

        var exception = assertThrows(ParameterException.class, () -> extractor.extractClusterReader());
        assertThat(args.toString(), exception.getMessage(), equalTo("No details on the source cluster found, please supply a connection details or a snapshot"));
    }

    @Test
    void testExtractClusterReader_invalidS3Snapshot_missingRegion() {
        var args = new MigrateOrEvaluateArgs();
        args.s3RepoUri = "foo.bar";
        args.s3LocalDirPath = "fizz.buzz";
        var extractor = new ClusterReaderExtractor(args);

        var exception = assertThrows(ParameterException.class, () -> extractor.extractClusterReader());
        assertThat(exception.getMessage(), equalTo("If an s3 repo is being used, s3-region and s3-local-dir-path must be set"));
    }

    @Test
    void testExtractClusterReader_invalidS3Snapshot_missingLocalDirPath() {
        var args = new MigrateOrEvaluateArgs();
        args.s3RepoUri = "foo.bar";
        args.s3Region = "us-west-1";
        var extractor = new ClusterReaderExtractor(args);

        var exception = assertThrows(ParameterException.class, () -> extractor.extractClusterReader());
        assertThat(exception.getMessage(), equalTo("If an s3 repo is being used, s3-region and s3-local-dir-path must be set"));
    }

    @Test
    void testExtractClusterReader_validLocalSnapshot_missingVersion() {
        var args = new MigrateOrEvaluateArgs();
        args.fileSystemRepoPath = "foo.bar";
        var extractor = spy(new ClusterReaderExtractor(args));

        var exception = assertThrows(ParameterException.class, () -> extractor.extractClusterReader());
        assertThat(exception.getMessage(), equalTo("Unable to read from snapshot without --source-version parameter"));
    }

    @Test
    void testExtractClusterReader_validLocalSnapshot() {
        var args = new MigrateOrEvaluateArgs();
        args.fileSystemRepoPath = "foo.bar";
        args.sourceVersion = Version.fromString("OS 1.1.1");
        var extractor = spy(new ClusterReaderExtractor(args));
        var mockReader = mock(ClusterReader.class);
        doReturn(mockReader).when(extractor).getSnapshotReader(eq(args.sourceVersion), any(FileSystemRepo.class));

        var result = extractor.extractClusterReader();
        assertThat(result, equalTo(mockReader));

        verify(extractor).getSnapshotReader(eq(args.sourceVersion), any(FileSystemRepo.class));
    }

    @Test
    void testExtractClusterReader_validS3Snapshot() {
        var args = new MigrateOrEvaluateArgs();
        args.s3RepoUri = "foo.bar";
        args.s3Region = "us-west-1";
        args.s3LocalDirPath = "fizz.buzz";
        args.sourceVersion = Version.fromString("OS 9.9.9");
        args.versionStrictness.allowLooseVersionMatches = true;
        var extractor = spy(new ClusterReaderExtractor(args));
        var mockReader = mock(ClusterReader.class);
        doReturn(mockReader).when(extractor).getSnapshotReader(eq(args.sourceVersion), any(S3Repo.class));

        var result = extractor.extractClusterReader();
        assertThat(result, equalTo(mockReader));

        verify(extractor).getSnapshotReader(eq(args.sourceVersion), any(S3Repo.class));
    }

    @Test
    void testExtractClusterReader_validRemote() {
        var args = new MigrateOrEvaluateArgs();
        args.sourceArgs.host = "http://foo.bar";
        var extractor = spy(new ClusterReaderExtractor(args));
        var mockReader = mock(ClusterReader.class);
        doReturn(mockReader).when(extractor).getRemoteReader(any());

        var result = extractor.extractClusterReader();
        assertThat(result, equalTo(mockReader));

        var foundContext = ArgumentCaptor.forClass(ConnectionContext.class);
        verify(extractor).getRemoteReader(foundContext.capture());
        assertThat(args.sourceArgs.toConnectionContext(), equalTo(foundContext.getValue()));
    }
}
