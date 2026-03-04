package org.opensearch.migrations.transform.shim.validation;

import java.util.Map;

/** Validates across one or more target responses. */
@FunctionalInterface
public interface ResponseValidator {
    ValidationResult validate(Map<String, TargetResponse> responses);
}
