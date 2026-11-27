# Maps `console --json backfill status --deep-check` output to SnapshotMigration.status.documentBackfill.
#
# Input: that JSON, or {} when it isn't available. Args: --arg phase, --arg message, --arg updatedAt, and
# --argjson previous: the documentBackfill this replaces, or null.
#
# The monitor writes the result as the whole of status.documentBackfill on every refresh, so a field not
# produced here is dropped rather than left over from an earlier refresh. The one thing carried over is the
# failed-document count: when this refresh couldn't count (documents_failed is null), the last count stays,
# with failures.countedAt still saying when it was taken. Counts only grow, so it's a lower bound.
#
# failures.shards and failures.indices keep the 20 with the most failed documents; shardCount and
# indexCount say how many there are in all (`console backfill status --deep-check` lists every shard).
def shard_failures:
    (. // []) as $shards
    | {
        shardCount: ($shards | length),
        shards: ($shards | .[:20]
                 | map({index, shard, nonRetryable: .non_retryable, retriesExhausted: .retries_exhausted})),
        indexCount: ($shards | map(.index) | unique | length),
        indices: ($shards | group_by(.index)
                  | map({index: .[0].index,
                         nonRetryable: (map(.non_retryable) | add),
                         retriesExhausted: (map(.retries_exhausted) | add)})
                  | sort_by(-(.nonRetryable + .retriesExhausted), .index) | .[:20])
      };

(.documents_failed != null) as $counted
| ($previous // {}) as $prev
| (if $counted then
       {documentsFailed: .documents_failed,
        documentsFailedNonRetryable: .documents_failed_non_retryable,
        documentsFailedRetriesExhausted: .documents_failed_retries_exhausted}
   else
       ($prev.summary // {}) | {documentsFailed, documentsFailedNonRetryable, documentsFailedRetriesExhausted}
   end) as $counts
| (if $counted then (.shards_with_failures | shard_failures) + {countedAt: $updatedAt}
   else ($prev.failures // {}) | {shardCount: (.shardCount // 0), shards: (.shards // []),
                                  indexCount: (.indexCount // 0), indices: (.indices // []),
                                  countedAt}
   end) as $failures
| {
    phase: $phase,
    updatedAt: $updatedAt,
    message: $message,
    summary: ({
        percentageCompleted: (.percentage_completed // 0),
        etaMs: .eta_ms,
        started: .started,
        finished: .finished,
        shardsTotal: (.shard_total // 0),
        shardsMigrated: (.shard_complete // 0),
        shardsInProgress: (.shard_in_progress // 0),
        shardsWaiting: (.shard_waiting // 0)
    } + $counts),
    failures: ($failures + {
        countError: .failed_documents_count_error,
        streamLocation: .failed_document_stream_location,
        streamHasRecords: .failed_documents_present
    })
}
