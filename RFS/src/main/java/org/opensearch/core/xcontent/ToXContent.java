/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Stub interface to satisfy KNN codec class loading.
 */
package org.opensearch.core.xcontent;

import java.io.IOException;

public interface ToXContent {
    interface Params {
        String param(String key);
        String param(String key, String defaultValue);
        boolean paramAsBoolean(String key, boolean defaultValue);
        Boolean paramAsBoolean(String key, Boolean defaultValue);
    }

    Params EMPTY_PARAMS = new Params() {
        @Override public String param(String key) { return null; }
        @Override public String param(String key, String defaultValue) { return defaultValue; }
        @Override public boolean paramAsBoolean(String key, boolean defaultValue) { return defaultValue; }
        @Override public Boolean paramAsBoolean(String key, Boolean defaultValue) { return defaultValue; }
    };

    XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException;

    default boolean isFragment() { return true; }
}
