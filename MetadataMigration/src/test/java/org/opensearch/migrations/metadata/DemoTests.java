package org.opensearch.migrations.metadata;

import java.util.List;
import java.util.Map;

import org.hamcrest.Matcher;
import org.junit.Test;
import org.junit.jupiter.api.Disabled;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.transformation.CanApplyResult;

import com.rfs.framework.SearchClusterContainer;
import lombok.SneakyThrows;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

public class DemoTests {

    private Meta meta = new Meta();

    @Test
    @Disabled
    @SneakyThrows
    public void evaluate_noTarget_issuesFound() {
        try (var sourceClusterContainer = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2)) {

            // Configure meta to connect to the source cluster
            var configureSource = meta.configure().source(Map.of("host", sourceClusterContainer.getUrl())).execute();

            assertThat(configureSource.result.code, equalTo(SuccessExitCode));

            // Evaluate the migration status
            var evaluate = meta.evaluate().execute();

            var sourceCluster = evaluate.clusters.source;
            assertThat(sourceCluster.version, equalTo(Version.fromString("ES 7.10.2")));
            assertThat(sourceCluster.messages, containsWarning(USING_VERSION_FALLBACK_MESSAGE));

            List<String> candidateIndexes = evaluate.candidates.indexes.list;
            var expectedIndexes = List.of(
                "geonames",
                "logs-181998",
                "logs-191998",
                "logs-201998",
                "logs-211998",
                "logs-221998",
                "logs-231998",
                "logs-241998",
                "nyc_taxis",
                "reindexed-logs",
                "sonested"
            );
            assertThat(candidateIndexes, containsInAnyOrder(expectedIndexes));

            assertThat(evaluate.candidates.index_templates.list, contains("daily_logs"));
            assertThat(evaluate.candidates.component_templates.list, empty());
            assertThat(evaluate.candidates.aliases.list, contains("logs-all"));

            assertThat(evaluate.transformations.list, empty());

            var expectedIssues = List.of(NO_TARGET_MESSAGE);
            assertThat(evaluate.result.code, equalTo(expectedIssues.size()));
            assertThat(evaluate.issues, equalTo(expectedIssues));
        }
    }

    @Test
    @Disabled
    @SneakyThrows
    public void evaluate_withTarget_issuesFound() {
        try (
            var sourceClusterContainer = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2);
            var targetClusterContainer = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
        ) {

            // Configure meta to connect to the source & target cluster
            var configure = meta.configure()
                .source(Map.of("host", sourceClusterContainer.getUrl()))
                .target(Map.of("host", targetClusterContainer.getUrl()))
                .execute();

            assertThat(configure.result.code, equalTo(SuccessExitCode));

            // Evaluate the migration status
            var evaluate = meta.evaluate().execute();

            var sourceCluster = evaluate.clusters.source;
            assertThat(sourceCluster.version, equalTo(Version.fromString("ES 7.10.2")));
            assertThat(sourceCluster.messages, containsWarning(USING_VERSION_FALLBACK_MESSAGE));

            var targetCluster = evaluate.clusters.target;
            assertThat(targetCluster.version, equalTo(Version.fromString("OS 2.14")));

            // Make sure the transformation errors are shown
            var indexMappingRemoval = evaluate.transformations.index.list.stream()
                .filter(t -> t.name.equals("IndexMappingTypeRemoval"))
                .findFirst()
                .orElseThrow();
            var indexTemplateMappingRemoval = evaluate.transformations.indexTemplate.list.stream()
                .filter(t -> t.name.equals("IndexMappingTypeRemoval"))
                .findFirst()
                .orElseThrow();

            var expectedMultipleTypeMessage = "Multiple mapping types are not supported";
            List.of(indexMappingRemoval, indexTemplateMappingRemoval).forEach(transformation -> {
                assertThat(transformation.status, instanceOf(CanApplyResult.Unsupported.class));
                assertThat(transformation.details, containsString(expectedMultipleTypeMessage));
            });

            assertThat(evaluate.result.code, greaterThanOrEqualTo(2));
            assertThat(evaluate.issues, everyItem(containsString(expectedMultipleTypeMessage)));
        }
    }

    @Test
    @Disabled
    public void evaluate_success() {
        try (
            var sourceClusterContainer = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2);
            var targetClusterContainer = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
        ) {

            var expectedIndexes = List.of("geonames", "nyc_taxis", "sonested");
            // Configure meta to connect to the source & target cluster with rules on indexes and indexTemplates
            var configure = meta.configure()
                .source(Map.of("host", sourceClusterContainer.getUrl()))
                .target(Map.of("host", targetClusterContainer.getUrl()))
                .index(Map.of("allowed", expectedIndexes))
                .indexTemplate(Map.of("denied", List.of("daily_logs")))
                .execute();

            assertThat(configure.result.code, equalTo(SuccessExitCode));

            // Evaluate the migration status
            var evaluate = meta.evaluate().execute();

            var candidateIndexes = evaluate.candidates.indexes.list;
            assertThat(candidateIndexes, containsInAnyOrder(expectedIndexes));

            assertThat(evaluate.transformations.list, everyItem(transformationWillApply()));

            assertThat(evaluate.result.code, equalTo(SuccessExitCode));
        }
    }

    @Test
    @Disabled
    public void migrate_success() {
        try (
            var sourceClusterContainer = new SearchClusterContainer(SearchClusterContainer.ES_V7_10_2);
            var targetClusterContainer = new SearchClusterContainer(SearchClusterContainer.OS_V2_14_0)
        ) {

            var expectedIndexes = List.of("geonames", "nyc_taxis", "sonested");
            // Configure meta to connect to the source & target cluster with rules on indexes and indexTemplates
            var configure = meta.configure()
                .source(Map.of("host", sourceClusterContainer.getUrl()))
                .target(Map.of("host", targetClusterContainer.getUrl()))
                .index(Map.of("allowed", expectedIndexes))
                .indexTemplate(Map.of("denied", List.of("daily_logs")))
                .execute();

            assertThat(configure.result.code, equalTo(SuccessExitCode));

            // Evaluate the migration status
            var evaluate = meta.migrate().execute();

            assertThat(evaluate.migrated.indexes.list, containsInAnyOrder(expectedIndexes));
            assertThat(evaluate.migrated.aliases.list, containsInAnyOrder("logs-all"));
            var expectedTotalDeployedItems = expectedIndexes.size() + 1 /* alias: logs-all*/;
            assertThat(evaluate.migrated.list, hasSize(expectedTotalDeployedItems));

            var indexMappingRemovalTransformer = evaluate.transformations.list.stream()
                .filter(t -> t.name.equals("IndexMappingTypeRemoval"))
                .findFirst()
                .orElseThrow();
            assertThat(indexMappingRemovalTransformer, wasAppliedTimes(expectedIndexes.size()));

            assertThat(evaluate.result.code, equalTo(SuccessExitCode));
        }
    }

    private static final int SuccessExitCode = 0;
    private static final String USING_VERSION_FALLBACK_MESSAGE = "msg";
    private static final String NO_TARGET_MESSAGE = "msg";

    private Matcher<List<String>> containsWarning(String usingVersionFallbackMessage) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'containsWarning'");
    }

    private Matcher<TransformationInfo> transformationWillApply() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'transformationWillApply'");
    }

    private Matcher<TransformationInfo> wasAppliedTimes(int size) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'wasAppliedTimes'");
    }
}
