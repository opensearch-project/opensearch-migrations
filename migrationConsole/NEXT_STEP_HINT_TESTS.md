# Next-Step Hint — Tests

## File

```
tests/workflow-tests/test_hints.py
```

Both unit and CLI integration tests are consolidated in one file.

Run with:
```bash
uv run pytest tests/workflow-tests/test_hints.py -v
```

---

## Structure

| Class | Type | What it tests |
|-------|------|---------------|
| `TestHintForPhase` | unit | `_hint_for_phase` dispatches to the right branch for every Argo phase, and emits nothing for unknown/empty phases |
| `TestPublicHintFunctions` | unit | Every public function in `hints.py` produces the expected text (checked via `capsys`) |
| `TestConfigureEditHints` | CLI | `workflow configure edit` — stdin valid, stdin invalid, editor valid, save-anyway, discard, edit-again-then-valid |
| `TestSubmitHints` | CLI | `workflow submit` — no `--wait`, `--wait` Succeeded/Failed/timeout, `CalledProcessError`, general `Exception`, `FileNotFoundError` |
| `TestApproveHints` | CLI | `workflow approve step/change/retry` — approval, `--all`, `--list` (no hint), no-match (no hint) |
| `TestStatusHints` | CLI | `workflow status` — Running/Pending/Succeeded/Failed/Error, `--all-workflows` (no hint), not-found (no hint) |
| `TestManageHints` | CLI | `workflow manage` — Running/Succeeded/Failed/Error phases, workflow not found, `get_workflow` exception, TUI crash |
| `TestShowHints` | CLI | `workflow show` — resource view, task view, `--list` / `--history` / `--run` / `--all` (all absent) |

---

## Key patterns

**Unit tests** call hint functions directly and capture output with pytest's `capsys`:
```python
def test_hint_after_show(self, capsys):
    from console_link.workflow.commands.hints import hint_after_show
    hint_after_show()
    assert "no further action needed" in capsys.readouterr().out
```

**CLI integration tests** invoke the full Click command through `CliRunner` with mocked K8s/Argo dependencies, then assert on `result.output`:
```python
@patch("console_link.workflow.commands.submit.ScriptRunner")
...
def test_success_no_wait_shows_manage_hint(self, ..., mock_runner_class, mock_store_class):
    mock_runner_class.return_value.submit_workflow.return_value = {"workflow_name": "...", "warnings": []}
    self._setup_store(mock_store_class)
    result = CliRunner().invoke(workflow_cli, ["submit"])
    assert "Hint:" in result.output
    assert "`workflow manage`" in result.output
```

Every scenario where a hint is **absent** asserts `"Hint:" not in result.output`, guarding against leakage into list/error/wrong-command paths.

---

## Non-obvious implementation detail

`WorkflowConfig(raw_yaml="...")` leaves `data={}` (falsy). The submit command guards early with `if not config.data`, which exits before the script runner is reached. Tests must pass both `data` and `raw_yaml`:

```python
WorkflowConfig(data={"sourceClusters": {}}, raw_yaml="sourceClusters: {}\n")
```

This was the root cause of the initial 6 submit test failures.

---

## Absence assertions are equally important

Every no-hint scenario asserts `"Hint:" not in result.output`. This prevents hint functions from silently firing on paths they shouldn't — e.g., `--list` modes showing "migration complete", or error recovery hints appearing on success.
