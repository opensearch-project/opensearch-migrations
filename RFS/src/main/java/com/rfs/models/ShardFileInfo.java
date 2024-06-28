package com.rfs.models;

import org.apache.lucene.util.BytesRef;

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

}
