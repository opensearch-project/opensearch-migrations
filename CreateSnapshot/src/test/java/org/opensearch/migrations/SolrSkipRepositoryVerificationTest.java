package org.opensearch.migrations;

import java.nio.file.Path;
import java.util.List;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SolrSkipRepositoryVerificationTest {

    @Test
    void skipRepositoryVerification_isRejectedForSolrSources(@TempDir Path repoRoot) {
        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = "http://127.0.0.1:1";
        args.sourceType = "solr";
        args.snapshotName = "snap";
        args.snapshotRepoName = "test";
        args.repoUri = "file://" + repoRoot;
        args.solrCollections = List.of("dummy");
        args.skipRepositoryVerification = true;

        var ex = assertThrows(ParameterException.class, () -> new SolrBackupStrategy(args));

        assertThat(ex.getMessage(), containsString("--skip-repository-verification"));
    }
}
