package org.opensearch.migrations.transformation;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class VersionRange {

    private final Version from;
    private final Version to;

    public boolean inRange(final Version v) {
        return
            this.from.equals(v) ||
            this.to.equals(v) || 
            (this.from.lessThan(v) && this.to.greaterThan(v));
    } 
}
