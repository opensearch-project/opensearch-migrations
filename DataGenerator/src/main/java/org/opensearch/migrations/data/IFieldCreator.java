package org.opensearch.migrations.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Helpers to build fields for index mappings.
 */
public interface IFieldCreator {
    ObjectMapper mapper = new ObjectMapper();

    default ObjectNode createField(ElasticsearchType type) {
        String typeString = type.getValue();
        return mapper.createObjectNode().put("type", typeString);
    }

    default ObjectNode fieldGeoPoint() { return createField(ElasticsearchType.GEO_POINT); }
    default ObjectNode fieldInt()      { return createField(ElasticsearchType.INTEGER); }
    default ObjectNode fieldIP()       { return createField(ElasticsearchType.IP); }
    default ObjectNode fieldKeyword()  { return createField(ElasticsearchType.KEYWORD); }
    default ObjectNode fieldLong()     { return createField(ElasticsearchType.LONG); }
    default ObjectNode fieldNested()   { return createField(ElasticsearchType.NESTED); }
    default ObjectNode fieldText()     { return createField(ElasticsearchType.TEXT); }

    default ObjectNode fieldRawTextKeyword() {
        return mapper.createObjectNode()
            .put("type", "text")
            .set("fields", mapper.createObjectNode()
                .set("raw", createField(ElasticsearchType.KEYWORD)));
    }

    default ObjectNode fieldScaledFloat(int scalingFactor) {
        return createField(ElasticsearchType.SCALED_FLOAT)
            .put("scaling_factor", scalingFactor);
    }
    default ObjectNode fieldScaledFloat() { return fieldScaledFloat(100); }

    default ObjectNode fieldDate() { return createField(ElasticsearchType.DATE); }
    default ObjectNode fieldDateISO() {
        return fieldDate().put("format", "yyyy-MM-dd HH:mm:ss");
    }
}
