# Next-Step Hint Logic

One-line hints printed at the end of `workflow` commands to guide users through the migration CLI sequence. Hints appear after both success and certain error states.

## Module

```
console_link/workflow/commands/hints.py
```

All hint strings are defined here. The shared helper `_hint_for_phase(phase, *, running_hint, succeeded_hint, failed_hint)` dispatches by Argo workflow phase (`Running`/`Pending`, `Succeeded`, `Failed`/`Error`/`Stopped`). Unknown or empty phases produce no hint.

---

## Full hint map

### `workflow configure edit` → static

```
Hint: `workflow submit` to start the migration
```

Call site: end of `_handle_editor_edit` and `_handle_stdin_edit` in `configure.py`, after a valid save. The "save anyway" (validation-failed) path is **not** hinted — the config is known-invalid and `workflow submit` would fail immediately.

---

### `workflow submit` → static / phase-aware

**Without `--wait`:**
```
Hint: `workflow manage` to monitor progress
```

**With `--wait`** (phase-aware, from `_handle_workflow_wait` return value):

| Phase | Hint |
|-------|------|
| Running / Pending | `` `workflow manage` to monitor progress `` |
| Succeeded | `migration complete — view results with `workflow show`` |
| Failed / Error / Stopped | `update config with `workflow configure edit`, then resubmit with `workflow submit`` |

**On script error** (`CalledProcessError` or general `Exception` from the submit script):
```
Hint: fix the issue above, then update config with `workflow configure edit`
```
Not emitted for `FileNotFoundError` (that is a setup/environment issue, not a config issue).

Call site: `submit.py`. `_handle_workflow_wait` now returns the phase string (`'Running'` on timeout, `''` on unexpected error).

---

### `workflow approve step` → static

```
Hint: `workflow manage` to monitor progress, or `workflow approve step --list` to check for more gates
```

Re-querying live gate state after approval is unreliable: the Argo node may not yet have unblocked, and the next gate may not be in `waiting` state. The hint therefore points to `--list` for follow-up discovery rather than naming specific next gates.

### `workflow approve change` / `workflow approve retry` → static

```
Hint: `workflow manage` to monitor progress
```

Call site: `_run_subcommand` in `approve.py`, after `_apply_approvals`, dispatched by `category`.

---

### `workflow status` → phase-aware

| Phase | Hint |
|-------|------|
| Running / Pending | `workflow is running — re-run `workflow status` or open TUI with `workflow manage`` |
| Succeeded | `migration complete — view results with `workflow show`` |
| Failed / Error / Stopped | `workflow failed — update config with `workflow configure edit`, then resubmit with `workflow submit`` |
| Unknown / no workflow | *(no hint)* |

**Implementation:** `StatusCommandHandler` stores `self._last_phase` (set in `_display_workflow_with_tree` from the already-fetched workflow data — no extra API call). The Click command reads `handler._last_phase` after `handle_status_command` returns.

Hint is **not** emitted for `--all-workflows` (multiple workflows shown, no single canonical phase) or `--resource-view` (resource tree view, phase not surfaced).

---

### `workflow manage` → phase-aware

| Phase | Hint |
|-------|------|
| Running / Pending | `workflow still running — re-open with `workflow manage` or check with `workflow status`` |
| Succeeded | `migration complete — view results with `workflow show`` |
| Failed / Error / Stopped | `workflow failed — update config with `workflow configure edit`, then resubmit with `workflow submit`` |
| Unknown / no workflow | *(no hint)* |

**Implementation:** After `app.run()` returns, `manage.py` calls `get_workflow(namespace, workflow_name)` (one lightweight Kubernetes API call) to read the current phase. The TUI itself does not expose an exit phase. Exceptions during this lookup are swallowed — a failed lookup simply produces no hint.

---

### `workflow show` → terminal (static)

```
Hint: migration complete — no further action needed
```

Emitted only when the command is in output-viewing mode: `not list_resources and not history and not run_selector`. List (`--list`), history (`--history`), and retained-run (`--run`) modes do not hint.

Call site: end of `show_command` in `show.py`.

---

## Commands with no hint

| Command | Reason |
|---------|--------|
| `workflow configure view` | Informational read; no action implied |
| `workflow configure sample` | Informational read |
| `workflow configure credentials *` | Credential management sub-flow; not in the migration journey |
| `workflow log` | Output streaming; user is already monitoring |
| `workflow reset` | Recovery step; next action depends on context |
| `workflow status --all-workflows` | Multiple workflows shown; no single canonical next step |
| `workflow status --resource-view` | Resource tree view; phase not surfaced |
| `workflow show --list` / `--history` / `--run` | Exploration/audit modes, not end-of-migration |
