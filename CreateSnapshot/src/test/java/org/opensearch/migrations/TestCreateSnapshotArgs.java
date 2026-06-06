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
}
