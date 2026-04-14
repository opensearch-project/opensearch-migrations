package org.opensearch.migrations;

import java.util.Set;

import lombok.experimental.UtilityClass;

/**
 * Known OpenSearch bulk item error types classified by retry behavior.
 * Shared between the traffic replayer (non-retryable classification) and
 * RFS (document exception allowlist) so both use a consistent vocabulary.
 */
@UtilityClass
public class BulkDocErrorTypes {

    /**
     * Client-side or logical errors that will produce the same failure on every attempt.
     * <p>
     * Used as:
     * <ul>
     *   <li>Replayer: default {@code --non-retryable-doc-exception-types} (don't retry, still a failure)</li>
     *   <li>RFS: available for {@code --allowed-doc-exception-types} (don't retry, treat as success)</li>
     * </ul>
     */
    public static final Set<String> NON_RETRYABLE = Set.of(
        "version_conflict_engine_exception",
        "mapper_parsing_exception",
        "strict_dynamic_mapping_exception",
        "document_missing_exception",
        "action_request_validation_exception",
        "invalid_index_name_exception",
        "routing_missing_exception",
        "illegal_argument_exception",
        "resource_already_exists_exception"
    );
}
