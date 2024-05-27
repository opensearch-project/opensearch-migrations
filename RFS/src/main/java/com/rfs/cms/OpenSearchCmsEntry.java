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

        public static Snapshot getInitial(String name) {
            return new Snapshot(name, CmsEntry.SnapshotStatus.NOT_STARTED);
        }

        public static Snapshot fromJson(ObjectNode node) {
            try {
                return new Snapshot(
                    node.get(FIELD_STATUS).asText(),
                    CmsEntry.SnapshotStatus.valueOf(node.get(FIELD_STATUS).asText())
                );
            } catch (Exception e) {
                throw new CantParseCmsEntryFromJson(Snapshot.class, node.toString(), e);
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

        @Override
        public String toString() {
            return this.toJson().toString();
        }
    }

    public static class Metadata extends CmsEntry.Metadata {
        public static final String FIELD_STATUS = "status";
        public static final String FIELD_LEASE_EXPIRY = "leaseExpiry";
        public static final String FIELD_NUM_ATTEMPTS = "numAttempts";

        public static Metadata getInitial() {
            return new Metadata(
                CmsEntry.MetadataStatus.IN_PROGRESS,
                // TODO: We should be ideally setting the lease using the server's clock, but it's unclear on the best way
                // to do this.  For now, we'll just use the client's clock.
                CmsEntry.Metadata.getLeaseExpiry(Instant.now().toEpochMilli(), 1),
                1
            );
        }

        public static Metadata fromJson(ObjectNode node) {
            try {
                return new Metadata(
                    CmsEntry.MetadataStatus.valueOf(node.get(FIELD_STATUS).asText()),
                    node.get(FIELD_LEASE_EXPIRY).asText(),
                    node.get(FIELD_NUM_ATTEMPTS).asInt()
                );
            } catch (Exception e) {
                throw new CantParseCmsEntryFromJson(Metadata.class, node.toString(), e);
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

        @Override
        public String toString() {
            return this.toJson().toString();
        }
    }

    public static class CantParseCmsEntryFromJson extends RfsException {
        public CantParseCmsEntryFromJson(Class<?> entryClass, String json, Exception e) {
            super("Failed to parse CMS entry of type " + entryClass.getName() + " from JSON: " + json, e);
        }
    }
}
