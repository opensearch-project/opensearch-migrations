package com.rfs.cms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class CmsEntry {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static enum SnapshotStatus {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
    }

    public static class Snapshot {
        public static Snapshot fromJsonString(String json) {
            try {
                ObjectNode node = objectMapper.readValue(json, ObjectNode.class);
                ObjectNode sourceNode = (ObjectNode) node.get("_source");

                return new Snapshot(
                    sourceNode.get("name").asText(),
                    SnapshotStatus.valueOf(sourceNode.get("status").asText())
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public final String name;
        public final SnapshotStatus status;

        public Snapshot(String name, SnapshotStatus status) {
            this.name = name;
            this.status = status;
        }

        public ObjectNode toJson() {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("name", name);
            node.put("status", status.toString());
            return node;
        }
    }    
}
