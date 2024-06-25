package org.opensearch.migrations.transformation;

import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

@RequiredArgsConstructor
@ToString
@EqualsAndHashCode
public class Version implements Comparable<Version> {
    private final Product product;
    private final int major;
    private final int minor;
    private final int patch;

    public enum Product {
        OPENSEARCH,
        ELASTICSEARCH;
    }

    public boolean greaterThan(final Version that) {
        return this.compareTo(that) > 0;
    }

    public boolean lessThan(final Version that) {
        return this.compareTo(that) < 0;
    }

    @Override
    public int compareTo(final Version that) {
        if (that == null) {
            throw new IllegalArgumentException("Cannot compare against null");
        }
        if (this.product != that.product) {
            throw new IllegalArgumentException("Cannot compare across products, this " + this + " , compared to " + that);
        }

        if (this.major > that.major) {
            return 1;
        } else if (this.major < that.major) {
            return -1;
        }

        if (this.minor > that.minor) {
            return 1;
        } else if (this.minor < that.minor) {
            return -1;
        }

        if (this.patch > that.patch) {
            return 1;
        } else if (this.patch < that.patch) {
            return -1;
        }

        return 0;
    }
}
