package org.opensearch.migrations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.testutils.CloseableLogSetup;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.everyItem;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Container-free tests for {@code --solr-topology}: what supplying it (or not) actually does to a
 * run. The Solr host below is deliberately unroutable, so any test that completes proves no request
 * to Solr was made.
 */
public class SolrTopologyOptionTest {

    /** Refuses instantly rather than hanging, so "no request was made" stays a fast assertion. */
    private static final String UNREACHABLE_SOLR = "http://127.0.0.1:1";
    private static final String SNAPSHOT_NAME = "snap";

    /** A backup carrying neither a cloud nor a standalone marker. */
    private static Path ambiguousBackup(Path repoRoot) throws Exception {
        var indexDir = repoRoot.resolve(SNAPSHOT_NAME).resolve("index");
        Files.createDirectories(indexDir);
        Files.writeString(indexDir.resolve("segments_1"), "not a real index");
        return repoRoot;
    }

    private static CreateSnapshot.Args argsFor(Path repoRoot, String mode, String topology) {
        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = UNREACHABLE_SOLR;
        args.sourceArgs.insecure = true;
        args.sourceType = "solr";
        args.snapshotName = SNAPSHOT_NAME;
        args.snapshotRepoName = "test";
        args.repoUri = "file://" + repoRoot;
        args.mode = mode;
        // Supplied explicitly so nothing needs to be discovered from the source.
        args.solrCollections = List.of("dummy");
        args.solrTopology = topology;
        return args;
    }

    @Test
    void ambiguousImportWithoutFlag_failsAskingForTheFlag(@TempDir Path repoRoot) throws Exception {
        var args = argsFor(ambiguousBackup(repoRoot), "import", null);

        var ex = assertThrows(ParameterException.class, () -> new SolrBackupStrategy(args).run());

        assertThat(ex.getMessage(), containsString("--solr-topology"));
        assertThat(ex.getMessage(), containsString("standalone"));
    }

    /**
     * The whole point of the flag: an IMPORT the artifact cannot classify still runs, and does so
     * without asking Solr anything (the host is unroutable, so a request would fail the test).
     */
    @Test
    void explicitCloudTopology_importsWithoutQueryingSolr(@TempDir Path repoRoot) throws Exception {
        var args = argsFor(ambiguousBackup(repoRoot), "import", "cloud");

        assertDoesNotThrow(() -> new SolrBackupStrategy(args).run());
    }

    /** CREATE with an explicit standalone topology must not attempt the Collections API BACKUP. */
    @Test
    void explicitStandaloneTopology_skipsTheCloudBackupAttempt(@TempDir Path repoRoot) throws Exception {
        var args = argsFor(ambiguousBackup(repoRoot), "create", "standalone");

        try (var logs = new CloseableLogSetup(SolrBackupStrategy.class.getName())) {
            // The backup itself cannot succeed against an unroutable host; only the branch matters.
            assertThrows(Exception.class, () -> new SolrBackupStrategy(args).run());

            assertThat(logs.getLogEvents(), everyItem(not(containsString("Detected SolrCloud"))));
            assertThat(logs.getLogEvents().toString(), containsString("Detected standalone Solr"));
        }
    }

    /** Conversely, an explicit cloud topology must not be second-guessed into the standalone path. */
    @Test
    void explicitCloudTopology_usesTheCloudBackupPath(@TempDir Path repoRoot) throws Exception {
        var args = argsFor(ambiguousBackup(repoRoot), "create", "cloud");

        try (var logs = new CloseableLogSetup(SolrBackupStrategy.class.getName())) {
            assertThrows(Exception.class, () -> new SolrBackupStrategy(args).run());

            assertThat(logs.getLogEvents().toString(), containsString("Detected SolrCloud"));
            assertThat(logs.getLogEvents(), everyItem(not(containsString("Detected standalone"))));
        }
    }

    /** A cloud backup classifies from its own markers and then makes no request at all. */
    @Test
    void cloudMarkersInBackup_inferCloudWithoutQueryingSolr(@TempDir Path repoRoot) throws Exception {
        var snapshot = repoRoot.resolve(SNAPSHOT_NAME).resolve("movies");
        Files.createDirectories(snapshot);
        Files.writeString(snapshot.resolve("backup.properties"), "collection=movies");

        var args = argsFor(repoRoot, "import", null);

        assertDoesNotThrow(() -> new SolrBackupStrategy(args).run());
    }

    /** Discovery failures against an unreachable source must surface, not be swallowed. */
    @Test
    void unreachableSourceDuringDiscovery_reportsTheTopologyFailure(@TempDir Path repoRoot) throws Exception {
        var args = argsFor(ambiguousBackup(repoRoot), "import", "cloud");
        args.solrCollections = List.of();

        assertThrows(SolrBackupStrategy.SolrTopologyDetectionException.class,
            () -> new SolrBackupStrategy(args).run());
    }

    /** The standalone ladder reaches Core Admin, whose transport failure is wrapped for the user. */
    @Test
    void unreachableSourceDuringCoreDiscovery_isWrappedAsParameterError(@TempDir Path repoRoot) throws Exception {
        var args = argsFor(ambiguousBackup(repoRoot), "import", "standalone");
        args.solrCollections = List.of();

        var ex = assertThrows(ParameterException.class, () -> new SolrBackupStrategy(args).run());
        assertThat(ex.getMessage(), containsString("discover Solr collections/cores"));
    }

    @Test
    void invalidTopologyValue_isRejectedBeforeAnythingRuns(@TempDir Path repoRoot) throws Exception {
        var args = argsFor(ambiguousBackup(repoRoot), "import", "zookeeper");

        var ex = assertThrows(ParameterException.class, () -> new SolrBackupStrategy(args).run());

        assertThat(ex.getMessage(), containsString("zookeeper"));
        assertThat(ex.getMessage(), containsString("cloud"));
    }
}
