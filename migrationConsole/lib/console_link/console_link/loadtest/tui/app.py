"""Text-UI for `loadtest` — a live table of k6 runs, plus launch, stop and log viewing.

Every cluster call is wrapped, so a k6-side error only raises a notification instead of killing
the UI.

runs.py owns all cluster logic; this file is display and key handling only, which keeps the TUI and
the non-interactive subcommands on the same code.
"""
import logging
import os
from typing import Dict, List, Optional

from textual.app import App, ComposeResult
from textual.containers import Container
from textual.widgets import DataTable, Footer, Header, Static

from .confirm_modal import ConfirmModal
from .launch_modal import LoadTestLaunchModal

logger = logging.getLogger(__name__)

TABLE_ANCHOR = "runs"
COLUMNS = ("NAME", "SCENARIO", "STAGE", "PARALLEL", "AGE")
# Actions that act on the highlighted run, and so are advertised only when a run is highlighted.
ROW_ACTIONS = ("stop_run", "view_logs", "follow_logs")


class LoadTestApp(App):
    CSS = """
    DataTable { height: 1fr; }
    #status { height: 1; padding: 0 1; }
    """
    BINDINGS = [
        ("n", "new_run", "New run"),
        ("s", "stop_run", "Stop"),
        ("S", "stop_all", "Stop all"),
        ("l", "view_logs", "Logs"),
        ("f", "follow_logs", "Follow Logs"),
        ("r", "refresh", "Refresh"),
        ("q", "quit", "Quit"),
    ]

    def __init__(self, namespace: str, refresh_interval: float = 5.0):
        super().__init__()
        self.title = f"[{namespace}] k6 load tests"
        self._namespace = namespace
        self._refresh_interval = refresh_interval
        # Rows in display order, mirroring the DataTable — the cursor row index indexes into this.
        self._runs: List[Dict] = []
        self.is_exiting = False

    def compose(self) -> ComposeResult:
        yield Header()
        yield Container(DataTable(id=TABLE_ANCHOR, cursor_type="row"))
        yield Static("", id="status")
        yield Footer()

    def on_mount(self) -> None:
        self.table.add_columns(*COLUMNS)
        self.action_refresh()

    def on_unmount(self) -> None:
        self.is_exiting = True

    @property
    def table(self) -> DataTable:
        return self.query_one(f"#{TABLE_ANCHOR}", DataTable)

    # --- Refresh loop ---

    def action_refresh(self) -> None:
        self.run_worker(self._refresh_worker, thread=True, name="refresh_runs")

    def _refresh_worker(self) -> None:
        """Worker: fetch the runs off the main thread, then route back to it."""
        from ..runs import list_runs
        try:
            runs, error = list_runs(self._namespace), None
        except Exception as e:
            runs, error = None, str(e)
        if not self.is_exiting:
            self.call_from_thread(self._apply_runs, runs, error)

    def _apply_runs(self, runs: Optional[List[Dict]], error: Optional[str]) -> None:
        """Main thread: repaint the table and re-arm the refresh timer.

        A failed fetch keeps the last good table rather than blanking it — the run list is the whole
        screen, and a transient API error should not look like "no runs".
        """
        if error is None:
            self._runs = runs or []
            self._rebuild_table()
        else:
            self.notify(f"Could not list k6 runs: {error}", severity="error")
        self.set_timer(self._refresh_interval, self.action_refresh)

    def _rebuild_table(self) -> None:
        """Repaint the table, keeping the cursor on the same run across refreshes (row indexes
        move as runs are created and reaped, so the name is what has to be preserved)."""
        selected = self.selected_run_name
        table = self.table
        table.clear()
        for run in self._runs:
            table.add_row(run["name"], run["scenario"], run["phase"], run["parallelism"], run["age"])
        if selected:
            for i, run in enumerate(self._runs):
                if run["name"] == selected:
                    table.move_cursor(row=i)
                    break
        self._update_status()

    def _update_status(self) -> None:
        status = self.query_one("#status", Static)
        if not self._runs:
            status.update("[dim](no k6 runs — press `n` to launch one)[/]")
        elif name := self.selected_run_name:
            status.update(f"Run: [bold green]{name}[/]")
        else:
            status.update("")
        self.refresh_bindings()

    def on_data_table_row_highlighted(self, event: DataTable.RowHighlighted) -> None:
        self._update_status()

    @property
    def selected_run_name(self) -> Optional[str]:
        row = self.table.cursor_row
        if row is None or not (0 <= row < len(self._runs)):
            return None
        return self._runs[row]["name"] or None

    def check_action(self, action: str, parameters) -> Optional[bool]:
        """Hide the run-scoped options from the footer while no run is highlighted."""
        if action in ROW_ACTIONS and not self.selected_run_name:
            return None
        return True

    # --- Launch ---

    def action_new_run(self) -> None:
        from ..runs import CONFIG_PRESETS
        from ..testrun_utils import list_scenarios
        # Presets ship inside the scripts image, so there is nothing to look up in the cluster.
        try:
            scenarios = list_scenarios(self._namespace)
        except Exception as e:
            self.notify(f"Could not list k6 scenarios: {e}", severity="error")
            scenarios = []
        self.push_screen(LoadTestLaunchModal(presets=CONFIG_PRESETS, scenarios=scenarios),
                         self._on_launch)

    def _on_launch(self, fields: Optional[Dict]) -> None:
        """Submit the form's run. Dismissal (None) is a cancel, not a command."""
        if not fields:
            return
        from ..runs import build_k6_parameters, submit_k6_run
        try:
            name = submit_k6_run(self._namespace, build_k6_parameters(**fields))
            self.notify(f"✅ Submitted k6 run: {name}")
        except ValueError as e:
            self.notify(f"Invalid override: {e}", severity="error")
        except Exception as e:
            self.notify(f"k6 action failed: {e}", severity="error")
        self.action_refresh()

    # --- Stop ---

    def action_stop_run(self) -> None:
        if name := self.selected_run_name:
            self._confirm_stop([name], f"Stop k6 run '{name}'?")

    def action_stop_all(self) -> None:
        names = [r["name"] for r in self._runs if r["name"]]
        if not names:
            self.notify("No k6 runs to stop.", severity="warning")
            return
        self._confirm_stop(names, f"Stop all {len(names)} k6 run(s)?")

    def _confirm_stop(self, names: List[str], message: str) -> None:
        # Stopping a run deletes its Workflow; the TestRun it owns goes with it and the operator
        # tears down the pods. There is no graceful pause, so confirm first.
        self.push_screen(ConfirmModal(message),
                         lambda confirmed: self._stop(names) if confirmed else None)

    def _stop(self, names: List[str]) -> None:
        from ..testrun_utils import delete_workflow
        try:
            stopped = sum(1 for n in names if delete_workflow(self._namespace, n))
            self.notify(f"⏹ Stopped {stopped}/{len(names)} k6 run(s)")
        except Exception as e:
            self.notify(f"k6 action failed: {e}", severity="error")
        self.action_refresh()

    # --- Logs ---

    def action_view_logs(self) -> None:
        self._show_logs(follow=False)

    def action_follow_logs(self) -> None:
        self._show_logs(follow=True)

    def _show_logs(self, follow: bool) -> None:
        """Hand the terminal to kubectl + less, the same way the manage TUI shows pod logs. `+F`
        makes less follow the stream; `q` in less returns to the table."""
        name = self.selected_run_name
        if not name:
            return
        from ..runs import logs_command
        cmd = " ".join(logs_command(self._namespace, name, follow))
        try:
            with self.suspend():
                os.system("clear")
                os.system(f"{cmd} | less -R{' +F' if follow else ''}")
        except Exception as e:
            self.notify(f"Log Error: {e}", severity="error")
