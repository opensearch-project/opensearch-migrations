package com.rfs.transformers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.common.GlobalMetadata;
import com.rfs.common.IndexMetadata;

/**
* Defines the behavior required to transform the Global and Index Metadata from one version of Elasticsearch/Opensearch
* to another.
*/
public interface Transformer {
    /**
     * Takes the raw JSON representing the Global Metadata of one version and returns a new, transformed copy of the JSON
     */
    public ObjectNode transformGlobalMetadata(GlobalMetadata.Data globalData);

    /**
     * Takes the raw JSON representing the Index Metadata of one version and returns a new, transformed copy of the JSON
     */
    public ObjectNode transformIndexMetadata(IndexMetadata.Data indexData);    
}
