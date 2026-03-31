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

class EvaluateTest {

    @Test
    void evaluate_failsInvalidParameters() {
        var args = new MigrateArgs();
        var context = mock(RootMetadataMigrationContext.class);
        var meta = new MetadataMigration();

        var results = meta.evaluate(args).execute(context);

        assertThat(results.getExitCode(), equalTo(Migrate.INVALID_PARAMETER_CODE));
        assertThat(results.getErrorMessage(), equalTo("Invalid parameter: No details on the source cluster found, please supply a connection details or a snapshot"));
    }

    @Test
    void evaluate_failsUnexpectedException() {
        var args = new EvaluateArgs();
        args.sourceVersion = Version.fromString("ES 7.10");
        args.fileSystemRepoPath = "";

        var meta = new MetadataMigration();
        var context = mock(RootMetadataMigrationContext.class);
 
        var results = meta.evaluate(args).execute(context);

        assertThat(results.getExitCode(), equalTo(Evaluate.UNEXPECTED_FAILURE_CODE));
        assertThat(results.getErrorMessage(), equalTo("Unexpected failure: No host was found"));
    }

    @Test
    void evaluate_failsUnexpectedExceptionInnerMessage() {
        var args = new EvaluateArgs();
        var meta = new MetadataMigration();
        var context = mock(RootMetadataMigrationContext.class);
 
        var evaluate = spy(meta.evaluate(args));
        doThrow(new RuntimeException("Outer", new RuntimeException("Inner"))).when(evaluate).createClusters();

        var results = evaluate.execute(context);

        assertThat(results.getExitCode(), equalTo(Evaluate.UNEXPECTED_FAILURE_CODE));
        assertThat(results.getErrorMessage(), equalTo("Unexpected failure: Outer, inner cause: Inner"));
    }

    @Test
    void evaluate_exitsNonZeroWhenItemAlreadyExists() {
        var args = new EvaluateArgs();
        var context = mock(RootMetadataMigrationContext.class);
        var clusters = mock(Clusters.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
        var transformers = mock(Transformers.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));

        var items = Items.builder()
            .dryRun(true)
            .indexTemplates(List.of())
            .componentTemplates(List.of())
            .indexes(List.of(
                CreationResult.builder().name("my-index").failureType(CreationFailureType.ALREADY_EXISTS).build()
            ))
            .aliases(List.of())
            .build();

        var evaluate = spy(new MetadataMigration().evaluate(args));
        doReturn(clusters).when(evaluate).createClusters();
        doReturn(transformers).when(evaluate).selectTransformer(any());
        doReturn(items).when(evaluate).migrateAllItems(any(MigrationMode.class), any(), any(), any());

        var result = evaluate.execute(context);

        assertThat(result.getExitCode(), greaterThan(0));
    }

    // Property 2: Exit code reflects ALREADY_EXISTS count
    @Test
    void testExitCodeNonZeroWhenAlreadyExistsPresent() {
        var clusters = mock(Clusters.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
        var items = Items.builder()
            .dryRun(true)
            .indexTemplates(List.of())
            .componentTemplates(List.of())
            .indexes(List.of(
                CreationResult.builder().name("ae").failureType(CreationFailureType.ALREADY_EXISTS).build()
            ))
            .aliases(List.of())
            .build();
        var result = EvaluateResult.builder().items(items).clusters(clusters).exitCode(0).build();
        assertThat(result.getExitCode(), greaterThan(0));
    }

    @Test
    void testExitCodeZeroWhenNoAlreadyExistsAndNoErrors() {
        var clusters = mock(Clusters.class, withSettings().defaultAnswer(RETURNS_DEEP_STUBS));
        var items = Items.builder()
            .dryRun(true)
            .indexTemplates(List.of())
            .componentTemplates(List.of())
            .indexes(List.of(CreationResult.builder().name("i1").build()))
            .aliases(List.of())
            .build();
        var result = EvaluateResult.builder().items(items).clusters(clusters).exitCode(0).build();
        assertThat(result.getExitCode(), equalTo(0));
    }
}
