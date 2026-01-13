/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Minimal stub to satisfy KNN codec class loading.
 */
package org.opensearch.core.action;

public interface ActionListener<Response> {
    void onResponse(Response response);
    void onFailure(Exception e);
}
