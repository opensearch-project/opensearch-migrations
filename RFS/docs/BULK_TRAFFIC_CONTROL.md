# Bulk Traffic Control and Failure Handling in RFS

How a Reindex-from-Snapshot (RFS) worker decides how hard to push the target cluster, how it reacts when the target
pushes back or fails, and when it gives up on a document and records it as failed. Failures belong here alongside the
rate: some are the signals the rate reacts to (throttles, response timeouts), some mean the target can't take work
right now (the worker waits), and the rest are a document's own (recorded, and the migration moves past them).

Each rule here is stated with the definitions used by the Javadoc of the class that implements it; if the two ever
disagree, the code is right and this document is a bug. How to inspect and manage the failed document stream is in
[failedDocumentStream.md](../../docs/failedDocumentStream.md); the per-work-item totals a backfill commits are in
[committedMetrics.md](../../docs/committedMetrics.md).

Everything below is per worker process and per target. Workers don't coordinate with each other: they share a target
the way independent TCP senders share a link, each reacting only to what its own requests see.

## Contents

- [Goals](#goals)
- [Vocabulary](#vocabulary)
- [Every decision at a glance](#every-decision-at-a-glance)
- [Overview](#overview)
- [Settings](#settings)
- [Batches](#batches)
- [The in-flight limit](#the-in-flight-limit)
- [Classifying an attempt](#classifying-an-attempt)
- [Waiting before a retry](#waiting-before-a-retry)
- [The retry budget](#the-retry-budget)
- [Splitting a batch](#splitting-a-batch)
- [Giving up on a document](#giving-up-on-a-document)
- [Credentials the target refuses](#credentials-the-target-refuses)
- [Time: the response timeout and the lease](#time-the-response-timeout-and-the-lease)
- [Ordering and starvation](#ordering-and-starvation)
- [Failed-document records](#failed-document-records)
- [Metrics and logs](#metrics-and-logs)
- [Tuning](#tuning)
- [Deferred work](#deferred-work)
- [References](#references)

## Goals

1. **Use what the target can take, and no more.** Throughput should rise until the target pushes back, and fall as
   soon as it does, without an operator choosing a request rate.
2. **A struggling target is never a reason to fail a document.** Throttles, unavailable shards or nodes, blocked
   writes, refused connections, and failures that many requests see at once mean the target is the problem. The
   worker freezes and waits for it, with metrics and WARN logs to alarm on; it doesn't move documents to the failed
   document stream, which would only have to be replayed later.
3. **A document that fails on its own is recorded and the migration moves on.** A document that fails while the
   target accepts others is set aside in the failed document stream after a small, fixed number of retries, with
   enough to investigate and replay it, and its batch checkpoints.
4. **Unknown failures are bounded.** When the worker can't recognize a failure as target state or as a document
   error, it retries a fixed number of times, splits a multi-document batch to isolate the cause, then records a
   persistently failing single operation and moves on.
5. **A broken workload is evident early.** A broken mapping, missing index or bad transform fails at full speed, every
   failure recorded with its reason, rather than every healthy shard being slowed to soften the news
   ([Correlated terminal failure](#correlated-terminal-failure)).
6. **Each control has one job.** The in-flight limit protects the target from load; the retry budget bounds the
   traffic that failing retries generate. Each has an explicit scope and unit.

## Vocabulary

- A **source document** is one input operation read from a source partition.
- A **target operation** is one transformed bulk operation sent or intended for the target. One source document may
  produce several target operations.
- An **attempt** is one HTTP bulk request. A retry is a new attempt.
- A **terminal failure** is a target operation RFS will no longer retry. It produces, or would produce when storage
  is disabled, one failed-document record.
- **Target state** is a retryable condition attributed to the target, such as throttling or unavailable shards.

Accounting is per target operation, not per source document. If one source document fans out into two operations
and both fail terminally, RFS records and counts two failures.

## Every decision at a glance

Every decision the worker makes about traffic, what it looks at, and where it's specified:

| Decision | Inputs | Section |
|---|---|---|
| How many requests may be on the wire | Throttles (429, rejected items, circuit breaker) and response timeouts cut the limit; successes that used the whole limit grow it | [The in-flight limit](#the-in-flight-limit) |
| What a failed attempt is | Status code; body's `cluster_block_exception`; each item's error type chain; whether a connection was made or the response timed out | [Classifying an attempt](#classifying-an-attempt) |
| How long to wait before a retry | Retries so far; `Retry-After` | [Waiting before a retry](#waiting-before-a-retry) |
| Whether a retry may go now | The shared retry budget | [The retry budget](#the-retry-budget) |
| Whether to halve a batch | 400/413 at once; or 2 transient whole-request failures in a row | [Splitting a batch](#splitting-a-batch) |
| Whether to give a single document up (unknown status or response timeout) | Its fixed number of failures in a row | [Giving up](#giving-up-on-a-document) |
| Whether to give a document up for an unknown item error | Its fixed number of item failures | [Giving up](#giving-up-on-a-document) |
| Whether to stop the worker | 401, or a 403 that isn't a write block | [Credentials](#credentials-the-target-refuses) |
| When to stop trying altogether | The work item's lease | [Time](#time-the-response-timeout-and-the-lease) |
| How fast terminal failures may accumulate | Nothing limits them: they're recorded at the pace the target answers | [Correlated terminal failure](#correlated-terminal-failure) |

Anything classified as the target's state (throttled or unavailable) never fails a document, however long it lasts.
Elapsed lease time alone also never fails a document.

## Overview

| Mechanism | Decides | Settings | Class |
|---|---|---|---|
| [Batches](#batches) | Which documents go in one bulk request, and how many requests a shard may have open | `--documents-size-per-bulk-request`, `--documents-per-bulk-request`, `--max-connections` | `BatchSizePredicate`, `DocumentMigrationPipeline` |
| [In-flight limit](#the-in-flight-limit) | How many bulk requests the worker sends at once, and how fast below one | `--max-connections` | `AdaptiveLimit` |
| [Classification](#classifying-an-attempt) | What a failed attempt says about the document and about the target | none | `BulkItemErrorKind`, `BulkErrorClass` |
| [Retry wait](#waiting-before-a-retry) | How long a batch waits before its next attempt | none | `BulkRetryPolicy` |
| [Retry budget](#the-retry-budget) | Whether a retry may go now, or must wait for retries to start succeeding | none | `RetryBudget` |
| [Splitting](#splitting-a-batch) | When a failing batch is halved to find the document responsible | none | `OpenSearchClient` |
| [Giving up](#giving-up-on-a-document) | When a document is recorded in the failed document stream | none | `OpenSearchClient` |
| [Time](#time-the-response-timeout-and-the-lease) | How long one attempt waits for a response; what happens when the lease runs out | `--bulk-response-timeout`, `--initial-lease-duration`, `--max-lease-duration` | `OpenSearchClient`, the lease logic |

The in-flight limit and the retry budget answer different questions: the limit asks "how many requests can the
target handle now?", the budget "how much repeated traffic should failing retries generate?" Both are per worker
process and per target.

A bulk attempt follows this path:

```text
source documents
    |
    +--> transform and batch
    |
    +--> retry-budget check and retry wait, when it is a retry
    |
    +--> in-flight limit slot
    |
    +--> assemble the NDJSON body, and gzip it when compression is on
    |
    +--> target HTTP request
    |
    +--> classify each operation's outcome
    |
    +--> make failed records durable, when a stream is configured
    |
    +--> checkpoint the completed source batch
```

Nothing gives up because of elapsed time or a total attempt count. A batch is retried until each of its documents
has succeeded or been given up by one of the rules in [Giving up on a document](#giving-up-on-a-document), or until
the work item's lease ends.

## Settings

Every setting that affects traffic control. "Workflow field" is the field under `documentBackfillConfig` in a
migration workflow; "On a running migration" is how a change is treated there
(see [reconfiguringWorkflows.md](../../docs/reconfiguringWorkflows.md)).

| Flag | Workflow field | Default | Controls | On a running migration |
|---|---|---|---|---|
| `--max-connections` | `maxConnections` | 10 | `M`, the ceiling on the in-flight limit, and the number of batches a shard may have open | Lowering is safe; raising needs approval |
| `--documents-size-per-bulk-request` | `documentsSizePerBulkRequest` | 10 MiB | Largest bulk body, in bytes of NDJSON before compression | Safe |
| `--documents-per-bulk-request` | `documentsPerBulkRequest` | no limit | Most documents in one bulk request | Safe |
| `--max-document-size-bytes` | `maxDocumentSizeBytes` | 0 (no limit) | Document bodies bigger than this after transformation (or without a transform) are recorded in the failed document stream and not sent; action metadata and NDJSON separators are excluded | Impossible (changes which documents migrate) |
| `--bulk-response-timeout` | `bulkResponseTimeout` | `PT310S` | How long one attempt waits for its response | Safe |
| `--initial-lease-duration`, `--max-lease-duration` | `initialLeaseDuration`, `maxLeaseDuration` | `PT10M`, `P1D` | How long a worker owns a work item before it checkpoints and hands it off | Safe |
| `--disable-compression` | | false | Whether bulk bodies are gzipped (when the target supports it) | |
| `--failed-document-stream-s3-bucket` or `--disable-failed-document-stream` | | one is required | Where terminal failures are recorded; without a stream they are counted and the batch moves on, unrecorded | |
| (pods) | `podReplicas` | 1 | How many workers share the target | Safe |

The fixed constants, which are not settings:

| Constant | Value | Where |
|---|---|---|
| Lowest in-flight limit | 1/32 | `AdaptiveLimit.MIN_LIMIT` |
| Limit cut factor `u` | uniform in [0.5, 0.7) | `AdaptiveLimit` |
| Quiet period before fast growth again | 5 min | `AdaptiveLimit.QUIET_PERIOD` |
| Weight of the newest hold time in `R` | 1/8 | `AdaptiveLimit.RTT_WEIGHT` |
| First retry wait bound, longest wait | 2 s, 30 s | `BulkRetryPolicy` |
| Retry budget capacity, refill, first-try reward | 500 tokens, 1 token/s, 1 token | `RetryBudget` |
| Retry cost: throttle or unavailable, transient | 5, 10 tokens | `BulkErrorClass` |
| Whole-request failures before a split | 2 in a row | `OpenSearchClient.FAILED_ATTEMPTS_BEFORE_SPLIT` |
| Retries before a single document is given up: unknown status | 3 (the 4th failure gives up) | `OpenSearchClient.MAX_UNKNOWN_FAILURE_RETRIES` |
| Retries before a single document is given up: no response | 5 (the 6th failure gives up) | `OpenSearchClient.MAX_NO_RESPONSE_RETRIES` |
| Retries before a document whose item keeps failing with an unknown error is given up | 3 (the 4th failure gives up) | `OpenSearchClient.MAX_ITEM_FAILURE_RETRIES` |

## Batches

A shard's documents are read in order and grouped into batches. A batch is closed when adding the next operation
would exceed `--documents-size-per-bulk-request` bytes or `--documents-per-bulk-request` operations; an operation
larger than the byte limit by itself is sent in a batch of its own (counted as `bulkOversizedOperations` and logged
at WARN at most once a minute). An operation larger than `--max-document-size-bytes` is never sent: it goes straight
to the failed document stream.

A shard has at most `M` batches open at once (`flatMapSequential` with concurrency `M` in
`DocumentMigrationPipeline`), and their results are taken in order, so the shard's checkpoint only advances past a
batch once it and every batch before it are done. A batch is *open* from its first attempt until each of its
documents has succeeded or been given up. An open batch that is waiting to retry holds no slot of the in-flight
limit; the limit, not the number of open batches, decides how many requests are on the wire. A request's body is
assembled and compressed only once it has a slot, so batches waiting for one hold their documents but no request
buffers.

Transformation may change each operation's target index, so one bulk request may contain operations for several
indices. A mixed-index request is still one HTTP attempt and holds one slot. There are no per-index limits: they would
mean splitting mixed requests or reserving several permits at once, with head-of-line blocking and starvation.

## The in-flight limit

**State.** `M` is `--max-connections`, an integer ≥ 1. The limit `L` is a real number with 1/32 ≤ `L` ≤ `M`,
initially 1. The threshold `T`, with 1 ≤ `T` ≤ `M`, initially `M`, separates two growth rules. The worker has
`P = max(1, ⌊L⌋)` slots, and `n` requests hold one. `R` is a moving average of how long a slot is held (each release
weighs 1/8).

**Admission.** A request takes a slot at once if no request is waiting, `n` < `P` and pacing allows; otherwise it
waits in a first-in, first-out queue. When a slot is released, or a paced interval ends, waiting requests take slots
in order while `n` < `P` and pacing allows. A slot is held for one HTTP attempt.

**Pacing.** While `L` < 1 the worker has one slot, and after each release the slot stays free for `(1/L − 1)·R`, so
the worker sends about `L` requests per hold time. That is how the limit keeps reducing a worker's load once it is
down to one request at a time; without it, `W` workers could never put fewer than `W` requests on the target. TCP
paces a congestion window below one segment the same way. At `L` ≥ 1 there is no pacing.

**Growth.** When a request succeeds and the limit was its bottleneck,

| Condition | Update | Name | Per round of `P` successes |
|---|---|---|---|
| `L` < 1 | `L := min(1, L + 1/32)` | below one | 1/32 per success: 31 successes from 1/32 back to 1 |
| 1 ≤ `L` < `T` | `L := min(M, L + 1)` | slow start | `L` roughly doubles |
| `L` ≥ max(1, `T`) | `L := min(M, L + 1/L)` | congestion avoidance | `L` grows by about 1 |

Below 1 the step is the floor rather than `1/L`, which would add more than 1 at once. This is a TCP sender's slow
start and congestion avoidance ([RFC 5681 §3.1](https://www.rfc-editor.org/rfc/rfc5681#section-3.1)).

The limit was a request's bottleneck if the request took the last free slot (`n` became `P`), or if, before the
request was released, another request took the last free slot or had to wait. Otherwise the success leaves `L`
unchanged and is counted as `bulkLimitGrowthSkipped`. A limit that was never reached hasn't been tested, and growing
it anyway would let a worker held back by slow snapshot reads later burst past anything the target has accepted;
TCP has the same rule ([RFC 7661](https://www.rfc-editor.org/rfc/rfc7661)).

**Cuts.** The limit is cut by a *limit signal*, one of:

- a `429 Too Many Requests` ([RFC 6585 §4](https://www.rfc-editor.org/rfc/rfc6585#section-4)) that isn't a write
  block;
- a `200` or `201` in which at least one item failed with `es_rejected_execution_exception` or
  `rejected_execution_exception` (a full write queue;
  [thread pool settings](https://opensearch.org/docs/latest/install-and-configure/configuring-opensearch/thread-pool-settings/))
  or `circuit_breaking_exception` (too little heap);
- no response within `--bulk-response-timeout`.

A limit signal on a request that took its slot after the most recent cut (any request, before the first cut) sets

```
T := max(1, L/2)
L := max(1/32, u·L),   u drawn uniformly from [0.5, 0.7)
```

where `L` on the right is the value before the cut. A limit signal on a request that took its slot before the most
recent cut changes nothing: one overload, reported by every request in flight at the time, causes one cut. Since
`u` ≥ 0.5, the limit after a cut is at least the new threshold, so after a cut the limit grows by the below-one or
congestion-avoidance rule; slow start applies only at the start and after a quiet-period reset. Additive increase
with multiplicative decrease is what makes independent senders converge on shares of a common bottleneck
([Chiu and Jain, 1989](https://www.cs.wustl.edu/~jain/papers/ftp/cong_av.pdf)); drawing `u` at random keeps workers
throttled by the same overload from cutting to the same value and growing back in step.

**Quiet-period reset.** When a request succeeds, if `T` < `M` and no limit signal (counted or ignored) has been seen
for 5 minutes (since the last one, or since the worker started if there was none), then `T := M`, so the limit grows
by the slow-start rule again until the next cut. This is checked before the growth step, whether or not the limit was
the request's bottleneck.

Any other outcome (another status, a dropped connection, a cancelled request) releases the slot and changes nothing.
In particular, the target being *unavailable* (a write block, a 503, a 2xx whose failed items are all unavailable;
see below) is not load: it neither cuts the limit nor grows it, so that healthy shards keep their throughput and
the limit is where it was when the target recovers.
`M` is a ceiling, not a target: raising it lets a worker use more of a target that has room, and doesn't make a
worker push harder on a target that is throttling.

## Classifying an attempt

### Item errors

Each failed item's error is classified by its whole chain (its `type`, then each nested `caused_by` type), first match
wins (`BulkItemErrorKind`). Names are the exception class's simple name in snake case, without an `OpenSearch`
prefix; the lists were checked against the OpenSearch 2.19 source. Status codes can't separate these kinds: several
target-state errors are 4xx (`shard_not_found_exception` and `illegal_index_shard_state_exception` are 404,
`index_closed_exception` 400, `cluster_block_exception` 403 or 429).

| Kind | Types | What happens to the document |
|---|---|---|
| **Throttle** | `es_rejected_execution_exception`, `rejected_execution_exception` (full write queues), `circuit_breaking_exception` (heap) | Retried until it succeeds. Never counts. Cuts the limit. |
| **Unavailable** | Shards: `unavailable_shards_exception`, `no_shard_available_action_exception`, `primary_missing_action_exception`, `shard_not_found_exception`, `illegal_index_shard_state_exception`, `index_shard_closed_exception`, `index_shard_not_started_exception`, `index_shard_not_recovering_exception`, `primary_shard_closed_exception`, `shard_not_in_primary_mode_exception`, `shard_lock_obtain_failed_exception`, `index_primary_shard_not_allocated_exception`, `retry_on_primary_exception`, `retry_on_replica_exception`. Nodes and cluster manager: `node_not_connected_exception`, `node_disconnected_exception`, `node_closed_exception`, `connect_transport_exception`, `send_request_transport_exception`, `receive_timeout_transport_exception`, `no_node_available_exception`, `cluster_manager_not_discovered_exception`, `master_not_discovered_exception`, `not_cluster_manager_exception`, `not_master_exception`, `process_cluster_event_timeout_exception`. Blocked writes: `cluster_block_exception`, `index_closed_exception`. | Retried until it succeeds. Never counts. Doesn't touch the limit. |
| **Document** | The non-retryable list shared with the replayer (`mapper_parsing_exception`, `strict_dynamic_mapping_exception`, `illegal_argument_exception`, `action_request_validation_exception`, `routing_missing_exception`, `invalid_index_name_exception`, `stream_constraints_exception`, `version_conflict_engine_exception`, `document_missing_exception`, `resource_already_exists_exception`), plus, for RFS only, `index_not_found_exception`, `parse_exception`, `x_content_parse_exception`, `json_parse_exception`, `number_format_exception`, `document_parsing_exception` | Recorded in the failed document stream at once (`NON_RETRYABLE`) |
| **Unknown** | Anything else | Retried; given up after 3 retries ([below](#giving-up-on-a-document)) |

The target's state is checked first: a circuit breaker that trips while a document is parsed is reported as the
`caused_by` of a `mapper_parsing_exception`, and it is still about the target. A missing index is a document error
for RFS because the backfill created the indices it writes to, so a document routed elsewhere (by a transform, say)
will never find its index; the replayer keeps it retryable, since there a later request may create the index. An
allowlisted type (`--allowed-doc-exception-types`) is a success.

### Whole attempts

Each attempt is classified by its outcome, first match wins (`BulkErrorClass`):

| Outcome | Class | What happens to the batch | Limit | Retry cost |
|---|---|---|---|---|
| 2xx, every item succeeded (or was allowlisted) | | Done | may grow | |
| 2xx, some items failed | by its items: throttle if any is a throttle; else transient if any is unknown; else unavailable | Succeeded and document-error items leave the batch; the rest are retried | cut if any item is a throttle; unchanged if all are unavailable; may grow otherwise | 5, 10 or 5 |
| Not 2xx, body reports `cluster_block_exception` (403 for a read-only index or cluster, 429 at the disk flood stage) | unavailable | Retried | unchanged | 5 |
| 429 | throttle | Retried | cut | 5 |
| 503 | unavailable | Retried | unchanged | 5 |
| 400, 413 | | The request itself is wrong: the batch is halved; a single document is recorded (`NON_RETRYABLE`) | unchanged | not retried |
| 401, 403 | auth | The worker stops ([below](#credentials-the-target-refuses)) | unchanged | not retried |
| No response: the connection was refused, reset or closed | unavailable | Retried | unchanged | 5 |
| No response within `--bulk-response-timeout` | transient | Retried; may split or give up | cut | 10 |
| Any other status (408, 500, 502, 504, other 4xx, …) | transient | Retried; may split or give up | unchanged | 10 |

*Target state* means throttle or unavailable: the target, not a document, is why the attempt failed, so it never
counts against a document, ends the batch's streaks (below), shows in the `bulkDocsWaitingOnTarget` gauge, and is
retried until the target can take the documents. A connection that was refused or reset is the target's state
because the document may never have reached it. *Transient* failures advance the fixed split or give-up thresholds.

### Allowlisted errors

An operation is a success when the target accepts it or its error type is listed in `--allowed-doc-exception-types`
(`allowedDocExceptionTypes`): it isn't retried, isn't recorded, and isn't counted as a failure. The allowlist is empty
by default and matches the error type string exactly, not as a pattern. It is separate from the built-in lists above:
those decide what is retried, the allowlist is the operator declaring an error acceptable.

### Transform failures

A transformer exception or a malformed transformed result fails the work item rather than creating an incomplete
failed-document record. A transformed operation that can't be linked to its source document is not a transform
failure; see [Source provenance](#source-provenance-through-transformation).

## Waiting before a retry

Let `Cmax` = 30 s. When attempt `k + 1` of a batch fails and is to be retried (`k` = 0, 1, 2, … retries so far):

1. The wait `d` is `min(RA, Cmax)` if the response carried `Retry-After` `RA`
   ([RFC 9110 §10.2.3](https://www.rfc-editor.org/rfc/rfc9110#section-10.2.3); seconds, or an HTTP date that counts
   as 0 once past). Otherwise `d = ⌊r · min(Cmax, 2 s · 2^k)⌋` with `r` drawn uniformly from [0, 1): *full jitter*,
   from the AWS Architecture Blog's
   [Exponential Backoff And Jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/). The
   mean wait is half the bound: 1, 2, 4 and 8 s for `k` = 0, 1, 2, 3, then 15 s. Drawing the whole wait at random
   spreads out batches that failed together instead of sending them back in waves.
2. The retry takes its cost from the [retry budget](#the-retry-budget), waiting for tokens if there aren't enough.
3. It waits `d`, then sends the documents still pending. The retry then waits for a slot like any other request.

`k` counts every retry of the batch, whatever its cause, so a batch that has been throttled many times waits up to
30 s between attempts. A half from a split starts again at `k` = 0.

## The retry budget

**State.** A balance `B`, a real number with 0 ≤ `B` ≤ `C`, where `C` = 500; initially `B = C`. Plus a first-in,
first-out queue of retries waiting for tokens.

**Rules.**
- *Refill:* `B` grows continuously at 1 token per second, never above `C`.
- *Spending:* a retry of cost `c` proceeds at once if no retry is waiting and `B` ≥ `c`, and then `B := B − c`.
  Otherwise it waits in the queue; waiting retries are served in order as soon as `B` covers the first one's cost.
  A retry never fails for lack of tokens.
- *Credits:* when one of a batch's requests succeeds, `B := min(C, B + c)` if that request was a retry that cost `c`
  (the retry is refunded), and `B := min(C, B + 1)` if it was the batch's first attempt.

First attempts never spend tokens; ramping up is the in-flight limit's job. This is the token-bucket retry quota of
the AWS SDKs' *standard* retry mode
([Retry behavior](https://docs.aws.amazon.com/sdkref/latest/guide/feature-retry-behavior.html), AWS SDKs and Tools
Reference Guide), with a time-based refill added.

**What it bounds.** Only retries that fail keep their cost. Over any `t` seconds, the tokens spent on retries that
fail are at most `C + t + s`, where `s` is the number of first-attempt successes in that time; every retry costs at
least 5, so at most `(C + t + s) / 5` retries fail. Over a long period that is a rate of at most `(1 + s/t) / 5`
failed retries per second: when the target is failing most requests, retries slow to about the refill rate, however
many batches are failing at once.

## Splitting a batch

A batch of more than one document is split into two halves, the first `⌊n/2⌋` documents and the rest, when:

- it gets a 400 or 413 (`reason=status` or `reason=too_large`), at once; or
- it gets two transient whole-request failures in a row (`FAILED_ATTEMPTS_BEFORE_SPLIT`): another status
  (`reason=status`) or a response timeout (`reason=no_response`).

The halves are new batches, sent one after the other, each starting with no streaks, no counts and `k` = 0. A batch
of `n` documents is therefore narrowed down to one document in at most `⌈log₂ n⌉` splits. A whole-request failure
says nothing about which document caused it, which is why it never counts against a batch of more than one document:
only splitting can find the document. Target-state failures never split a batch.

## Giving up on a document

A document is given up, recorded in the failed document stream, and the migration continues past it, in exactly
these cases:

| Case | Rule | `failureClass` | `bulkDocsGaveUp` `reason` |
|---|---|---|---|
| Document error | Its item fails with a document-kind error. At once. | `NON_RETRYABLE` | (not a give-up) |
| Rejected request | A single document's request gets a 400 or 413. At once. | `NON_RETRYABLE` | (not a give-up) |
| Unknown status | A single document's request gets a transient status 4 times in a row: 3 retries. | `RETRYABLE_EXHAUSTED` | `unknown_failures` |
| Response timeout | A single document's request times out 6 times in a row: 5 retries. | `RETRYABLE_EXHAUSTED` | `no_response` |
| Unknown item error | Its item fails with an unknown error for the 4th time in the batch: 3 retries. | `RETRYABLE_EXHAUSTED` | `item_failures` |
| Too large to send | It is bigger than `--max-document-size-bytes`. Never sent. | | (not a give-up) |

Nothing else fails a document:

- **The target's state never counts.** A throttle or an unavailable target is retried for as long as it takes.
- **Time never counts.** There is no deadline on a batch. If the lease runs out first, the batch is stopped and its
  documents are not recorded as failed: the next worker sends them again
  ([below](#time-the-response-timeout-and-the-lease)).
- **A whole-request failure of a batch of more than one document never counts** against its documents; it only
  leads to a split.

How a document came to be alone doesn't matter: a batch of one from the start, or the last half of a split, follow
the same rules. The streaks end differently:

- any response ends the response-timeout streak;
- a 2xx, or any target-state outcome, ends the unknown-status streak and the split streak.

The unknown-item count is per document and per batch, and isn't a streak: it only grows, by one for each response in
which the document's item failed with an unknown error. A half from a split starts with no streaks and no counts.

### What the numbers mean

A document that fails on its own is recorded after at most `2·⌈log₂ n⌉` attempts to narrow it down (each split needs
two failures, unless a 400 or 413 split it at once) plus 4 for an unknown status or 6 for a response timeout.
If instead a healthy target fails requests at random with probability `p`, independently, a document is given up by
mistake only if its own request fails 4 (or 6) times in a row: about `p⁴` per single-document batch for an unknown
status (`p = 0.05` gives 6·10⁻⁶) and `p⁶` for timeouts (1.6·10⁻⁸).

The waits between those attempts are jittered (means 1, 2, 4, 8, then 15 s), so the shortest time to give a document
up is about the duration of its attempts; with response timeouts, 6 attempts take at least 6 × `--bulk-response-timeout`
(31 minutes at the default).

### Without a failed document stream

With `--disable-failed-document-stream`, every terminal failure is classified and counted exactly as with a
stream, and the batch moves on; nothing records the documents, so metrics and logs are all that remain
([Durability and checkpointing](#durability-and-checkpointing)).

### Correlated terminal failure

RFS does not slow terminal failures. A worker whose every operation fails keeps sending at the pace the target and
its in-flight limit allow, and records every failure. That is deliberate:

- Pacing failures has a cost on every run. Any mechanism that limits how many failures one attempt can add also limits
  how large healthy requests can be, and a process-local allowance resets with every work item.
- A broken workload is evident from the first failed records, with their reasons, across every shard that is
  running. Stopping it, fixing the mapping or transform, and rerunning is the remedy whatever the failure rate was.
- Because target state is never a terminal failure (see [Classifying an attempt](#classifying-an-attempt)), an
  outage or an overloaded target can't produce this flood; only operations the target refused can.

Stopping a shard once its committed terminal failures exceed a configured share of its source documents is deferred;
see [Deferred work](#deferred-work).

## Credentials the target refuses

A 401, or a 403 that isn't a write block (`cluster_block_exception` in the body), means the target refused the
worker's credentials or permissions, which can also happen mid-run when credentials expire or permissions change.
That is not a property of any document, and retrying won't fix it. The worker logs at ERROR, stops
(`TargetAuthenticationFailed`), and records nothing as failed; the work item is picked up again when its lease
expires, so the migration resumes once the credentials are fixed. Other workers stop the same way when their own
requests are refused.

## Time: the response timeout and the lease

`--bulk-response-timeout` (default 310 s) bounds how long one attempt waits once its body has been sent. The timer is
reset by each part of the response that arrives, so in practice it bounds the time until the target starts
answering. OpenSearch keeps working on a bulk it has received, so retrying one that is merely slow adds a second copy
of the work to a target that is already behind; the timeout should exceed the longest time the target takes to
complete a bulk. The default is just over 300 s, a common idle timeout for load balancers and proxies in front of a
cluster, so behind one a stuck bulk is answered with a 504 before the worker gives up on it. A response timeout is
both a transient failure and a limit signal. Work coordination and other requests keep a separate, shorter
timeout.

The work item's lease is the only bound on how long a batch keeps trying. At
`max(0.75·lease, lease − 4 min 30 s)` the worker cancels the shard's work, records how far the shard got (up to the
end of the last batch completed in order), hands the rest to a successor work item, and exits
([Early checkpoint before lease expiry](../../DocumentsFromSnapshotMigration/README.md#early-checkpoint-before-lease-expiry)).
Batches still retrying at that moment are abandoned without recording anything as failed; the successor sends their
documents again. A work item whose lease expires is given a longer lease the next time, up to
`--max-lease-duration`.

The in-flight limit and retry budget are process-local. A replacement process starts them
afresh with the settings it was started with, and may send again the operations after its predecessor's last durable
checkpoint. Lease expiry never turns pending operations into failures.

## Ordering and starvation

Every queue in this design is first-in, first-out: waiting for a slot, waiting for retry tokens, and taking a shard's
batches in order. Retries don't get priority over first attempts, or the other way round. Instead, the set of
requests competing is kept small: a shard has at most `M` open batches, so at most `M` of its batches compete for
slots, and a batch that keeps being throttled delays the shard's checkpoint (results are taken in order) but not
other batches' attempts.

A batch can still be unlucky: with many retries in a row while other batches succeed, or a long wait at 30 s per
retry. Three metrics show whether that is happening:

- `bulkBatchAge` (histogram, ms): how long batches stayed open, from first attempt to done.
- `bulkOldestOpenBatchAge` (gauge, ms): the age of the oldest batch still open. If it keeps climbing while
  documents are being migrated, a batch is starving.
- `bulkThrottleStreak` (histogram): for each batch, its longest run of throttled attempts in a row. A long tail
  means the limit is growing back too steeply for the target, so a few batches are throttled over and over.

## Failed-document records

This is the contract for the records; [failedDocumentStream.md](../../docs/failedDocumentStream.md) is
the guide to finding, reading and deleting them.

### Meaning and lifecycle

A failed-document record is an immutable historical observation:

> This target operation became terminally failed during this RFS session.

It does not assert that the target document is currently absent. A version conflict is recorded unless explicitly
allowlisted even though the target may already contain the document or a newer version.

Later repair or replay does not update, delete, or resolve the earlier record. A later migration session creates a
new historical observation.

The stream is append-only and at-least-once. Consumers may deduplicate re-emissions of the same logical operation
within one session, but raw records remain intact.

### Source provenance through transformation

A transform is called once per batch: it receives the batch's operations as one array and returns one array, so its
output says nothing about which input produced each operation. In this version RFS links a transformed operation to
its source document the way it does today, by document ID:

- When exactly one source document in the batch has the operation's ID, the record carries that source document in
  its `source` portion along with the transformed operation in its `target` portion.
- When there is no such match (the transform changed the ID, produced several operations with one ID, or produced an
  operation unrelated to any input), the `source` portion is absent and the record carries the `target` portion
  only. This is not an error, and it doesn't change classification, counting or checkpointing.

Dropping a document (1→0) and producing several operations from one (1→many) work for writing; only the link from a
failed operation back to its source is weaker.

> **Deferred: tightening provenance for failures.** A later change should give every transformed operation exactly
> one immutable source identity and a deterministic per-source ordinal, so that every failed record carries its
> source even when a transform changes IDs or fans out. Options include calling the transform once per source
> document (each call's outputs then come from that document) or carrying an identity field through the transform.
> The intended rules: fan-out copies the source identity to each output; filtering and reordering are supported; a
> transform may change the target ID, index, routing, operation type and body; output order for one source is
> deterministic; multi-source aggregation and operations unrelated to any source are unsupported; and the identity
> is internal metadata, never sent to the target.

### Logical record shape

The serialized names may change during implementation, but the record has explicit portions rather than the current
hybrid request. `source` is present only when the operation was linked to its source document (see above), and the
source `position` and target `operationOrdinal` wait for the deferred provenance work:

```json
{
  "context": {
    "sessionId": "migration-run",
    "workerId": "worker-process",
    "workItemId": "source-work-item"
  },
  "source": {
    "index": "source-index",
    "shard": "0",
    "position": 12345,
    "documentId": "source-id",
    "operationType": "UPSERT",
    "body": {}
  },
  "target": {
    "operationOrdinal": 0,
    "effectiveIndex": "target-index",
    "documentId": "target-id",
    "operationType": "index",
    "action": {},
    "body": {}
  },
  "failure": {
    "type": "mapper_parsing_exception",
    "class": "NON_RETRYABLE",
    "timestamp": "2026-10-29T12:00:00Z",
    "responseItem": {}
  }
}
```

When both portions are present, the record stores both complete bodies even when they are identical or mostly
redundant. The gzip-compressed stream
is responsible for making repeated content inexpensive; the record does not use body deltas or record-level
deduplication.

`target.effectiveIndex` is the transformed operation's `_index` when present and otherwise the request's outer
default index. S3 target-index partitioning uses this effective value.

### Stable logical identity

Deferred with source provenance. Until then, the migration console keeps deduplicating by target index (now the
effective index) and document ID. The intended identity of a logical failed target operation is the transparent
composite:

```text
session ID
+ source index
+ source shard
+ stable source position
+ target-operation ordinal for that source
```

Do not replace these fields with an opaque hash. `workerId`, `workItemId`, timestamp, retry number, and S3 object
location are not part of the identity.

The target-operation ordinal is its deterministic position among one source document's transformed outputs. It is
not a retry count or failure count. Two byte-for-byte identical fan-out operations remain distinct through their
ordinals.

The migration console uses this identity to deduplicate at-least-once stream re-emissions. Future replay tooling may
use the same coordinates, but that is not required by this design.

### Durability and checkpointing

Failed-document stream configuration changes retention, not classification or counting.

When a failed-document stream is configured:

- every terminal record for a completed source batch must be durable before that batch checkpoints;
- a persistence failure prevents the checkpoint; and
- reprocessing may emit the same logical records again, which is why the stream is at-least-once.

When the stream is explicitly disabled:

- terminal operations are classified and counted identically;
- the source batch checkpoints after terminal outcomes are accounted;
- metrics and logs still describe the failures; and
- no durable replay artifact exists.

Disabling the stream is therefore an explicit acceptance that terminal failures cannot be recovered from failed
records. It must not silently change which categories stop or continue the migration.

### Committed failure counts

Each work-item checkpoint commits, with its progress, the terminal failures of the range it covers:
`committed_metrics.metrics.failures`, as `nonRetryable` and `retryableExhausted`
([committedMetrics.md](../../docs/committedMetrics.md)). They are counted at the same point as the records, before
the watermark advances, so they sum across a shard's work items without double counting. A worker that fails after
writing records but before committing leaves records with no committed count, and its successor may write them
again, so the stream can hold more records than the summed counts.

## Metrics and logs

Once a minute, while it has bulk activity, each worker logs a summary: at WARN while any documents are waiting on
the target (throttled or unavailable), at INFO otherwise. For example:

```
Bulk flow in the last PT1M: 228 attempts (ok 201, admission_rejected 27); 228 requests sent, averaging 989 documents
and 1047552 bytes (1047552 bytes on the wire); 27 retries (27 throttled, 0 unavailable, 0 transient); 0 documents the
target couldn't take; 25 limit cuts, 0 quiet resets; 0 batches split; 0 documents given up; 19336 documents re-sent;
retry budget empty 0 times. Now: limit 5.67 of 10 (threshold 2.10), 1 in flight, 0 waiting for a slot; retry budget
500 of 500 tokens, 0 retries waiting; 989 documents waiting on the target; oldest open batch 2140 ms old
```

Each worker also exports OpenTelemetry metrics (per worker; attributes are small fixed sets, never index, shard or
worker ids):

| Metric | Kind | Attributes | Shows |
|---|---|---|---|
| `bulkAttempts`, `bulkAttemptDuration` | counter, histogram (ms) | `outcome` (`ok`, `item_errors`, `throttled_429`, `admission_rejected`, `payload_413`, `other_4xx`, `5xx`, `other_status`, `timeout`, `connection_reset`, `connect_failed`); `attempt` (`first`, `retry`) on the counter | Every HTTP attempt; durations near `--bulk-response-timeout` |
| `bulkRetries`, `bulkRetryDelay`, `bulkRetryAfterHonored` | counter, histogram (ms), counter | `errorClass` (`throttle`, `unavailable`, `transient`) | Retries, and the backoff part of their wait |
| `bulkAttemptGap` | histogram (ms) | | How long each retry was sent after the batch's previous attempt ended: the whole wait (backoff, retry tokens, an in-flight slot) |
| `rfsWorkerPhase` | gauge | `phase` (`starting`, `acquiring_work`, `downloading_shard`, `migrating`, `completing`, `exited`) | 1 for the process's current phase, 0 for the others, on every export: how many workers are in each phase. Each transition is logged at INFO with the previous phase's duration |
| `bulkDocsWaitingOnTarget` | gauge (documents) | | Documents in open batches whose last attempt was throttled or found the target unavailable. **Alarm on this staying above 0**: the migration is frozen on the target |
| `bulkDocsTargetUnavailable` | counter (documents) | `reason` (an unavailable type, `http_503`, `connect_failed`, `connection_reset`, else `other`) | Why the target couldn't take documents |
| `bulkDocumentsFailed` | counter (operations) | `failureClass` (`NON_RETRYABLE`, `RETRYABLE_EXHAUSTED`) | Every terminal failure, recorded or not (with the stream disabled) |
| `bulkDocsGaveUp` | counter (documents) | `reason` (`unknown_failures`, `no_response`, `item_failures`) | Documents given up by the rules above. Should be 0 on a healthy migration |
| `bulkBatchSplits` | counter | `reason` (`status`, `no_response`, `too_large`) | Batches halved to find a failing document |
| `bulkAttemptsPerBatch` | histogram | | How many attempts batches need |
| `bulkBatchAge`, `bulkOldestOpenBatchAge`, `bulkThrottleStreak` | histogram (ms), gauge (ms), histogram | | Starvation (above) |
| `bulkItemErrors` | counter | `errorType` (known types, else `other`), `retryable` | Per-item failures and their classification |
| `bulkDocsResent` | counter | | Documents sent again by retries |
| `bulkRequestDocuments`, `bulkRequestBytes`, `bulkRequestSentBytes` | histograms | | Each attempt's operations, NDJSON size, and size on the wire (smaller when gzipped) |
| `retryBudgetTokens`, `retryBudgetSpent`, `retryBudgetRefunded`, `retryBudgetEmpty`, `retryBudgetWait` | gauge, counters, histogram (ms) | `errorClass` on spent | The retry budget |
| `bulkLimit`, `bulkLimitMax`, `bulkLimitThreshold`, `bulkInFlight`, `bulkSlotWaiters`, `bulkSlotWait` | gauges, histogram (ms) | | The in-flight limit's state (`bulkLimitMax` is `M`), and how long requests wait for a slot |
| `bulkWorkersByLimitUtilization` | gauge | `range` (`0-24%`, `25-49%`, `50-74%`, `75-99%`, `100%`) | 1 for the range `L`/`M` is in, 0 for the others: how many workers are at each share of their ceiling |
| `bulkLimitCuts`, `bulkLimitQuietResets`, `bulkLimitGrowthSkipped`, `bulkTimeToFullLimit` | counters, histogram (ms) | `signal` on cuts (`throttled_429`, `admission_rejected`, `timeout`) | How the in-flight limit moves |

Logs: each cut and quiet-period reset at INFO; each throttled attempt at INFO; each unavailable attempt, each other
failed attempt, and each split at WARN; each give-up and each refused
credential at ERROR; the retry budget running out at WARN; the minute summary at WARN while documents wait on the
target. The `BulkRequestTrace` logger, off by default, logs every attempt's request and
response at TRACE (see the [README](../../DocumentsFromSnapshotMigration/README.md#tracing-bulk-requests); it contains
document contents).

The RFS dashboards (CloudWatch and Grafana, generated by `deployment/k8s/scripts/generate-rfs-dashboards.py`) chart
these metrics.

Metrics are diagnostic and best-effort: never used for checkpointing, admission or any other decision. Their
attributes are small fixed sets; index names, shard numbers, document ids and error strings belong in logs and
failed-document records. The migration console reports durable progress and failed-document-stream facts, not
metrics.

## Tuning

| Symptom | Likely cause | What to change |
|---|---|---|
| Frequent `timeout` cuts; `bulkAttemptDuration` near the timeout | Bulks take longer than `--bulk-response-timeout` | Raise the timeout, or lower `documentsSizePerBulkRequest` so each bulk finishes sooner |
| `bulkDocsWaitingOnTarget` above 0 for minutes; WARN summaries | The target is throttling or unavailable (see `bulkDocsTargetUnavailable` by `reason`) | Fix the target: allocate shards, free disk, clear blocks, bring nodes back. The migration resumes on its own |
| `bulkDocsGaveUp` with `reason=no_response` | Single documents whose requests repeatedly time out | Check the documents in the failed document stream; if the target is struggling, raise `bulkResponseTimeout` or reduce load |
| `bulkDocsGaveUp` with `reason=unknown_failures` or `item_failures` | Documents that fail on their own with an error in neither list | Check the `failureType` in the failed document stream; a type that is really about the target belongs in `BulkItemErrorKind` |
| `bulkOldestOpenBatchAge` climbing; long `bulkThrottleStreak` tail | Some batches are throttled over and over while others get through | Fewer workers or a lower `maxConnections` |
| `retryBudgetEmpty` frequent, `retryBudgetWait` large | Most retries are failing | The target can't take the load; reduce `podReplicas` |
| `bulkLimit` well below `M` on every pod, with regular cuts | The target is the bottleneck and the workers are sharing it | Expected under load; more workers won't help, a larger target will |
| `bulkLimit` = `M` with no cuts, and the target has headroom | The workers could send more | Raise `maxConnections` or `podReplicas` |
| `bulkLimitGrowthSkipped` high, few requests in flight | Reading the snapshot is slower than writing | The source side is the bottleneck, not the target |
| `bulkDocsResent` large relative to documents migrated | Many partial rejections | Smaller bulks, or fewer workers |
| `bulkDocumentsFailed` steady, across shards | A mapping, index or transform is broken | Inspect the failed records, stop the migration, fix the cause, and rerun |
| ERROR logs about refused credentials | The target returned 401, or 403 without a write block | Fix the credentials or permissions; the work resumes when leases expire |

## Deferred work

Everything above is implemented, except where marked deferred. Two changes are planned.

**Stopping broken shards.**

- Allow each shard a configured number of terminal failures, the lesser of an absolute count and a percentage of the
  shard's source documents (which may exceed 100% when a transform fans out). A successor's allowance is the
  shard's minus its committed failures. A worker that reaches it sends no new requests, lets those in flight
  finish, records the failures, marks the work item broken, and exits.
- Report broken work items as a distinct backfill and workflow outcome, and provide a command to rerun them.

**Tightening provenance for failures** (also described under
[Source provenance](#source-provenance-through-transformation)).

- Carry immutable source provenance and deterministic target-operation ordinals through transformation, so every
  failed record carries its source even for ID-changing and fan-out transforms.
- Change migration-console deduplication from `(targetIndex, documentId)` to the stable composite identity.

## References

- RFC 5681, *TCP Congestion Control* (slow start, congestion avoidance).
- RFC 7661, *Updating TCP to Support Rate-Limited Traffic* (growing only while the window is in use).
- D. Chiu and R. Jain, *Analysis of the Increase and Decrease Algorithms for Congestion Avoidance in Computer
  Networks*, 1989.
- RFC 6585 §4 (`429 Too Many Requests`); RFC 9110 §10.2.3 (`Retry-After`).
- M. Brooker, *Exponential Backoff And Jitter*, AWS Architecture Blog, 2015.
- AWS SDKs and Tools Reference Guide, *Retry behavior* (standard mode retry quota).
- OpenSearch documentation, *Thread pool settings*.
