"""k6 control panel modal for the `workflow manage` TUI (opened with `k`).

Two things in one screen: **launch** a new run, and **list/stop** the currently-running runs.
Returns a command dict for the app to execute, or None on close/cancel:

    {"kind": "launch", "fields": {... build_k6_parameters kwargs ...}}
    {"kind": "stop",   "names": [run, ...]}
    None

Stopping a run deletes its TestRun CR (the operator tears down the pods), so there is no separate
"delete" toggle.
"""
from typing import List, Optional

from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Container, Horizontal, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Static, Button, Input, Select, Checkbox, TextArea, Rule

SCENARIOS = ["ingest", "search", "mixed"]


class K6PanelModal(ModalScreen[Optional[dict]]):
    CSS = """
    K6PanelModal { align: center middle; background: $background 60%; }
    #dialog { width: 78; height: 90%; border: thick $primary; background: $surface; padding: 1 2; }
    #title { text-align: center; margin-bottom: 1; }
    #body { height: 1fr; }
    .section { margin-top: 1; text-style: bold; }
    .run-row { height: auto; }
    .run-row Static { width: 1fr; content-align: left middle; }
    #row { height: auto; }
    #row Input { width: 1fr; margin: 0 1 0 0; }
    #buttons { align: center middle; height: 3; }
    Button { margin: 0 1; min-width: 12; }
    Input, Select, TextArea { margin-bottom: 1; }
    #overrides { height: 5; }
    """
    BINDINGS = [
        Binding("escape", "cancel", "Cancel", show=False),
        Binding("ctrl+s", "launch", "Launch", show=False),
    ]

    def __init__(self, runs: Optional[List[dict]] = None, default_target: str = ""):
        super().__init__()
        self._runs = runs or []
        self._run_names = [r.get("name", "") for r in self._runs if r.get("name")]
        self._default_target = default_target

    def compose(self) -> ComposeResult:
        with Container(id="dialog"):
            yield Static("k6 load tests", id="title")
            with VerticalScroll(id="body"):
                yield Static("Running runs", classes="section")
                if self._runs:
                    for i, r in enumerate(self._runs):
                        with Horizontal(classes="run-row"):
                            yield Static(f"{r['name']}  [dim]{r['scenario']} · "
                                         f"{r['phase']} · {r['age']}[/]")
                            yield Button("Stop", id=f"stop-{i}", name=r["name"], variant="warning")
                    with Horizontal(classes="run-row"):
                        yield Button("Stop all", id="stopall", variant="error")
                else:
                    yield Static("  [dim](none)[/]")

                yield Rule()
                yield Static("New run", classes="section")
                yield Select([(s, s) for s in SCENARIOS], value="ingest",
                             allow_blank=False, id="scenario")
                yield Input(placeholder="config preset (default <scenario>-steady)", id="config")
                yield Input(value=self._default_target,
                            placeholder="target URL, e.g. https://<proxy>.ma.svc.cluster.local:9200",
                            id="target")
                with Horizontal(id="row"):
                    yield Input(placeholder="rate", id="rate")
                    yield Input(placeholder="duration (e.g. 30s)", id="duration")
                    yield Input(placeholder="vus", id="vus")
                    yield Input(placeholder="parallelism", id="parallelism")
                yield Checkbox("registry enabled (mixed consistency ring)", id="registry")
                yield Checkbox("control enabled (chaos bus)", id="control")
                yield Static("overrides (one KEY=VALUE per line):")
                yield TextArea(id="overrides")
            with Horizontal(id="buttons"):
                yield Button("Launch", id="launch", variant="success")
                yield Button("Close", id="close")

    def on_mount(self) -> None:
        self.query_one("#scenario", Select).focus()

    def action_cancel(self) -> None:
        self.dismiss(None)

    def action_launch(self) -> None:
        self.dismiss({"kind": "launch", "fields": self._fields()})

    def on_button_pressed(self, event: Button.Pressed) -> None:
        bid = event.button.id or ""
        if bid == "close":
            self.dismiss(None)
        elif bid == "launch":
            self.dismiss({"kind": "launch", "fields": self._fields()})
        elif bid == "stopall":
            self.dismiss({"kind": "stop", "names": list(self._run_names)})
        elif bid.startswith("stop-"):
            self.dismiss({"kind": "stop", "names": [event.button.name]})

    def _val(self, wid: str) -> Optional[str]:
        v = self.query_one(f"#{wid}", Input).value.strip()
        return v or None

    def _fields(self) -> dict:
        return {
            "scenario": self.query_one("#scenario", Select).value,
            "config_name": self._val("config"),
            "parallelism": self._val("parallelism") or 1,
            "target_url": self._val("target"),
            "rate": self._val("rate"),
            "duration": self._val("duration"),
            "vus": self._val("vus"),
            "registry_enabled": True if self.query_one("#registry", Checkbox).value else None,
            "control_enabled": True if self.query_one("#control", Checkbox).value else None,
            "overrides_text": self.query_one("#overrides", TextArea).text or None,
        }
