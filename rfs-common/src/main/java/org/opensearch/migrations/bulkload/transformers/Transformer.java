package org.opensearch.migrations.bulkload.transformers;

import java.util.List;

import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;

/**
* Defines the behavior required to transform the Global and Index Metadata from one version of Elasticsearch/Opensearch
* to another.
*/
public interface Transformer {
    /**
     * Takes the raw JSON representing the Global Metadata of one version and returns a new, transformed copy of the JSON
     */
    public GlobalMetadata transformGlobalMetadata(GlobalMetadata globalData);

    /**
     * Takes the raw JSON representing the Index Metadata of one version and returns a new, transformed copy of the JSON
     */
    public List<IndexMetadata> transformIndexMetadata(IndexMetadata indexData);
}
