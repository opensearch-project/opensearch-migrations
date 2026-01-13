/*
 * SPDX-License-Identifier: Apache-2.0
 * Minimal stub to satisfy KNN codec class loading.
 */
package org.opensearch.core;

public class ParseField {
    private final String name;

    public ParseField(String name, String... deprecatedNames) {
        this.name = name;
    }

    public String getPreferredName() { return name; }
    public String[] getAllNamesIncludedDeprecated() { return new String[]{name}; }
    public ParseField withDeprecation(String... deprecatedNames) { return this; }
    public ParseField withAllDeprecated(String allReplacedWith) { return this; }
    public ParseField withAllDeprecated() { return this; }
    public String toString() { return name; }
}
