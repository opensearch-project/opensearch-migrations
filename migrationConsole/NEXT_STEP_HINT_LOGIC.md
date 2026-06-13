# Next-Step Hint Logic

One-line hints printed at the end of successful `workflow` commands to guide users through the migration CLI sequence without memorising the full command set.

## User journey

```
workflow configure edit
  → Hint: `workflow submit` to start the migration

workflow submit
  → Hint: `workflow manage` to monitor progress

workflow approve step <gate>
  → Hint: `workflow manage` to monitor progress,
          or `workflow approve step --list` to check for more gates

workflow approve change <gate>
  → Hint: `workflow manage` to monitor progress

workflow approve retry <gate>
  → Hint: `workflow manage` to monitor progress
```

## Implementation

All hint strings live in a single module:

```
console_link/workflow/commands/hints.py
```

Functions: `hint_after_configure_edit`, `hint_after_submit`, `hint_after_approve_step`, `hint_after_approve_change`, `hint_after_approve_retry`. Each calls `click.echo(f"\nHint: ...")`.

### Call sites

| File | Location | Condition |
|------|----------|-----------|
| `configure.py` | end of `_handle_editor_edit` | after valid save + `process_secrets` |
| `configure.py` | end of `_handle_stdin_edit` | after valid save + `process_secrets` |
| `submit.py` | after success block | only when `--wait` is **not** set |
| `approve.py` | end of `_run_subcommand` | after `_apply_approvals`, dispatched by `category` |

### Why `--wait` suppresses the submit hint

When `--wait` is used, `submit_command` already blocks until the workflow finishes and prints the final phase. A "monitor with manage" hint after that is misleading.

### Why approve hints are static (not live-queried)

After patching an ApprovalGate CRD to `Approved`, the Argo workflow node may not have unblocked yet and the next gate may not be in `waiting` state. Re-querying immediately would give a stale view. The hint therefore points to `workflow approve step --list` rather than listing specific next gates.

### "Save anyway" path not hinted

`workflow configure edit` lets users save a config that failed validation (choice `s`). This path does **not** emit a hint because the config is known-invalid and `workflow submit` would fail immediately.
