/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Stub class to satisfy KNN codec class loading.
 */
package org.opensearch.common;

@FunctionalInterface
public interface TriFunction<S, T, U, R> {
    R apply(S s, T t, U u);
}
