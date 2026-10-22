package org.opensearch.migrations.bulkload.common;

import java.util.Set;

import org.opensearch.migrations.ExceptionTypeAllowlist;

import lombok.Value;

/**
 * Configuration for document-level exceptions that should be treated as successful operations.
 * This allows migrations to proceed when encountering expected errors like version conflicts.
 */
@Value
public class DocumentExceptionAllowlist {
    ExceptionTypeAllowlist exceptionTypes;

    public DocumentExceptionAllowlist(Set<String> allowedExceptionTypes) {
        exceptionTypes = new ExceptionTypeAllowlist(allowedExceptionTypes);
    }
    
    public static DocumentExceptionAllowlist empty() {
        return new DocumentExceptionAllowlist(Set.of());
    }
    
    public boolean isAllowed(String exceptionType) {
        return exceptionTypes.isAllowed(exceptionType);
    }

    public Set<String> getAllowedExceptionTypes() {
        return exceptionTypes.allowedTypes();
    }
}
