package org.opensearch.migrations.bulkload.transformers;

import java.util.List;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;

/** JSON compatibility boundary for bulk transformations; native chains bypass this adapter. */
@UtilityClass
class BulkOperationJsonAdapter {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();

    static Object transform(Object input, BulkOperationTransformer transformer) {
        boolean batch = input instanceof List<?>;
        var items = batch ? (List<?>) input : List.of(input);
        var operations = items.stream()
            .map(item -> MAPPER.convertValue(item, BulkOperationSpec.class))
            .collect(Collectors.toList());
        var result = transformer.transformOperations(operations).stream()
            .map(operation -> operation.toTransformerMap(MAPPER))
            .toList();
        return !batch && result.size() == 1 ? result.get(0) : result;
    }
}
