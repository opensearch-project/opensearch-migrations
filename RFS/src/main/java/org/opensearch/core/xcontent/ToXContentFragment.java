/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Stub interface to satisfy KNN codec class loading.
 */
package org.opensearch.core.xcontent;

public interface ToXContentFragment extends ToXContent {
    @Override
    default boolean isFragment() { return true; }
}
