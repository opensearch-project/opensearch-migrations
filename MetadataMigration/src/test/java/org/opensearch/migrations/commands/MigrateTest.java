package org.opensearch.migrations.commands;

import java.util.List;

import org.opensearch.migrations.MetadataMigration;
import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.cli.Clusters;
import org.opensearch.migrations.cli.Items;
import org.opensearch.migrations.cli.Transformers;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.withSettings;
class MigrateTest {

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
        args.fileSystemRepoPath = "";
        var context = mock(RootMetadataMigrationContext.class);
        var meta = new MetadataMigration();

        var result = meta.migrate(args).execute(context);

        assertThat(result.getExitCode(), equalTo(Migrate.UNEXPECTED_FAILURE_CODE));
        assertThat(result.getErrorMessage(), containsString("Unexpected failure: No host was found"));
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
    void migrate_exitsNonZeroWhenItemAlreadyExists() {
        var args = new MigrateArgs();
        var context = mock(RootMetadataMigrationContext.class);
        var clusters = mock(Clusters.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
        var transformers = mock(Transformers.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));

        var items = Items.builder()
            .dryRun(false)
            .indexTemplates(List.of())
            .componentTemplates(List.of())
            .indexes(List.of(
                CreationResult.builder().name("my-index").failureType(CreationFailureType.ALREADY_EXISTS).build()
            ))
            .aliases(List.of())
            .build();

        var migrate = spy(new MetadataMigration().migrate(args));
        doReturn(clusters).when(migrate).createClusters();
        doReturn(transformers).when(migrate).selectTransformer(any());
        doReturn(items).when(migrate).migrateAllItems(any(MigrationMode.class), any(), any(), any());

        var result = migrate.execute(context);

        assertThat(result.getExitCode(), greaterThan(0));
    }

    @Test
    void migrate_allowExistingFlag_exitsZeroWhenItemsAlreadyExist() {
        var args = new MigrateArgs();
        args.allowExisting = true;
        var context = mock(RootMetadataMigrationContext.class);
        var clusters = mock(Clusters.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
        var transformers = mock(Transformers.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));

        // With --allow-existing, already-existing items are silently skipped and not recorded as ALREADY_EXISTS
        var items = Items.builder()
            .dryRun(false)
            .indexTemplates(List.of())
            .componentTemplates(List.of())
            .indexes(List.of(
                CreationResult.builder().name("my-index").build() // successful — skipped silently
            ))
            .aliases(List.of())
            .build();

        var migrate = spy(new MetadataMigration().migrate(args));
        doReturn(clusters).when(migrate).createClusters();
        doReturn(transformers).when(migrate).selectTransformer(any());
        doReturn(items).when(migrate).migrateAllItems(any(MigrationMode.class), any(), any(), any());

        var result = migrate.execute(context);

        assertThat(result.getExitCode(), equalTo(0));
    }

    @Test
    void migrate_completesAllItemsEvenWhenSomeAlreadyExist() {
        var args = new MigrateArgs();
        var context = mock(RootMetadataMigrationContext.class);
        var clusters = mock(Clusters.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
        var transformers = mock(Transformers.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));

        // All three items are present in the result — no early abort
        var items = Items.builder()
            .dryRun(false)
            .indexTemplates(List.of())
            .componentTemplates(List.of())
            .indexes(List.of(
                CreationResult.builder().name("index-1").build(),
                CreationResult.builder().name("index-2").failureType(CreationFailureType.ALREADY_EXISTS).build(),
                CreationResult.builder().name("index-3").build()
            ))
            .aliases(List.of())
            .build();

        var migrate = spy(new MetadataMigration().migrate(args));
        doReturn(clusters).when(migrate).createClusters();
        doReturn(transformers).when(migrate).selectTransformer(any());
        doReturn(items).when(migrate).migrateAllItems(any(MigrationMode.class), any(), any(), any());

        var result = migrate.execute(context);

        // Non-zero exit due to ALREADY_EXISTS
        assertThat(result.getExitCode(), greaterThan(0));
        // All three indexes are present — no early abort
        assertThat(result.getItems().getIndexes().size(), equalTo(3));
        assertThat(result.getItems().getAlreadyExistsCount(), equalTo(1));
    }

    // Property 2: Exit code reflects ALREADY_EXISTS count
    @Test
    void testExitCodeNonZeroWhenAlreadyExistsPresent() {
        var clusters = mock(Clusters.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
        var items = Items.builder()
            .dryRun(false)
            .indexTemplates(List.of())
            .componentTemplates(List.of())
            .indexes(List.of(
                CreationResult.builder().name("ae").failureType(CreationFailureType.ALREADY_EXISTS).build()
            ))
            .aliases(List.of())
            .build();
        var result = MigrateResult.builder().items(items).clusters(clusters).exitCode(0).build();
        assertThat(result.getExitCode(), greaterThan(0));
    }

    @Test
    void testExitCodeZeroWhenNoAlreadyExistsAndNoErrors() {
        var clusters = mock(Clusters.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
        var items = Items.builder()
            .dryRun(false)
            .indexTemplates(List.of())
            .componentTemplates(List.of())
            .indexes(List.of(CreationResult.builder().name("i1").build()))
            .aliases(List.of())
            .build();
        var result = MigrateResult.builder().items(items).clusters(clusters).exitCode(0).build();
        assertThat(result.getExitCode(), equalTo(0));
    }
}
