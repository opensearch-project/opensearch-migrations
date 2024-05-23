package com.rfs.cms;

import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.common.RfsException;

public class OpenSearchCmsEntry {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static class Snapshot extends CmsEntry.Snapshot {
        public static final String FIELD_NAME = "name";
        public static final String FIELD_STATUS = "status";

        public static ObjectNode getInitial(String name) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put(FIELD_STATUS, name);
            node.put(FIELD_STATUS, CmsEntry.SnapshotStatus.NOT_STARTED.toString());
            return node;
        }

        public static Snapshot fromJsonString(String json) {
            try {
                ObjectNode node = objectMapper.readValue(json, ObjectNode.class);
                ObjectNode sourceNode = (ObjectNode) node.get("_source");

                return new Snapshot(
                    sourceNode.get(FIELD_STATUS).asText(),
                    CmsEntry.SnapshotStatus.valueOf(sourceNode.get(FIELD_STATUS).asText())
                );
            } catch (Exception e) {
                throw new CantParseCmsEntryFromJson(Snapshot.class, json, e);
            }
        }

        public Snapshot(String name, CmsEntry.SnapshotStatus status) {
            super(name, status);
        }

        public ObjectNode toJson() {
            ObjectNode node = objectMapper.createObjectNode();
            node.put(FIELD_STATUS, name);
            node.put(FIELD_STATUS, status.toString());
            return node;
        }
    }

    public static class Metadata extends CmsEntry.Metadata {
        public static final String FIELD_STATUS = "status";
        public static final String FIELD_LEASE_EXPIRY = "leaseExpiry";
        public static final String FIELD_NUM_ATTEMPTS = "numAttempts";

        public static ObjectNode getInitial() {
            ObjectNode metadataDoc = objectMapper.createObjectNode();
            metadataDoc.put(FIELD_STATUS, CmsEntry.MetadataStatus.IN_PROGRESS.toString());
            metadataDoc.put(FIELD_NUM_ATTEMPTS, 1);

            // TODO: We should be ideally setting the lease using the server's clock, but it's unclear on the best way
            // to do this.  For now, we'll just use the client's clock.
            metadataDoc.put(FIELD_LEASE_EXPIRY, CmsEntry.Metadata.getLeaseExpiry(Instant.now().toEpochMilli(), 1));

            return metadataDoc;
        }

        public static Metadata fromJsonString(String json) {
            try {
                ObjectNode node = objectMapper.readValue(json, ObjectNode.class);
                ObjectNode sourceNode = (ObjectNode) node.get("_source");

                return new Metadata(
                    CmsEntry.MetadataStatus.valueOf(sourceNode.get(FIELD_STATUS).asText()),
                    sourceNode.get(FIELD_LEASE_EXPIRY).asText(),
                    sourceNode.get(FIELD_NUM_ATTEMPTS).asInt()
                );
            } catch (Exception e) {
                throw new CantParseCmsEntryFromJson(Metadata.class, json, e);
            }
        }

        public Metadata(CmsEntry.MetadataStatus status, String leaseExpiry, Integer numAttempts) {
            super(status, leaseExpiry, numAttempts);
        }

        public ObjectNode toJson() {
            ObjectNode node = objectMapper.createObjectNode();
            node.put(FIELD_STATUS, status.toString());
            node.put(FIELD_LEASE_EXPIRY, leaseExpiry);
            node.put(FIELD_NUM_ATTEMPTS, numAttempts);
            return node;
        }
    }

    public static class CantParseCmsEntryFromJson extends RfsException {
        public CantParseCmsEntryFromJson(Class<?> entryClass, String json, Exception e) {
            super("Failed to parse CMS entry of type " + entryClass.getName() + " from JSON: " + json, e);
        }
    }
}
