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

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

public interface ISourceTrafficChannelKey {
    String getNodeId();

    String getConnectionId();

    default int getSourceGeneration() {
        return 0;
    }

    @Getter
    @AllArgsConstructor
    @EqualsAndHashCode
    class PojoImpl implements ISourceTrafficChannelKey {
        String nodeId;
        String connectionId;
    }
}

*/
// REBUILD-LIMBO-END(G11)