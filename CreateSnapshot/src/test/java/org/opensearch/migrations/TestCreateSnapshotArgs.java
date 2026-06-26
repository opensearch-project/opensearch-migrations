package org.opensearch.migrations;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TestCreateSnapshotArgs {

    @Test
    void noRepoUri_throwsParameterException() {
        var ex = assertThrows(ParameterException.class, () ->
            CreateSnapshot.main(new String[]{
                "--snapshot-name", "snap",
                "--snapshot-repo-name", "repo",
                "--source-host", "http://localhost:9200"
            })
        );
        assertThat(ex.getMessage(), containsString("required"));
    }

    @Test
    void s3RepoWithoutRegion_throwsParameterException() {
        var ex = assertThrows(ParameterException.class, () ->
            CreateSnapshot.main(new String[]{
                "--snapshot-name", "snap",
                "--snapshot-repo-name", "repo",
                "--source-host", "http://localhost:9200",
                "--repo-uri", "s3://bucket/path"
            })
        );
        assertThat(ex.getMessage(), containsString("s3-region"));
    }

    @Test
    void unrecognizedScheme_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
            CreateSnapshot.main(new String[]{
                "--snapshot-name", "snap",
                "--snapshot-repo-name", "repo",
                "--source-host", "http://localhost:9200",
                "--repo-uri", "ftp://invalid/path"
            })
        );
    }

    @Test
    void s3AliasStillWorks() {
        var ex = assertThrows(ParameterException.class, () ->
            CreateSnapshot.main(new String[]{
                "--snapshot-name", "snap",
                "--snapshot-repo-name", "repo",
                "--source-host", "http://localhost:9200",
                "--s3-repo-uri", "s3://bucket/path"
            })
        );
        assertThat(ex.getMessage(), containsString("s3-region"));
    }

    @Test
    void fileSystemAliasStillWorks() throws Exception {
        var ex = assertThrows(Exception.class, () ->
            CreateSnapshot.main(new String[]{
                "--snapshot-name", "snap",
                "--snapshot-repo-name", "repo",
                "--source-host", "http://localhost:9200",
                "--file-system-repo-path", "/tmp/repo"
            })
        );
        // Should get past arg parsing (no ParameterException) and fail on connection
        assert !(ex instanceof ParameterException);
    }

    @Test
    void solrSourceWithGcsRepo_throwsParameterException() {
        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = "http://localhost:8983";
        args.sourceArgs.insecure = true;
        args.sourceType = "solr";
        args.snapshotName = "snap";
        args.snapshotRepoName = "repo";
        args.repoUri = "gs://bucket/path";

        var strategy = new SolrBackupStrategy(args);
        // Guard must fire before any Solr network call, so this fails fast rather
        // than discovering collections against a (non-existent) Solr at localhost.
        var ex = assertThrows(ParameterException.class, strategy::run);
        assertThat(ex.getMessage(), containsString("gs://"));
    }
}
