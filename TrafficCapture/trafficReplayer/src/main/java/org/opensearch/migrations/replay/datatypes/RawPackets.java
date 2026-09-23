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

import java.util.ArrayList;
import java.util.Arrays;

public class RawPackets extends ArrayList<byte[]> {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RawPackets)) {
            return false;
        }
        RawPackets that = (RawPackets) o;
        if (size() != that.size()) {
            return false;
        }

        for (int i = 0; i < size(); i++) {
            if (!Arrays.equals(get(i), that.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 29;
        for (byte[] array : this) {
            result = 31 * result + Arrays.hashCode(array);
        }
        return result;
    }
}

*/
// REBUILD-LIMBO-END(G11)