"""One-line next-step hints printed at the end of each successful workflow command."""

import click


def _hint(msg: str) -> None:
    click.echo(f"\nHint: {msg}")


def _hint_for_phase(phase: str, *, running_hint: str, succeeded_hint: str, failed_hint: str) -> None:
    """Emit a phase-appropriate hint. Silently no-ops for unknown/empty phases."""
    p = (phase or '').lower()
    if p in ('running', 'pending'):
        _hint(running_hint)
    elif p == 'succeeded':
        _hint(succeeded_hint)
    elif p in ('failed', 'error', 'stopped'):
        _hint(failed_hint)
    # unknown / empty phase: no hint (e.g. workflow not found, TUI opened before submit)


# ── configure ──────────────────────────────────────────────────────────────

def hint_after_configure_edit() -> None:
    _hint("`workflow submit` to start the migration")


# ── submit ─────────────────────────────────────────────────────────────────

def hint_after_submit() -> None:
    """Hint after `workflow submit` (no --wait): workflow is now queued."""
    _hint("`workflow manage` to monitor progress")


def hint_after_submit_wait(phase: str) -> None:
    """Hint after `workflow submit --wait` completes with a known phase."""
    _hint_for_phase(
        phase,
        running_hint="`workflow manage` to monitor progress",
        succeeded_hint="migration complete — view results with `workflow show`",
        failed_hint="update config with `workflow configure edit`, then resubmit with `workflow submit`",
    )


def hint_on_submit_error() -> None:
    """Hint when the submit script itself fails (e.g. config validation error)."""
    _hint("fix the issue above, then update config with `workflow configure edit`")


# ── approve ────────────────────────────────────────────────────────────────

def hint_after_approve_step() -> None:
    _hint("`workflow manage` to monitor progress, or `workflow approve step --list` to check for more gates")


def hint_after_approve_change() -> None:
    _hint("`workflow manage` to monitor progress")


def hint_after_approve_retry() -> None:
    _hint("`workflow manage` to monitor progress")


# ── status ─────────────────────────────────────────────────────────────────

def hint_after_status(phase: str) -> None:
    """Phase-aware hint after `workflow status` displays a single workflow."""
    _hint_for_phase(
        phase,
        running_hint="workflow is running — re-run `workflow status` or open TUI with `workflow manage`",
        succeeded_hint="migration complete — view results with `workflow show`",
        failed_hint="workflow failed — update config with `workflow configure edit`, then resubmit with `workflow submit`",
    )


# ── manage ─────────────────────────────────────────────────────────────────

def hint_after_manage(phase: str) -> None:
    """Phase-aware hint emitted after the TUI exits."""
    _hint_for_phase(
        phase,
        running_hint="workflow still running — re-open with `workflow manage` or check with `workflow status`",
        succeeded_hint="migration complete — view results with `workflow show`",
        failed_hint="workflow failed — update config with `workflow configure edit`, then resubmit with `workflow submit`",
    )


# ── show ───────────────────────────────────────────────────────────────────

def hint_after_show() -> None:
    """Hint after viewing migration output artifacts — this is the terminal step."""
    _hint("migration complete — no further action needed")
