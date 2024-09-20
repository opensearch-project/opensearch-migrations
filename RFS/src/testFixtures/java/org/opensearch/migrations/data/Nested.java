package org.opensearch.migrations.data;

import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.experimental.UtilityClass;

import static org.opensearch.migrations.data.GeneratedData.createField;

@UtilityClass
public class Nested {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static ObjectNode generateNestedIndex() {
        var index = mapper.createObjectNode();
        var mappings = mapper.createObjectNode();
        var properties = mapper.createObjectNode();
        
        properties.set("parentId", createField("integer"));
        properties.set("parentName", createField("text"));
        
        // Create nested field for children
        var children = mapper.createObjectNode();
        var childrenProps = mapper.createObjectNode();
        childrenProps.set("childId", createField("integer"));
        childrenProps.set("name", createField("text"));
        childrenProps.set("age", createField("integer"));
        children.set("properties", childrenProps);
        
        var nestedField = mapper.createObjectNode();
        nestedField.put("type", "nested");
        nestedField.set("properties", childrenProps);
        properties.set("children", nestedField);

        mappings.set("properties", properties);
        index.set("mappings", mappings);
        return index;
    }

    public static Stream<ObjectNode> generateNestedDocs(int numDocs) {
        var random = new Random(1L);

        return IntStream.range(0, numDocs)
            .mapToObj(i -> {
                var doc = mapper.createObjectNode();
                doc.put("parentId", i + 1000);
                doc.put("parentName", "Parent" + (i + 1));

                var children = generateChildren(mapper, random);
                doc.set("children", children);
                return doc;
            }
        );
    }

    private static ArrayNode generateChildren(ObjectMapper mapper, Random random) {
        var children = mapper.createArrayNode();
        var numChildren = random.nextInt(5) + 1; // 1 to 5 children per parent

        for (int i = 0; i < numChildren; i++) {
            var child = mapper.createObjectNode();
            child.put("childId", i + 100);
            child.put("name", "Child" + (i + 1));
            child.put("age", randomAge(random));

            children.add(child);
        }
        return children;
    }

    private static int randomAge(Random random) {
        return random.nextInt(15) + 5; // Child age between 5 and 20
    }
}
