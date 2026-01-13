/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Minimal stub to satisfy KNN codec class loading.
 */
package org.opensearch;

public class OpenSearchParseException extends RuntimeException {
    public OpenSearchParseException(String msg, Object... args) {
        super(String.format(msg.replace("{}", "%s"), args));
    }

    public OpenSearchParseException(String msg, Throwable cause, Object... args) {
        super(String.format(msg.replace("{}", "%s"), args), cause);
    }
}
