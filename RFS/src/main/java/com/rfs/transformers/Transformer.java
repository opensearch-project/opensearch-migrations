package com.rfs.transformers;

import com.rfs.models.GlobalMetadata;
import com.rfs.models.IndexMetadata;

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
    public IndexMetadata transformIndexMetadata(IndexMetadata indexData);
}
