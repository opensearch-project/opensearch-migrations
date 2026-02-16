package org.opensearch.migrations.bulkload.models;

import java.util.Objects;

import lombok.Value;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

/**
 * Defines the behavior expected of an object that will surface the metadata of an file stored in a snapshot
 * See: https://github.com/elastic/elasticsearch/blob/7.10/server/src/main/java/org/elasticsearch/index/snapshots/blobstore/BlobStoreIndexShardSnapshot.java#L277
 * See: https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/index/snapshots/blobstore/BlobStoreIndexShardSnapshot.java#L281
 */
public interface ShardFileInfo {

    public String getName();

    public String getPhysicalName();

    public long getLength();

    public String getChecksum();

    public long getPartSize();

    public String getWrittenBy();

    public BytesRef getMetaHash();

    public long getNumberOfParts();

    public String partName(long part);

    public default ShardFileKey key() {
        return new ShardFileKey(getName(), getPhysicalName(), getChecksum());
    }

    @SuppressWarnings("java:S1210")
    @Value
    class ShardFileKey implements Comparable<ShardFileKey> {
        String name;
        String physicalName;
        String checksum;

        @Override
        public int compareTo(ShardFileKey other) {
            if (other == null) return 1;
            int cmp = compareNullable(name, other.name);
            if (cmp != 0) return cmp;
            cmp = compareNullable(physicalName, other.physicalName);
            if (cmp != 0) return cmp;
            return compareNullable(checksum, other.checksum);
        }

        private static int compareNullable(String a, String b) {
            if (Objects.equals(a, b)) return 0;
            if (a == null) return -1;
            if (b == null) return 1;
            return a.compareTo(b);
        }    }
}
