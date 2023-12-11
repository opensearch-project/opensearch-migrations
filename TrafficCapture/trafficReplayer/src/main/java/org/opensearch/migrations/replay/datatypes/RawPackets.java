package org.opensearch.migrations.replay.datatypes;

import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.Arrays;


public class RawPackets extends ArrayList<byte[]> {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RawPackets)) { return false; }
        RawPackets that = (RawPackets) o;
        if (size() != that.size()) { return false; }

        for (int i = 0; i < size(); i++) {
            if (!Arrays.equals(get(i), that.get(i))) {
                return false;
            }
        }
        return true;
    }
}
