package com.rfs.cms;

import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rfs.common.RfsException;

public class OpenSearchCmsEntry {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static class Snapshot extends CmsEntry.Snapshot {
        public static final String FIELD_TYPE = "type";
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

        public Snapshot(CmsEntry.Snapshot entry) {
            this(entry.name, entry.status);
        }

        public ObjectNode toJson() {
            ObjectNode node = objectMapper.createObjectNode();
            node.put(FIELD_TYPE, type.toString());
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
        public static final String FIELD_TYPE = "type";
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

        public Metadata(CmsEntry.Metadata entry) {
            this(entry.status, entry.leaseExpiry, entry.numAttempts);
        }

        public ObjectNode toJson() {
            ObjectNode node = objectMapper.createObjectNode();
            node.put(FIELD_TYPE, type.toString());
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

    public static class Index extends CmsEntry.Index {
        public static final String FIELD_TYPE = "type";
        public static final String FIELD_STATUS = "status";
        public static final String FIELD_LEASE_EXPIRY = "leaseExpiry";
        public static final String FIELD_NUM_ATTEMPTS = "numAttempts";

        public static Index getInitial() {
            return new Index(
                CmsEntry.IndexStatus.SETUP,
                // TODO: We should be ideally setting the lease using the server's clock, but it's unclear on the best way
                // to do this.  For now, we'll just use the client's clock.
                CmsEntry.Index.getLeaseExpiry(Instant.now().toEpochMilli(), 1),
                1
            );
        }

        public static Index fromJson(ObjectNode node) {
            try {
                return new Index(
                    CmsEntry.IndexStatus.valueOf(node.get(FIELD_STATUS).asText()),
                    node.get(FIELD_LEASE_EXPIRY).asText(),
                    node.get(FIELD_NUM_ATTEMPTS).asInt()
                );
            } catch (Exception e) {
                throw new CantParseCmsEntryFromJson(Index.class, node.toString(), e);
            }
        }

        public Index(CmsEntry.IndexStatus status, String leaseExpiry, Integer numAttempts) {
            super(status, leaseExpiry, numAttempts);
        }

        public Index(CmsEntry.Index entry) {
            this(entry.status, entry.leaseExpiry, entry.numAttempts);
        }

        public ObjectNode toJson() {
            ObjectNode node = objectMapper.createObjectNode();
            node.put(FIELD_TYPE, type.toString());
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

    public static class IndexWorkItem extends CmsEntry.IndexWorkItem {
        public static final String FIELD_TYPE = "type";
        public static final String FIELD_NAME = "name";
        public static final String FIELD_STATUS = "status";
        public static final String FIELD_NUM_ATTEMPTS = "numAttempts";
        public static final String FIELD_NUM_SHARDS = "numShards";

        public static IndexWorkItem getInitial(String name, int numShards) {
            return new IndexWorkItem(
                name,
                CmsEntry.IndexWorkItemStatus.NOT_STARTED,
                1,
                numShards
            );
        }

        public static IndexWorkItem fromJson(ObjectNode node) {
            try {
                return new IndexWorkItem(
                    node.get(FIELD_NAME).asText(),
                    CmsEntry.IndexWorkItemStatus.valueOf(node.get(FIELD_STATUS).asText()),
                    node.get(FIELD_NUM_ATTEMPTS).asInt(),
                    node.get(FIELD_NUM_SHARDS).asInt()
                );
            } catch (Exception e) {
                throw new CantParseCmsEntryFromJson(IndexWorkItem.class, node.toString(), e);
            }
        }

        public IndexWorkItem(String name, CmsEntry.IndexWorkItemStatus status, Integer numAttempts, Integer numShards) {
            super(name, status, numAttempts, numShards);
        }

        public IndexWorkItem(CmsEntry.IndexWorkItem entry) {
            this(entry.name, entry.status, entry.numAttempts, entry.numShards);
        }

        public ObjectNode toJson() {
            ObjectNode node = objectMapper.createObjectNode();
            node.put(FIELD_TYPE, type.toString());
            node.put(FIELD_NAME, name);
            node.put(FIELD_STATUS, status.toString());
            node.put(FIELD_NUM_ATTEMPTS, numAttempts);
            node.put(FIELD_NUM_SHARDS, numShards);
            return node;
        }

        @Override
        public String toString() {
            return this.toJson().toString();
        }
    }

    public static class Documents extends CmsEntry.Documents {
        public static final String FIELD_TYPE = "type";
        public static final String FIELD_STATUS = "status";
        public static final String FIELD_LEASE_EXPIRY = "leaseExpiry";
        public static final String FIELD_NUM_ATTEMPTS = "numAttempts";

        public static Documents getInitial() {
            return new Documents(
                CmsEntry.DocumentsStatus.SETUP,
                // TODO: We should be ideally setting the lease using the server's clock, but it's unclear on the best way
                // to do this.  For now, we'll just use the client's clock.
                CmsEntry.Documents.getLeaseExpiry(Instant.now().toEpochMilli(), 1),
                1
            );
        }

        public static Documents fromJson(ObjectNode node) {
            try {
                return new Documents(
                    CmsEntry.DocumentsStatus.valueOf(node.get(FIELD_STATUS).asText()),
                    node.get(FIELD_LEASE_EXPIRY).asText(),
                    node.get(FIELD_NUM_ATTEMPTS).asInt()
                );
            } catch (Exception e) {
                throw new CantParseCmsEntryFromJson(Documents.class, node.toString(), e);
            }
        }

        public Documents(CmsEntry.DocumentsStatus status, String leaseExpiry, Integer numAttempts) {
            super(status, leaseExpiry, numAttempts);
        }

        public Documents(CmsEntry.Documents entry) {
            this(entry.status, entry.leaseExpiry, entry.numAttempts);
        }

        public ObjectNode toJson() {
            ObjectNode node = objectMapper.createObjectNode();
            node.put(FIELD_TYPE, type.toString());
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

    public static class DocumentsWorkItem extends CmsEntry.DocumentsWorkItem {
        public static final String FIELD_TYPE = "type";
        public static final String FIELD_INDEX_NAME = "indexName";
        public static final String FIELD_SHARD_ID = "shardId";
        public static final String FIELD_STATUS = "status";
        public static final String FIELD_LEASE_EXPIRY = "leaseExpiry";
        public static final String FIELD_NUM_ATTEMPTS = "numAttempts";

        public static DocumentsWorkItem getInitial(String indexName, int shardId) {
            return new DocumentsWorkItem(
                indexName,
                shardId,
                CmsEntry.DocumentsWorkItemStatus.NOT_STARTED,
                // TODO: We should be ideally setting the lease using the server's clock, but it's unclear on the best way
                // to do this.  For now, we'll just use the client's clock.
                CmsEntry.Documents.getLeaseExpiry(Instant.now().toEpochMilli(), 1),
                1
            );
        }

        public static DocumentsWorkItem fromJson(ObjectNode node) {
            try {
                return new DocumentsWorkItem(
                    node.get(FIELD_INDEX_NAME).asText(),
                    node.get(FIELD_SHARD_ID).asInt(),
                    CmsEntry.DocumentsWorkItemStatus.valueOf(node.get(FIELD_STATUS).asText()),
                    node.get(FIELD_LEASE_EXPIRY).asText(),
                    node.get(FIELD_NUM_ATTEMPTS).asInt()
                );
            } catch (Exception e) {
                throw new CantParseCmsEntryFromJson(DocumentsWorkItem.class, node.toString(), e);
            }
        }

        public DocumentsWorkItem(String indexName, int shardId, CmsEntry.DocumentsWorkItemStatus status, String leaseExpiry, int numAttempts) {
            super(indexName, shardId, status, leaseExpiry, numAttempts);
        }

        public DocumentsWorkItem(CmsEntry.DocumentsWorkItem entry) {
            this(entry.indexName, entry.shardId, entry.status, entry.leaseExpiry, entry.numAttempts);
        }

        public ObjectNode toJson() {
            ObjectNode node = objectMapper.createObjectNode();
            node.put(FIELD_TYPE, type.toString());
            node.put(FIELD_INDEX_NAME, indexName);
            node.put(FIELD_SHARD_ID, shardId);
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
