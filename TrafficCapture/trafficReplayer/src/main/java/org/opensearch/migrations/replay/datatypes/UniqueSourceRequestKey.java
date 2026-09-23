package org.opensearch.migrations.replay.datatypes;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

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

*/
// REBUILD-LIMBO-END(G11)