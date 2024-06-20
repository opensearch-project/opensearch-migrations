package com.rfs.models;

import org.apache.lucene.util.BytesRef;

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
