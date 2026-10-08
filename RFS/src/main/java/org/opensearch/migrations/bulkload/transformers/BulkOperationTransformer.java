package org.opensearch.migrations.bulkload.transformers;

import java.util.List;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.transform.IJsonTransformer;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Native Java transformation of RFS bulk operations.
 *
 * <p>The document sink passes typed metadata and unparsed source bytes when every
 * stage implements this contract. Accessing {@link BulkOperationSpec#getDocument()}
 * opts that operation into parsing and serializing its body. Metadata-only changes
 * keep the original bytes. Implementations may filter, reorder or add operations,
 * must return a non-null list, and must not retain or concurrently mutate a batch.
 *
 * <p>The JSON adapter preserves compatibility and ordering in mixed Java/JavaScript
 * chains. Those chains use the existing JSON path and materialize document bodies.
 */
public abstract class BulkOperationTransformer implements IJsonTransformer {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();

    public abstract List<BulkOperationSpec> transformOperations(List<BulkOperationSpec> operations);

    @Override
    public Object transformJson(Object input) {
        boolean batch = input instanceof List<?>;
        var items = batch ? (List<?>) input : List.of(input);
        var operations = items.stream()
            .map(item -> MAPPER.convertValue(item, BulkOperationSpec.class))
            .collect(Collectors.toList());
        var result = transformOperations(operations).stream()
            .map(operation -> operation.toTransformerMap(MAPPER))
            .toList();
        return !batch && result.size() == 1 ? result.get(0) : result;
    }
}
