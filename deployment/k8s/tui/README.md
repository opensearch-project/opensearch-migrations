# Live replay-quality TUI

A [Textual](https://textual.textualize.io/) replacement for
`chorus_es_to_os_demo/07-live-jaccard-kafka.sh`. Consumes the `tuple-output` Kafka topic and shows a
live sparkline + table of how closely each replayed request's target response matched the
source. Every request decomposes into independently-scored rows — one per hits section or
named aggregation, and one per `_msearch` sub-query — rather than one blended score per
request, since averaging hides exactly the failure that matters (see `scoring.py`).

Hits rows can be scored two ways, toggled live with `m`:
- **Jaccard** (default) — plain set overlap of doc IDs, order-blind: two result sets with the
  same documents in a different order score a perfect 1.0.
- **RBO** (Rank-Biased Overlap, Webber/Moffat/Zobel 2010) — weights agreement at shallow ranks
  more than agreement deep in the list, so a reordered result set scores below 1.0. Useful
  when ranking fidelity matters, not just membership.

Aggregation rows always use their own weighted bucket-overlap score regardless of this
toggle — RBO has no meaningful equivalent for bucket comparisons.

Built following the structure of the `loadtest` TUI added in
[opensearch-project/opensearch-migrations#3263](https://github.com/opensearch-project/opensearch-migrations/pull/3263):
a background thread owns the long-lived I/O (there, k6 run polling via the Kubernetes API;
here, a `kafka-console-consumer.sh` subprocess) and only ever writes to a lock-guarded
buffer. A timer on the main thread snapshots that buffer and repaints. This keeps a stalled
or slow consumer from ever blocking keypresses or redraws.

## Run it

```bash
cd deployment/k8s
pip install -r tui/requirements.txt
python3 -m tui --namespace ma
```

Options:

| Flag | Default | Meaning |
|---|---|---|
| `--namespace`, `-n` | `ma` | Namespace the migration console and Kafka run in |
| `--topic` | `tuple-output` | Kafka topic to consume |
| `--window` | `15` | Tuples shown in the sparkline/table |
| `--interval` | `2.0` | UI repaint interval, seconds |
| `--no-auto-create-topic` | off | Fail instead of creating the topic if missing |

Keys: `v` toggles a selected row's detail pane between the hit-ID/bucket diff and the raw
captured request; `m` toggles hits scoring between Jaccard and RBO; `c` copies the selected
row's request to the clipboard; `r` resets the visible window (the footnote's lifetime stats
are *not* affected by this — see below); `q` quits.

### Lifetime stats

The bottom line is a running, per-label breakdown since the app started (or since the topic
was last recreated) — independent of the sliding window and unaffected by `r`. It exists for
the same reason per-request blended scores were dropped in favor of per-item ones: a flat
"average score across everything" can hide a consistently-diverged label (say, every `_msearch`
aggregation on one facet) behind a pile of perfect hits-only requests. It shows count,
average, and min/max per label under whichever metric (`m`) is currently selected, capped to
the 8 highest-count labels.

## Requirements

- `kubectl` configured against the target cluster (this runs on your machine, not inside
  the migration console pod — same as the script it replaces).
- `migration-console-0` running in the target namespace, with `kafka-tools` at
  `/root/kafka-tools/kafka` (present on that image already).
- A TrafficReplayer producing to the topic (`--tuple-kafka-topic tuple-output`).

## Why the topic gets auto-created

The original script's error message said running the TrafficReplayer once would
auto-create the topic. On a Strimzi cluster with `auto.create.topics.enable=false` (the
default for the `ma` demo cluster), that never happens — the producer just retries
`UNKNOWN_TOPIC_OR_PARTITION` forever. `kafka_reader.ensure_ready()` now creates the topic
itself via a `KafkaTopic` CR when it's missing (pass `--no-auto-create-topic` to get the
old fail-fast behavior instead).

## Layout

- `scoring.py` — pure scoring functions, no I/O; ported unchanged from the bash script's
  embedded Python.
- `kafka_reader.py` — `kubectl`-based bootstrap (consumer creds, topic) and streaming.
- `app.py` — the Textual `App`.
- `__main__.py` — CLI entry point.
