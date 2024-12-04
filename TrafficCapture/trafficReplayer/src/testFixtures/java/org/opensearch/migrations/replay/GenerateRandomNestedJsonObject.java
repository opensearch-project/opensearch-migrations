package org.opensearch.migrations.replay;

import java.util.AbstractMap;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.opensearch.migrations.utils.PruferTreeGenerator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

public class GenerateRandomNestedJsonObject {

    public static final String KEY_PREFIX = "keyPrefix-";
    public static final String VALUE_PREFIX = "VALUE_";
    ObjectMapper objectMapper;

    public GenerateRandomNestedJsonObject() {
        objectMapper = new ObjectMapper();
    }

    public String getRandomTreeFormattedAsString(boolean pretty, int seed, int numNodes, int numArrays)
        throws JsonProcessingException {
        Random random = new Random(seed);
        var jsonObj = makeRandomJsonObject(random, numNodes, numArrays);
        if (pretty) {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObj);
        } else {
            return objectMapper.writeValueAsString(jsonObj);
        }
    }

    @SneakyThrows
    public static Object makeRandomJsonObject(Random random, int numNodes, int numArrays) {
        assert numArrays < numNodes;
        var ptg = new PruferTreeGenerator<String>();
        var edges = IntStream.range(0, numNodes - 3).map(x -> random.nextInt(numNodes - 1) + 1).sorted().toArray();
        var tree = ptg.makeTree(vn -> Integer.toString(vn), edges);
        PriorityQueue<Map.Entry<Map<String, Object>, String>> parentAndBiggestChildPQ = new PriorityQueue<>(
            Comparator.comparingInt(kvp -> -1 * getSize(kvp.getKey().get(kvp.getValue())))
        );
        var rval = convertSimpleNodeToJsonTree(tree, parentAndBiggestChildPQ);
        replaceTopItemsForArrays(numArrays, parentAndBiggestChildPQ);
        return rval;
    }

    private static int getSize(Object o) {
        return o instanceof Map ? ((Map) o).size() : 1;
    }

    private static void replaceTopItemsForArrays(
        int numArrays,
        PriorityQueue<Map.Entry<Map<String, Object>, String>> parentAndBiggestChildPQ
    ) {
        for (int i = 0; i < numArrays; ++i) {
            var pairToEdit = parentAndBiggestChildPQ.poll();
            var parentMap = pairToEdit.getKey();
            var key = pairToEdit.getValue();
            parentMap.put(key, makeArray(parentMap.get(key)));
        }
    }

    private static Object makeArray(Object o) {
        return o instanceof Map ? ((Map) o).values().toArray() : new Object[] { o };
    }

    public static Object convertSimpleNodeToJsonTree(
        PruferTreeGenerator.SimpleNode<String> treeNode,
        PriorityQueue<Map.Entry<Map<String, Object>, String>> parentAndBiggestChildPQ
    ) {
        if (treeNode.hasChildren()) {
            var myMap = new LinkedHashMap<String, Object>();
            myMap.putAll(
                treeNode.getChildren()
                    .collect(
                        Collectors.toMap(
                            child -> KEY_PREFIX + child.value,
                            child -> convertSimpleNodeToJsonTree(child, parentAndBiggestChildPQ)
                        )
                    )
            );
            myMap.entrySet()
                .stream()
                .forEach(kvp -> parentAndBiggestChildPQ.add(new AbstractMap.SimpleEntry<>(myMap, kvp.getKey())));
            return myMap;
        } else {
            return VALUE_PREFIX + treeNode.value;
        }
    }
}
