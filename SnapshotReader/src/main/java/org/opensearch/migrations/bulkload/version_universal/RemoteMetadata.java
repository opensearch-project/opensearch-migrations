package org.opensearch.migrations.bulkload.version_universal;

import org.opensearch.migrations.bulkload.models.GlobalMetadata;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class RemoteMetadata implements GlobalMetadata {

    private ObjectNode sourceData;

    @Override
    public ObjectNode toObjectNode() {
        return sourceData;
    }

    @Override
    public JsonPointer getTemplatesPath() {
        return JsonPointer.compile("/templates/templates");
    }

    @Override
    public JsonPointer getIndexTemplatesPath() {
        return JsonPointer.compile("/index_template/index_template");
    }

    @Override
    public JsonPointer getComponentTemplatesPath() {
        return JsonPointer.compile("/component_template/component_template");
    }
}
