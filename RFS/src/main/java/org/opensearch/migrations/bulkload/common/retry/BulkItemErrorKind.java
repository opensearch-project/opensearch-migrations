package org.opensearch.migrations.bulkload.common.retry;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.BulkDocErrorTypes;

/**
 * What a bulk item's error type says, and so what is done with the document. The kind comes from the error's whole
 * chain (its {@code type}, then each nested {@code caused_by} type), first match wins:
 * <ol>
 *   <li>{@link #THROTTLE} if any type is in {@link #THROTTLE_TYPES}: the target is too busy.</li>
 *   <li>{@link #UNAVAILABLE} if any type is in {@link #UNAVAILABLE_TYPES}: a shard, node or the cluster can't take
 *       writes right now.</li>
 *   <li>{@link #DOCUMENT} if any type is in {@link #DOCUMENT_TYPES}: the document fails the same way every time.</li>
 *   <li>{@link #UNKNOWN} otherwise.</li>
 * </ol>
 * The target's state is checked first: a circuit breaker that trips while a document is parsed is reported as the
 * cause of a {@code mapper_parsing_exception}, and is still about the target. Names are OpenSearch's (the exception
 * class's simple name, without an {@code OpenSearch} prefix, in snake case) and, for Elasticsearch targets,
 * Elasticsearch's where they differ. Status codes don't separate the kinds: several target-state errors are 4xx
 * ({@code shard_not_found_exception} and {@code illegal_index_shard_state_exception} are 404,
 * {@code index_closed_exception} 400, {@code cluster_block_exception} 403 or 429).
 */
public enum BulkItemErrorKind {
    /** The target is too busy: retried until it succeeds, never counted against the document, cuts the limit. */
    THROTTLE,
    /** The target can't take the write right now: retried until it succeeds, never counted against the document. */
    UNAVAILABLE,
    /** The document fails the same way on every attempt: recorded in the failed document stream at once. */
    DOCUMENT,
    /** Neither: retried, and given up after a fixed number of failures. */
    UNKNOWN;

    /** Write queues full ({@code es_} is Elasticsearch's prefix), or the heap too full to take the request. */
    public static final Set<String> THROTTLE_TYPES = Set.of(
        "es_rejected_execution_exception",
        "rejected_execution_exception",
        "circuit_breaking_exception"
    );

    /** Shards, nodes or the cluster manager that aren't available, and blocks on writes. */
    public static final Set<String> UNAVAILABLE_TYPES = Set.of(
        // A shard that isn't active, isn't allocated, or is moving.
        "unavailable_shards_exception",
        "no_shard_available_action_exception",
        "primary_missing_action_exception",
        "shard_not_found_exception",
        "illegal_index_shard_state_exception",
        "index_shard_closed_exception",
        "index_shard_not_started_exception",
        "index_shard_not_recovering_exception",
        "primary_shard_closed_exception",
        "shard_not_in_primary_mode_exception",
        "shard_lock_obtain_failed_exception",
        "index_primary_shard_not_allocated_exception",
        "retry_on_primary_exception",
        "retry_on_replica_exception",
        // A node that left or can't be reached, or no elected cluster manager (and the pre-2.0 names).
        "node_not_connected_exception",
        "node_disconnected_exception",
        "node_closed_exception",
        "connect_transport_exception",
        "send_request_transport_exception",
        "receive_timeout_transport_exception",
        "no_node_available_exception",
        "cluster_manager_not_discovered_exception",
        "master_not_discovered_exception",
        "not_cluster_manager_exception",
        "not_master_exception",
        "process_cluster_event_timeout_exception",
        // Writes blocked: a read-only index or cluster, the disk flood stage, or a closed index.
        "cluster_block_exception",
        "index_closed_exception"
    );

    /**
     * {@link BulkDocErrorTypes#NON_RETRYABLE}, shared with the replayer, plus types that fail the same way on every
     * attempt in a backfill. A missing index is one: the backfill created the indices it writes to, so a document
     * routed to another index (by a transform, say) will never find it.
     */
    public static final Set<String> DOCUMENT_TYPES = Stream.concat(BulkDocErrorTypes.NON_RETRYABLE.stream(), Stream.of(
        "index_not_found_exception",
        "parse_exception",
        "x_content_parse_exception",
        "json_parse_exception",
        "number_format_exception",
        "document_parsing_exception"
    )).collect(Collectors.toUnmodifiableSet());

    public static BulkItemErrorKind of(Collection<String> errorTypeChain) {
        if (errorTypeChain.stream().anyMatch(THROTTLE_TYPES::contains)) {
            return THROTTLE;
        }
        if (errorTypeChain.stream().anyMatch(UNAVAILABLE_TYPES::contains)) {
            return UNAVAILABLE;
        }
        if (errorTypeChain.stream().anyMatch(DOCUMENT_TYPES::contains)) {
            return DOCUMENT;
        }
        return UNKNOWN;
    }

    /** Whether the target, not the document, is the reason: never counted against the document. */
    public boolean isTargetState() {
        return this == THROTTLE || this == UNAVAILABLE;
    }
}
