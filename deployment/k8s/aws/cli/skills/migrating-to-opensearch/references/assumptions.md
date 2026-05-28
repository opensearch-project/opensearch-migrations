# Default Assumptions

> **Skill IP** — defaults this skill applies when the customer does not provide a value. Not from any upstream doc; the operator MUST validate against the customer's environment.

When the user cannot supply a value, you SHOULD fall back to these defaults. You MUST state the
default in the report and flag that the user SHOULD validate against their actual environment.

| Field | Default | Rationale |
|---|---|---|
| `account_context.region` | `us-east-1` | Largest service surface, cheapest list price baseline. |
| `source.azs` (production) | 3 | Multi-AZ best practice; flag if user requests otherwise. |
| `target.replicas` | 1 (= 2 copies) | OpenSearch production default; increase for read-heavy or HA-strict workloads. |
| `target.target_shard_size_gb` | 30 GB (search), 45 GB (time-series) | Center of the 10–50 GB band; time-series tolerates larger shards because writes are append-only. |
| `target.cpus_per_shard` | 1.5 (search), 1.25 (time-series), 1.0 (vector) | Mirrors upstream sizing recommendations. |
| `target.aoss_min_ocus` | 4 (2 indexing + 2 search) | Production HA minimum; verify current value via AWS Knowledge MCP. |
| `pricing.peak_factor` | 1.5 | Multiplier on steady-state OCUs to account for peak-hour scaling. |
| `pricing.discount_factor` | 1.0 (no discount) | You MUST present list-price first because discounts are user-specific and unverifiable by the agent. |
| `migration.allowed_downtime_min` | 60 minutes | Drives MA-vs-snapshot-vs-OSI choice. |
| `migration.cutover_strategy` | "capture-and-replay validate, then DNS swap" | Lowest-risk default; revisit if user has dual-write infrastructure. |

If the user's value differs by more than 2× from a default, you MUST treat that as a strong signal
that the recommendation MUST be re-derived rather than nudged from the default. You MUST recompute
from scratch.
