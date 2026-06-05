package org.opensearch.migrations;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TestCreateSnapshotArgs {

    @Test
    void noRepoArgs_throwsParameterException() {
        var ex = assertThrows(ParameterException.class, () ->
            CreateSnapshot.main(new String[]{
                "--snapshot-name", "snap",
                "--snapshot-repo-name", "repo",
                "--source-host", "http://localhost:9200"
            })
        );
        assertThat(ex.getMessage(), containsString("must be set"));
    }

    @Test
    void bothS3AndGcs_throwsParameterException() {
        var ex = assertThrows(ParameterException.class, () ->
            CreateSnapshot.main(new String[]{
                "--snapshot-name", "snap",
                "--snapshot-repo-name", "repo",
                "--source-host", "http://localhost:9200",
                "--s3-repo-uri", "s3://bucket/path",
                "--s3-region", "us-east-1",
                "--gcs-repo-uri", "gs://bucket/path"
            })
        );
        assertThat(ex.getMessage(), containsString("Only one"));
    }

    @Test
    void bothFsAndGcs_throwsParameterException() {
        var ex = assertThrows(ParameterException.class, () ->
            CreateSnapshot.main(new String[]{
                "--snapshot-name", "snap",
                "--snapshot-repo-name", "repo",
                "--source-host", "http://localhost:9200",
                "--file-system-repo-path", "/tmp/repo",
                "--gcs-repo-uri", "gs://bucket/path"
            })
        );
        assertThat(ex.getMessage(), containsString("Only one"));
    }
}
