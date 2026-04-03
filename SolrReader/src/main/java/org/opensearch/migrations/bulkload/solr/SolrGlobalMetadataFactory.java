package org.opensearch.migrations.bulkload.solr;

import org.opensearch.migrations.bulkload.common.SnapshotRepo.Provider;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SolrGlobalMetadataFactory implements GlobalMetadata.Factory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public GlobalMetadata fromRepo(String snapshotName) {
        log.info("Solr does not have global metadata (templates), returning empty metadata");
        return new SolrGlobalMetadata();
    }

    @Override
    public GlobalMetadata fromJsonNode(JsonNode root) {
        throw new UnsupportedOperationException("Unimplemented method 'fromJsonNode'");
    }

    @Override
    public SmileFactory getSmileFactory() {
        throw new UnsupportedOperationException("Unimplemented method 'getSmileFactory'");
    }

    @Override
    public Provider getRepoDataProvider() {
        throw new UnsupportedOperationException("Unimplemented method 'getRepoDataProvider'");
    }

    private static class SolrGlobalMetadata implements GlobalMetadata {
        @Override
        public ObjectNode toObjectNode() {
            return MAPPER.createObjectNode();
        }

        @Override
        public JsonPointer getTemplatesPath() {
            return JsonPointer.compile("/templates");
        }

        @Override
        public JsonPointer getIndexTemplatesPath() {
            return JsonPointer.compile("/index_template");
        }

        @Override
        public JsonPointer getComponentTemplatesPath() {
            return JsonPointer.compile("/component_template");
        }
    }
}
