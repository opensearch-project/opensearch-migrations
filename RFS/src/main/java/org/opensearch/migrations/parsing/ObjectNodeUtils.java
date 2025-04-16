package org.opensearch.migrations.parsing;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class ObjectNodeUtils {
    private ObjectNodeUtils() {}

    public static void removeFieldsByPath(ObjectNode node, String path) {
        if (node == null) {
            return;
        }

        var pathParts = path.split("\\.");

        if (pathParts.length == 1) {
            node.remove(pathParts[0]);
            return;
        }

        var currentNode = node;
        for (int i = 0; i < pathParts.length - 1; i++) {
            var nextNode = currentNode.get(pathParts[i]);
            if (nextNode != null && nextNode.isObject()) {
                currentNode = (ObjectNode) nextNode;
            } else {
                return;
            }
        }
        currentNode.remove(pathParts[pathParts.length - 1]);
    }
}
