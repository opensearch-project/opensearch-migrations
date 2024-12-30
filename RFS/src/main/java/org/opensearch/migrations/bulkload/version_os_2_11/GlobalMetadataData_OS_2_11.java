package org.opensearch.migrations.bulkload.version_os_2_11;

import org.opensearch.migrations.bulkload.models.GlobalMetadata;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class GlobalMetadataData_OS_2_11 implements GlobalMetadata {
    private final ObjectNode root;

    public GlobalMetadataData_OS_2_11(ObjectNode root) {
        this.root = root;
    }

    @Override
    public ObjectNode toObjectNode() {
        return root;
    }

    @Override
    public JsonPointer getTemplatesPath() {
        return JsonPointer.compile("/templates");
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
