"""One-line next-step hints printed at the end of each successful workflow command."""

import click


def _hint(msg: str) -> None:
    click.echo(f"\nHint: {msg}")


def hint_after_configure_edit() -> None:
    _hint("`workflow submit` to start the migration")


def hint_after_submit() -> None:
    _hint("`workflow manage` to monitor progress")


def hint_after_approve_step() -> None:
    _hint("`workflow manage` to monitor progress, or `workflow approve step --list` to check for more gates")


def hint_after_approve_change() -> None:
    _hint("`workflow manage` to monitor progress")


def hint_after_approve_retry() -> None:
    _hint("`workflow manage` to monitor progress")
