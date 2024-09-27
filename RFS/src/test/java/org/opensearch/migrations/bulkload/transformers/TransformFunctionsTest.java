package org.opensearch.migrations.bulkload.transformers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import lombok.extern.slf4j.Slf4j;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
public class TransformFunctionsTest {

    @Test
    public void removeIntermediateMappingsLevels_AsExpected() throws Exception {
        // Extract from {"mappings":[{"_doc":{"properties":{"address":{"type":"text"}}}}])
        ObjectNode testNode1 = new ObjectMapper().createObjectNode();
        ArrayNode mappingsNode1 = new ObjectMapper().createArrayNode();
        ObjectNode docNode1 = new ObjectMapper().createObjectNode();
        ObjectNode propertiesNode1 = new ObjectMapper().createObjectNode();
        ObjectNode addressNode1 = new ObjectMapper().createObjectNode();
        addressNode1.put("type", "text");
        propertiesNode1.set("address", addressNode1);
        docNode1.set(TransformFunctions.PROPERTIES_KEY_STR, propertiesNode1);
        ObjectNode intermediateNode1 = new ObjectMapper().createObjectNode();
        intermediateNode1.set("_doc", docNode1);
        mappingsNode1.add(intermediateNode1);
        testNode1.set(TransformFunctions.MAPPINGS_KEY_STR, mappingsNode1);

        TransformFunctions.removeIntermediateMappingsLevels(testNode1);
        assertEquals(docNode1.toString(), testNode1.get(TransformFunctions.MAPPINGS_KEY_STR).toString());

        // Extract from {"mappings":[{"properties":{"address":{"type":"text"}}}])
        ObjectNode testNode2 = new ObjectMapper().createObjectNode();
        ArrayNode mappingsNode2 = new ObjectMapper().createArrayNode();
        ObjectNode propertiesNode2 = new ObjectMapper().createObjectNode();
        ObjectNode addressNode2 = new ObjectMapper().createObjectNode();
        addressNode2.put("type", "text");
        propertiesNode2.set("address", addressNode2);
        ObjectNode intermediateNode2 = new ObjectMapper().createObjectNode();
        intermediateNode2.set(TransformFunctions.PROPERTIES_KEY_STR, propertiesNode2);
        mappingsNode2.add(intermediateNode2);
        testNode2.set(TransformFunctions.MAPPINGS_KEY_STR, mappingsNode2);

        TransformFunctions.removeIntermediateMappingsLevels(testNode2);
        assertEquals(intermediateNode2.toString(), testNode2.get(TransformFunctions.MAPPINGS_KEY_STR).toString());

        // Extract from {"mappings":[])
        ObjectNode testNode3 = new ObjectMapper().createObjectNode();
        ArrayNode mappingsNode3 = new ObjectMapper().createArrayNode();
        testNode3.set(TransformFunctions.MAPPINGS_KEY_STR, mappingsNode3);

        TransformFunctions.removeIntermediateMappingsLevels(testNode3);
        assertEquals(new ObjectMapper().createObjectNode().toString(), testNode3.get(TransformFunctions.MAPPINGS_KEY_STR).toString());
    }

    @Test
    public void getMappingsFromBeneathIntermediate_AsExpected() throws Exception {
        // Extract from {"_doc":{"properties":{"address":{"type":"text"}}}}
        ObjectNode testNode1 = new ObjectMapper().createObjectNode();
        ObjectNode docNode1 = new ObjectMapper().createObjectNode();
        ObjectNode propertiesNode1 = new ObjectMapper().createObjectNode();
        ObjectNode addressNode1 = new ObjectMapper().createObjectNode();
        addressNode1.put("type", "text");
        propertiesNode1.set("address", addressNode1);
        docNode1.set(TransformFunctions.PROPERTIES_KEY_STR, propertiesNode1);
        testNode1.set("_doc", docNode1);

        ObjectNode result1 = TransformFunctions.getMappingsFromBeneathIntermediate(testNode1);
        assertEquals(docNode1.toString(), result1.toString());

        // Extract from {"arbitrary_type":{"properties":{"address":{"type":"text"}}}}
        ObjectNode testNode2 = new ObjectMapper().createObjectNode();
        ObjectNode docNode2 = new ObjectMapper().createObjectNode();
        ObjectNode propertiesNode2 = new ObjectMapper().createObjectNode();
        ObjectNode addressNode2 = new ObjectMapper().createObjectNode();
        addressNode2.put("type", "text");
        propertiesNode2.set("address", addressNode2);
        docNode2.set(TransformFunctions.PROPERTIES_KEY_STR, propertiesNode2);
        testNode2.set("arbitrary_type", docNode2);

        ObjectNode result2 = TransformFunctions.getMappingsFromBeneathIntermediate(testNode2);
        assertEquals(docNode2.toString(), result2.toString());

        // Extract from {"properties":{"address":{"type":"text"}}
        ObjectNode testNode3 = new ObjectMapper().createObjectNode();
        ObjectNode propertiesNode3 = new ObjectMapper().createObjectNode();
        ObjectNode addressNode3 = new ObjectMapper().createObjectNode();
        addressNode3.put("type", "text");
        propertiesNode3.set("address", addressNode3);
        testNode3.set(TransformFunctions.PROPERTIES_KEY_STR, propertiesNode3);

        ObjectNode result3 = TransformFunctions.getMappingsFromBeneathIntermediate(testNode3);
        assertEquals(testNode3.toString(), result3.toString());
    }

}
