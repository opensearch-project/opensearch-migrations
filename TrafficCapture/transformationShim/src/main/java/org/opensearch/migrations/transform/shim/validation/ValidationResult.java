package org.opensearch.migrations.transform.shim.validation;

/** The result of a single validation rule execution. */
public record ValidationResult(String ruleName, boolean passed, String detail) {}
