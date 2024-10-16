package org.opensearch.migrations.bulkload.transformers;

import org.opensearch.migrations.bulkload.common.RfsException;

public class IndexTransformationException extends RfsException {
    public IndexTransformationException(String indexName, Throwable cause) {
        super("Transformation for index index '" + indexName + "' failed.", cause);
    }
}
