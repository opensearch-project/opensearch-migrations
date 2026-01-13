/*
 * SPDX-License-Identifier: Apache-2.0
 * Minimal stub to satisfy KNN codec class loading.
 */
package org.opensearch.index.query;

public interface QueryBuilder {
    String queryName();
    float boost();
}
