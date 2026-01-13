/*
 * SPDX-License-Identifier: Apache-2.0
 * Minimal stub to satisfy KNN codec class loading.
 */
package org.opensearch.common.settings;

import java.util.List;
import java.util.Collections;

public class Settings {
    public static final Settings EMPTY = new Settings();

    public String get(String key) { return null; }
    public String get(String key, String defaultValue) { return defaultValue; }
    public List<String> getAsList(String key, List<String> defaultValue) { return defaultValue != null ? defaultValue : Collections.emptyList(); }
    public Settings getByPrefix(String prefix) { return EMPTY; }

    public static class Builder {
        public Builder put(String key, String value) { return this; }
        public Builder putList(String key, List<String> values) { return this; }
        public Settings build() { return EMPTY; }
    }

    public static Builder builder() { return new Builder(); }
}
