/*
 * SPDX-License-Identifier: Apache-2.0
 * Stub to satisfy KNN codec class loading.
 */
package org.opensearch.common;

import java.util.ArrayList;
import java.util.List;

public class ValidationException extends IllegalArgumentException {
    private final List<String> validationErrors = new ArrayList<>();

    public ValidationException() { super("validation failed"); }

    public final void addValidationError(String error) { validationErrors.add(error); }
    public final void addValidationErrors(Iterable<String> errors) { for (String e : errors) validationErrors.add(e); }
    public final List<String> validationErrors() { return validationErrors; }

    @Override
    public final String getMessage() {
        StringBuilder sb = new StringBuilder("Validation Failed: ");
        int i = 0;
        for (String error : validationErrors) sb.append(++i).append(": ").append(error).append(";");
        return sb.toString();
    }
}
