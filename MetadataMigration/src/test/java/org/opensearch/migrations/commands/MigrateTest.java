package org.opensearch.migrations.commands;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.opensearch.migrations.MetadataMigration;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.cli.Clusters;
import org.opensearch.migrations.cluster.ClusterReader;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class MigrateTest {

    // Matches a Java stack-trace frame, e.g. "at com.foo.Bar.method(Bar.java:42)".
    private static final Pattern JAVA_STACK_FRAME = Pattern.compile("\\bat [\\w.$]+\\([^)]*:\\d+\\)");

    @Test
    void migrate_failsInvalidParameters() {
        var args = new MigrateArgs();
        var context = mock(RootMetadataMigrationContext.class);
        var meta = new MetadataMigration();

        var result = meta.migrate(args).execute(context);

        assertThat(result.getExitCode(), equalTo(Migrate.INVALID_PARAMETER_CODE));
        assertThat(result.getErrorMessage(), equalTo("Invalid parameter: No details on the source cluster found, please supply a connection details or a snapshot"));
    }

    @Test
    void migrate_failsUnexpectedException() {
        var args = new MigrateArgs();
        args.sourceVersion = Version.fromString("ES 7.10");
        args.repoUri = "/tmp";
        var context = mock(RootMetadataMigrationContext.class);
        var meta = new MetadataMigration();

        var result = meta.migrate(args).execute(context);

        assertThat(result.getExitCode(), equalTo(Migrate.UNEXPECTED_FAILURE_CODE));
        assertThat(result.getErrorMessage(), containsString("Unexpected failure: No host was found"));
    }

    @Test
    void migrate_classifiesSnapshotReadFailureWithDedicatedExitCode() {
        var args = new MigrateArgs();
        args.snapshotName = "snap1";
        var context = mock(RootMetadataMigrationContext.class);
        var meta = new MetadataMigration();

        var migrate = spy(meta.migrate(args));
        // A snapshot read failure surfacing during source/snapshot setup, wrapped the way real
        // callers wrap it (e.g. inside a metadata-read wrapper).
        var readFailure = new SnapshotRepo.CannotParseRepoFile("corrupt repo metadata: index-0");
        doThrow(new RuntimeException("reading snapshot failed", readFailure))
            .when(migrate).createClusters();

        var result = migrate.execute(context);

        assertThat(result.getExitCode(), equalTo(MigratorEvaluatorBase.SNAPSHOT_READ_FAILED_EXIT_CODE));
        assertThat(result.getErrorMessage(), containsString("Non-retriable snapshot read failure"));
        assertThat(result.getErrorMessage(), containsString("corrupt repo metadata: index-0"));
        assertThat(result.getErrorMessage(), containsString("snap1"));
        assertThat("error should be a labeled line, not a raw stack trace",
            JAVA_STACK_FRAME.matcher(result.getErrorMessage()).find(), equalTo(false));
    }

    @Test
    void migrate_snapshotReadFailureNamesFilesystemRepo() {
        var args = new MigrateArgs();
        args.snapshotName = "snap2";
        args.repoUri = "/backups/repo";
        var context = mock(RootMetadataMigrationContext.class);
        var meta = new MetadataMigration();

        var migrate = spy(meta.migrate(args));
        doThrow(new RuntimeException("read failed",
            new SnapshotRepo.CannotParseRepoFile("bad index-0"))).when(migrate).createClusters();

        var result = migrate.execute(context);

        assertThat(result.getExitCode(), equalTo(MigratorEvaluatorBase.SNAPSHOT_READ_FAILED_EXIT_CODE));
        assertThat(result.getErrorMessage(), containsString("repo=/backups/repo"));
        assertThat("error should be a labeled line, not a raw stack trace",
            JAVA_STACK_FRAME.matcher(result.getErrorMessage()).find(), equalTo(false));
    }

    @Test
    void migrate_failsUnexpectedExceptionInnerMessage() {
        var args = new EvaluateArgs();
        var meta = new MetadataMigration();
        var context = mock(RootMetadataMigrationContext.class);
 
        var evaluate = spy(meta.migrate(args));
        doThrow(new RuntimeException("Outer", new RuntimeException("Inner"))).when(evaluate).createClusters();

        var results = evaluate.execute(context);

        assertThat(results.getExitCode(), equalTo(Evaluate.UNEXPECTED_FAILURE_CODE));
        assertThat(results.getErrorMessage(), equalTo("Unexpected failure: Outer, inner cause: Inner"));
    }

    @Test
    void migrate_rejectsCollectionRoutingWithoutRoutedTarget() {
        var args = new MigrateArgs();
        args.snapshotName = "snap1";
        args.collectionRouting = "{\"staticCollectionRouting\": [{\"sourceIndex\": \"a\", \"collection\": \"c\"}]}";
        var context = mock(RootMetadataMigrationContext.class);

        var result = new MetadataMigration().migrate(args).execute(context);

        assertThat(result.getExitCode(), equalTo(Migrate.INVALID_PARAMETER_CODE));
        assertThat(result.getErrorMessage(), containsString("--target-collection-routed"));
    }

    @Test
    void resolveServerlessCollections_coversAllowlistedIndicesAndFailsOnUnmatched() {
        var args = new MigrateArgs();
        args.snapshotName = "snap1";
        args.targetArgs.collectionRouted = true;
        args.collectionRouting = "{\"regexCollectionRouting\": [{\"sourceIndex\": \"(.+)-\\\\d+\", \"collection\": \"$1\"}]}";
        args.dataFilterArgs.indexAllowlist = List.of("regex:.*-\\d+");
        var migrate = new MetadataMigration().migrate(args);

        var clusters = clustersWithSnapshotIndices("b-1", "a-1", "b-2", "skipped");
        assertThat(migrate.resolveServerlessCollections(clusters), equalTo(List.of("b", "a")));

        args.dataFilterArgs.indexAllowlist = List.of();
        var unmatched = assertThrows(ParameterException.class,
            () -> migrate.resolveServerlessCollections(clusters));
        assertThat(unmatched.getMessage(), containsString("[skipped]"));
    }

    @Test
    void resolveServerlessCollections_failsWhenNoIndexIsSelected() {
        var args = new MigrateArgs();
        args.snapshotName = "snap1";
        args.targetArgs.collectionRouted = true;
        args.collectionRouting = "{\"staticCollectionRouting\": [{\"sourceIndex\": \"a\", \"collection\": \"c\"}]}";
        args.dataFilterArgs.indexAllowlist = List.of("nothing-matches");
        var migrate = new MetadataMigration().migrate(args);

        var e = assertThrows(ParameterException.class,
            () -> migrate.resolveServerlessCollections(clustersWithSnapshotIndices("a")));
        assertThat(e.getMessage(), containsString("No source indices were selected"));
    }

    @Test
    void resolveServerlessCollections_isEmptyForOtherTargets() {
        var args = new MigrateArgs();
        args.snapshotName = "snap1";

        assertThat(new MetadataMigration().migrate(args).resolveServerlessCollections(null), equalTo(List.of()));
    }

    private static Clusters clustersWithSnapshotIndices(String... names) {
        var indices = Arrays.stream(names).map(name -> {
            var index = mock(SnapshotRepo.Index.class);
            when(index.getName()).thenReturn(name);
            return index;
        }).toList();
        var repoDataProvider = mock(SnapshotRepo.Provider.class);
        doReturn(indices).when(repoDataProvider).getIndicesInSnapshot("snap1");
        var indexMetadata = mock(IndexMetadata.Factory.class);
        when(indexMetadata.getRepoDataProvider()).thenReturn(repoDataProvider);
        var source = mock(ClusterReader.class);
        when(source.getIndexMetadata()).thenReturn(indexMetadata);
        return Clusters.builder().source(source).build();
    }
}
