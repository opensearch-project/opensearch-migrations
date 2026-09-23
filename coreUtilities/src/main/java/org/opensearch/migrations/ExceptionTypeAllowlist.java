package org.opensearch.migrations;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.NonNull;

/**
 * Shared exact-match policy for normalized OpenSearch exception type names.
 */
public final class ExceptionTypeAllowlist {
    private final Set<String> allowedTypes;

    public ExceptionTypeAllowlist(@NonNull Collection<String> allowedTypes) {
        this.allowedTypes = allowedTypes.stream()
            .map(ExceptionTypeAllowlist::normalize)
            .collect(Collectors.toUnmodifiableSet());
    }

    public static ExceptionTypeAllowlist empty() {
        return new ExceptionTypeAllowlist(Set.of());
    }

    public boolean isAllowed(String exceptionType) {
        return exceptionType != null && allowedTypes.contains(normalize(exceptionType));
    }

    public Set<String> allowedTypes() {
        return allowedTypes;
    }

    private static String normalize(String exceptionType) {
        var normalized = exceptionType.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Exception type cannot be blank");
        }
        return normalized;
    }
}
