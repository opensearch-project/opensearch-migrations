# Migration Console — operating reference

Once `migration-console-0` is up, you (or the agent) drive the
migration from inside the pod:

```bash
kubectl exec -n ma -it migration-console-0 -- /bin/bash
```

Two CLI surfaces live side-by-side on the pod:

- **`workflow`** — modern, declarative, Argo-orchestrated. The
  recommended way to drive a migration end-to-end.
- **`console`** — the legacy, imperative command tree that drives
  one stage at a time (`console snapshot create`,
  `console metadata migrate`, `console backfill start`,
  `console replayer start`, …). Still works; useful for ad-hoc
  introspection and one-off operations.

Pick `workflow` first. Fall back to `console` only when you need to
touch a single phase the workflow declared (or to investigate something
the workflow flagged for approval).

---

## `workflow` (recommended)

The flow is "configure once, then submit, then approve gates":

### Configure
| Command | What it does |
|---|---|
| `workflow configure sample` | Print the sample YAML schema for the installed MA version |
| `workflow configure sample --load` | Load the sample as your starting config |
| `workflow configure edit` | Open the config in `$EDITOR` |
| `workflow configure view` | Print the current saved config |
| `workflow configure clear` | Clear the saved config |

The schema **changes between MA versions** — always run `workflow
configure sample` against the version you're on rather than copying
from old docs.

### Submit
| Command | What it does |
|---|---|
| `workflow submit` | Validate config → transform to Argo Workflow → initialize state → run asynchronously |

`submit` is fire-and-forget — control returns immediately; the
workflow runs in the background.

### Monitor
| Command | What it does |
|---|---|
| `workflow status` | Workflow progress + task states |
| `workflow output` | Logs across all pods |
| `workflow output --follow` | Stream logs live |
| `workflow output -l <key>=<value>` | Filter by label (auto-prefixes `migrations.opensearch.org/`) |
| `workflow show <RESOURCE> [TASK]` | Step-specific output (e.g. `evaluatemetadata`, `migratemetadata`) |
| `workflow manage` | Interactive TUI: approve steps, view logs, monitor |

### Approve gates
The workflow pauses at human-approval gates — metadata evaluation,
incompatible-change retries, etc. Approve via:

| Command | What it does |
|---|---|
| `workflow approve step <pattern>` | Approve a blocked step (globs OK: `"evaluatemetadata.*"`) |
| `workflow approve change [--all]` | Approve a change to a blocked resource |
| `workflow approve retry` | Approve an INCOMPATIBLE change retry (resource must be reset first) |

You MUST review `workflow log` (or `workflow output -l task=<name>`)
**before** approving any gate. The agent is bound by this rule too.

### Reset / clean up
| Command | What it does |
|---|---|
| `workflow reset --list` | Show migration-managed resources |
| `workflow reset <resource>` | Delete one resource (e.g. `snapshotmigration.migration-0`) |
| `workflow reset --cascade` | Reset everything dependent on the target |
| `workflow reset --include-proxies` | Also tear down capture proxies |
| `workflow reset --delete-storage` | Also delete the snapshot storage (S3) |
| `workflow reset --keep-output-artifacts` | Preserve workflow outputs even on reset |
| `workflow reset --all` | Wipe all migration-managed resources |

**`reset --delete-storage` deletes the S3 snapshot.** Always delete
the snapshot via the cluster API first — see the agent rules in
`SKILL.md`.

### Help
- `workflow --help`
- `workflow <subcommand> --help`

---

## `console` (legacy, per-stage)

Useful for one-off operations and ad-hoc inspection. The four major
phases each have a subcommand tree:

| Subcommand | Purpose | Common actions |
|---|---|---|
| `console clusters` | Source / target connectivity tests | `connection-check`, `cat-indices`, `clear-indices` |
| `console snapshot` | Create snapshot from source | `create`, `delete`, `status` |
| `console metadata` | Migrate index templates + settings | `evaluate`, `migrate` |
| `console backfill` | Bulk-load documents (RFS) | `start`, `stop`, `scale`, `status` |
| `console replayer` | Replay captured live traffic | `start`, `stop`, `status` |
| `console kafka` | Kafka topic inspection | `describe-topic-records`, `delete-topic` |
| `console completion` | Shell completion | `bash`, `zsh` |

Quick reference:

```bash
console clusters connection-check         # do source + target answer?
console snapshot create                    # snapshot the source
console metadata evaluate                  # dry-run metadata migration
console metadata migrate                   # apply index templates / settings
console backfill start                     # spin up the RFS workers
console backfill scale --units 5           # add more workers
console backfill status                    # progress
console replayer start                     # begin replay of captured traffic
console replayer status                    # replay progress
```

Each subcommand has `--help`. The "modern" workflow runs all of
these for you in the right order; treat `console` as the manual
escape hatch.

---

## When to reach for which

- Brand-new migration → **`workflow configure sample --load` + edit
  + `workflow submit`**. The workflow handles ordering, gates, retry.
- Spot-check what's happening → `workflow status`, `workflow output -l ...`.
- Cluster health / connectivity / index inspection →
  `console clusters connection-check`, `console clusters cat-indices`.
- Recover from a bad metadata migration → `workflow reset
  metadata.<release>` (NOT a manual `console metadata` rerun, unless
  you're certain you're outside the workflow's scope).
- Stuck workflow that won't unblock → `workflow log` first, then
  consider `workflow reset --cascade` for the offending resource.

## Authoritative docs

- [Workflow CLI Overview](https://github.com/opensearch-project/opensearch-migrations/wiki/Workflow-CLI-Overview)
- [Workflow CLI Getting Started](https://github.com/opensearch-project/opensearch-migrations/wiki/Workflow-CLI-Getting-Started)
- [Backfill Workflow](https://github.com/opensearch-project/opensearch-migrations/wiki/Backfill-Workflow)
- [Capture and Replay Workflow](https://github.com/opensearch-project/opensearch-migrations/wiki/Capture-and-Replay-Workflow)
- [Migration console pod](https://github.com/opensearch-project/opensearch-migrations/wiki/Migration-console)
