/*
 * SPDX-License-Identifier: Apache-2.0
 * Minimal stub to satisfy KNN codec class loading.
 */
package org.opensearch.index.query;

public abstract class AbstractQueryBuilder<QB extends AbstractQueryBuilder<QB>> implements QueryBuilder {
    public static final float DEFAULT_BOOST = 1.0f;
    protected String queryName;
    protected float boost = DEFAULT_BOOST;

    protected AbstractQueryBuilder() {}

    @Override public String queryName() { return queryName; }
    @Override public float boost() { return boost; }
    public QB queryName(String queryName) { this.queryName = queryName; return (QB) this; }
    public QB boost(float boost) { this.boost = boost; return (QB) this; }
}
