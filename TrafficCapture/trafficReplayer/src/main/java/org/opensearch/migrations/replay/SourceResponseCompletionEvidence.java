/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;

/**
 * The boundary that ended source-response accumulation.
 */
public enum SourceResponseCompletionEvidence {
    HTTP_FRAMING(true),
    NEXT_REQUEST(true),
    CONNECTION_CLOSE(false),
    CONNECTION_EXCEPTION(false);

    private final boolean provenComplete;

    SourceResponseCompletionEvidence(boolean provenComplete) {
        this.provenComplete = provenComplete;
    }

    public boolean provenComplete() {
        return provenComplete;
    }
}
