package org.opensearch.migrations.replay.datatypes;

import com.google.common.base.Objects;

public abstract class UniqueSourceRequestKey {
    public abstract ISourceTrafficChannelKey getTrafficStreamKey();

    public abstract int getSourceRequestIndex();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UniqueSourceRequestKey that = (UniqueSourceRequestKey) o;
        return getSourceRequestIndex() == that.getSourceRequestIndex()
            && Objects.equal(getTrafficStreamKey(), that.getTrafficStreamKey());
    }

    @Override
    public String toString() {
        return getTrafficStreamKey() + "." + getSourceRequestIndex();
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getTrafficStreamKey(), getSourceRequestIndex());
    }
}
