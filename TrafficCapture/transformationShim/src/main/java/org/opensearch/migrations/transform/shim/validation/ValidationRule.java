package org.opensearch.migrations.transform.shim.validation;

import java.util.List;

/**
 * A named validation rule that applies to specific targets.
 * The validator receives the full response map but is expected to compare
 * only the targets listed in {@code targetNames}.
 */
public record ValidationRule(
    String name,
    List<String> targetNames,
    ResponseValidator validator
) {}
