# Maps `console --json backfill status --deep-check` output to SnapshotMigration.status.documentBackfill.
#
# Input: that JSON, or {} when it isn't available. Args: --arg phase, --arg message, --arg updatedAt.
# The monitor writes the result as the whole of status.documentBackfill on every refresh, so a field
# not produced here is dropped rather than left over from an earlier refresh.
#
# failures.shards and failures.indices keep the 20 with the most failed documents; shardCount and
# indexCount say how many there are in all (`console backfill status --deep-check` lists every shard).
(.shards_with_failures // []) as $shards
| {
    phase: $phase,
    updatedAt: $updatedAt,
    message: $message,
    summary: {
        percentageCompleted: (.percentage_completed // 0),
        etaMs: .eta_ms,
        started: .started,
        finished: .finished,
        shardsTotal: (.shard_total // 0),
        shardsMigrated: (.shard_complete // 0),
        shardsInProgress: (.shard_in_progress // 0),
        shardsWaiting: (.shard_waiting // 0),
        documentsFailed: .documents_failed,
        documentsFailedNonRetryable: .documents_failed_non_retryable,
        documentsFailedRetriesExhausted: .documents_failed_retries_exhausted
    },
    failures: {
        shardCount: ($shards | length),
        shards: ($shards | .[:20]
                 | map({index, shard, nonRetryable: .non_retryable, retriesExhausted: .retries_exhausted})),
        indexCount: ($shards | map(.index) | unique | length),
        indices: ($shards | group_by(.index)
                  | map({index: .[0].index,
                         nonRetryable: (map(.non_retryable) | add),
                         retriesExhausted: (map(.retries_exhausted) | add)})
                  | sort_by(-(.nonRetryable + .retriesExhausted), .index) | .[:20]),
        streamLocation: .failed_document_stream_location,
        streamHasRecords: .failed_documents_present
    }
}
