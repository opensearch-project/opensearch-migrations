package org.opensearch.migrations.bulkload.worker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.AwarenessAttributeSettings;
import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.FilterScheme;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.transformers.IndexTransformationException;
import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;
import org.opensearch.migrations.metadata.IndexCreator;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.ICreateIndexContext;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class IndexRunner {

    private final String snapshotName;
    private final IndexMetadata.Factory metadataFactory;
    private final IndexCreator indexCreator;
    private final Transformer transformer;
    private final List<String> indexAllowlist;
    private final AwarenessAttributeSettings awarenessAttributeSettings;
    private final Map<String, IndexMetadata> indexMetadataCache;

    public IndexRunner(
        String snapshotName,
        IndexMetadata.Factory metadataFactory,
        IndexCreator indexCreator,
        Transformer transformer,
        List<String> indexAllowlist,
        AwarenessAttributeSettings awarenessAttributeSettings
    ) {
        this(
            snapshotName,
            metadataFactory,
            indexCreator,
            transformer,
            indexAllowlist,
            awarenessAttributeSettings,
            new HashMap<>()
        );
    }

    public IndexMetadataResults migrateIndices(MigrationMode mode, ICreateIndexContext context) {
        var repoDataProvider = metadataFactory.getRepoDataProvider();
        var results = IndexMetadataResults.builder();
        var skipCreation = FilterScheme.filterByAllowList(indexAllowlist, FilterScheme.FilterContext.INDEX).negate();
        var indices = repoDataProvider.getIndicesInSnapshot(snapshotName);
        long skippedIndexes = 0;

        for (var index : indices) {
            if (skipCreation.test(index.getName())) {
                skippedIndexes++;
                continue;
            }

            var indexMetadata = getIndexMetadata(index.getName());
            var creationResults = createIndex(index.getName(), indexMetadata, mode, context);
            creationResults.forEach(results::index);

            indexMetadata.getAliases().fieldNames().forEachRemaining(alias -> {
                var aliasResult = CreationResult.builder().name(alias);
                if (!creationResults.isEmpty()) {
                    aliasResult.failureType(creationResults.get(0).getFailureType());
                }
                results.alias(aliasResult.build());
            });
        }
        results.skippedIndexes(skippedIndexes);
        log.info("Selected {}/{} indices before reading index metadata", indices.size() - skippedIndexes, indices.size());
        return results.build();
    }

    private IndexMetadata getIndexMetadata(String indexName) {
        return indexMetadataCache.computeIfAbsent(
            indexName,
            name -> metadataFactory.fromRepo(snapshotName, name)
        );
    }

    private List<CreationResult> createIndex(
        String indexName,
        IndexMetadata originalIndexMetadata,
        MigrationMode mode,
        ICreateIndexContext context
    ) {
        var indexMetadata = originalIndexMetadata.deepCopy();
        List<CreationResult> creationResults = new ArrayList<>();
        try {
            List<IndexMetadata> transformedMetadataList = transformer.transformIndexMetadata(indexMetadata);
            for (IndexMetadata transformedMetadata : transformedMetadataList) {
                creationResults.add(createInner(indexName, mode, context, transformedMetadata));
            }
        } catch (Exception e) {
            log.atError()
                .setMessage("Index Creation failed with error \"{}\":")
                .addArgument(CreationFailureType.UNABLE_TO_TRANSFORM_FAILURE.getMessage())
                .setCause(e)
                .log();
            creationResults.add(CreationResult.builder()
                .name(indexName)
                .exception(new IndexTransformationException(indexName, e))
                .failureType(CreationFailureType.UNABLE_TO_TRANSFORM_FAILURE)
                .build());
        }
        return creationResults;
    }

    private CreationResult createInner(String indexName,
                                       MigrationMode mode,
                                       ICreateIndexContext context,
                                       IndexMetadata transformedMetadata) {
        try {
            return indexCreator.create(transformedMetadata, mode, awarenessAttributeSettings, context);
        } catch (Exception e) {
            log.atError()
                .setMessage("Index Creation failed for index \"{}\"")
                .addArgument(indexName)
                .setCause(e)
                .log();
            return CreationResult.builder()
                .name(indexName)
                .exception(new IndexTransformationException(indexName, e))
                .failureType(CreationFailureType.UNABLE_TO_TRANSFORM_FAILURE)
                .build();
        }
    } 
}
