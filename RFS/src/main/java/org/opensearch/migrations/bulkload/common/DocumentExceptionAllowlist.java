package org.opensearch.migrations.bulkload.common;

import java.util.Collections;
import java.util.Set;

import lombok.Value;

/**
 * Configuration for document-level exceptions that should be treated as successful operations.
 * This allows migrations to proceed when encountering expected errors like version conflicts.
 */
@Value
public class DocumentExceptionAllowlist {
    Set<String> allowedExceptionTypes;
    
    public static DocumentExceptionAllowlist empty() {
        return new DocumentExceptionAllowlist(Collections.emptySet());
    }
    
    public boolean isAllowed(String exceptionType) {
        return allowedExceptionTypes.contains(exceptionType);
    }
}
