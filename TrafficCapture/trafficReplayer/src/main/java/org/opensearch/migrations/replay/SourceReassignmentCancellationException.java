package org.opensearch.migrations.replay;

import java.util.concurrent.CancellationException;

final class SourceReassignmentCancellationException extends CancellationException {
    private static final long serialVersionUID = 1L;

    SourceReassignmentCancellationException(String message) {
        super(message);
    }
}
