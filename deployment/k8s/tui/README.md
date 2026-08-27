# Live replay-quality TUI

A [Textual](https://textual.textualize.io/) replacement for
`chorus_es_to_os_demo/07-live-jaccard-kafka.sh`. Consumes the `tuple-output` Kafka topic and shows a
live sparkline + table of how closely each replayed request's target response matched the
source, scoring by doc-ID overlap, aggregation buckets, or hit-count ratio (see
`scoring.py`).

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

Keys: `r` resets the window and tuple count, `q` quits.

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
