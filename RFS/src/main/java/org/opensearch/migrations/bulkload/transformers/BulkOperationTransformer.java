package org.opensearch.migrations.bulkload.transformers;

import java.util.List;

import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.transform.IJsonTransformer;

/**
 * Native Java transformation of RFS bulk operations.
 *
 * <p>The document sink passes typed metadata and unparsed source bytes when every
 * stage implements this contract. Accessing {@link BulkOperationSpec#getDocument()}
 * opts that operation into parsing and serializing its body. Metadata-only changes
 * keep the original bytes. Operations may be edited in place; to filter, reorder
 * or add operations, return a new list rather than modifying the input list.
 * Implementations must return a non-null list and must not retain or concurrently
 * mutate a batch.
 *
 * <p>The JSON adapter preserves compatibility and ordering in mixed Java/JavaScript
 * chains. Those chains use the existing JSON path and materialize document bodies.
 */
@FunctionalInterface
public interface BulkOperationTransformer extends IJsonTransformer {
    List<BulkOperationSpec> transformOperations(List<BulkOperationSpec> operations);

    @Override
    default Object transformJson(Object input) {
        return BulkOperationJsonAdapter.transform(input, this);
    }
}
