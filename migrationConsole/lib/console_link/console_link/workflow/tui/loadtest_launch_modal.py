"""New-run form for the `workflow loadtest` TUI (opened with `n`).

Returns the build_k6_parameters kwargs for the app to submit, or None on close/cancel. Listing and
stopping runs is the job of the app's run table, not this form.
"""
from typing import List, Optional

from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Container, Horizontal, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Static, Button, Input, Select, Checkbox, TextArea

# Scenarios are discovered from the cluster (the k6-testrun-examples ConfigMap keys) and passed
# into the modal. This literal is only a fallback for when that lookup returns nothing.
_FALLBACK_SCENARIOS = ["ingest", "search", "mixed"]
# Presets live in the scripts image, so the caller passes in the CLI's list (loadtest.CONFIG_PRESETS).
# This literal is only a fallback for when nothing is passed — e.g. a headless test that constructs
# the modal directly — so the config dropdown is never empty.
_FALLBACK_PRESETS = [
    "ingest-steady", "ingest-ramp", "ingest-burst",
    "search-steady", "search-deep-paging", "search-ramp", "search-burst",
    "mixed-steady", "mixed-ramp", "mixed-burst",
]

# Widget ids for the two Selects we query back out repeatedly, with their "#id" selectors derived
# from them so the pair can't drift.
_SCENARIO_ID = "scenario"
_CONFIG_ID = "config"
_SCENARIO_SEL = f"#{_SCENARIO_ID}"
_CONFIG_SEL = f"#{_CONFIG_ID}"


class LoadTestLaunchModal(ModalScreen[Optional[dict]]):
    CSS = """
    LoadTestLaunchModal { align: center middle; background: $background 60%; }
    #dialog { width: 78; height: 90%; border: thick $primary; background: $surface; padding: 1 2; }
    #title { text-align: center; margin-bottom: 1; }
    #body { height: 1fr; }
    #row { height: auto; }
    #row Input { width: 1fr; margin: 0 1 0 0; }
    #buttons { align: center middle; height: 3; }
    Button { margin: 0 1; min-width: 12; }
    /* Lift editable fields off the dark dialog surface so it's obvious where input goes. */
    Input, Select, TextArea { margin-bottom: 1; background: $boost; }
    Input:focus, Select:focus, Select:focus-within, TextArea:focus { background: $panel; }
    #overrides { height: 5; }
    """
    BINDINGS = [
        Binding("escape", "cancel", "Cancel", show=False),
        Binding("ctrl+s", "launch", "Launch", show=False),
    ]

    def __init__(self, default_target: str = "", presets: Optional[List[str]] = None,
                 scenarios: Optional[List[str]] = None):
        super().__init__()
        self._default_target = default_target
        # Scenarios and config presets discovered from the cluster; fall back to known sets if none
        # were passed (API error, or a headless test with no cluster).
        self._scenarios = list(scenarios) if scenarios else list(_FALLBACK_SCENARIOS)
        self._presets = list(presets) if presets else list(_FALLBACK_PRESETS)
        # Gate the Select.Changed handler until the modal has finished mounting, so the
        # initial Changed event (fired while children are still being mounted) can't touch
        # the toggle checkboxes before they exist. on_mount() does the first seed.
        self._ready = False
        # This Textual's "no selection" sentinel, captured from the blank config Select at mount.
        # Its identity/name varies across versions (Select.BLANK vs Select.NULL, and only one is a
        # settable value), so we reuse the widget's own blank value rather than naming a sentinel.
        self._config_blank = None

    def compose(self) -> ComposeResult:
        with Container(id="dialog"):
            yield Static("New k6 load test run", id="title")
            with VerticalScroll(id="body"):
                default_scenario = "ingest" if "ingest" in self._scenarios else self._scenarios[0]
                yield Select([(s, s) for s in self._scenarios], value=default_scenario,
                             allow_blank=False, id=_SCENARIO_ID)
                yield Select([(p, p) for p in self._presets], allow_blank=True,
                             prompt="config preset — blank = <scenario>-steady", id=_CONFIG_ID)
                yield Input(value=self._default_target,
                            placeholder="target URL (optional) — default https://capture-proxy:9200",
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
        # The config Select is blank on mount (allow_blank, no value), so its value here IS this
        # Textual's blank sentinel — capture it to reset to later. Guard against a str in case a
        # future Textual auto-selects the first option.
        cfg_value = self.query_one(_CONFIG_SEL, Select).value
        self._config_blank = None if isinstance(cfg_value, str) else cfg_value
        # Seed the toggle checkboxes from the default scenario's preset before enabling the
        # change handler, then focus the scenario select.
        self._sync_toggle_defaults()
        self._ready = True
        self.query_one(_SCENARIO_SEL, Select).focus()

    @staticmethod
    def _default_toggles(scenario: str, config_name: Optional[str]) -> tuple:
        """Preset defaults for the (registry, control) toggles, so a freshly-opened box reflects
        what the run would do if left untouched. Only the mixed-* presets ship the consistency
        ring on (REGISTRY_ENABLED=true in k6-config/mixed-*.env); CONTROL_ENABLED is off in every
        preset, and both default off in lib/id-registry.js / lib/control.js otherwise."""
        preset = config_name or f"{scenario}-steady"
        return preset.startswith("mixed-"), False

    def _config_value(self) -> Optional[str]:
        """Selected config preset, or None when left blank (→ build defaults to <scenario>-steady).

        A blank Select yields a sentinel (NoSelection), not a str — and its identity/name varies
        across Textual versions (Select.BLANK vs Select.NULL) — so treat anything non-str as blank
        rather than comparing against a specific sentinel object."""
        value = self.query_one(_CONFIG_SEL, Select).value
        return value if isinstance(value, str) else None

    def _sync_toggle_defaults(self) -> None:
        """Point the toggle checkboxes at the current scenario/config's preset defaults. Called
        on mount and whenever the scenario changes; a manual toggle after that is preserved until
        the scenario is changed again."""
        scenario = self.query_one(_SCENARIO_SEL, Select).value
        registry_default, control_default = self._default_toggles(scenario, self._config_value())
        self.query_one("#registry", Checkbox).value = registry_default
        self.query_one("#control", Checkbox).value = control_default

    def on_select_changed(self, event: Select.Changed) -> None:
        # Re-seed toggles when the scenario changes (e.g. picking "mixed" pre-checks registry).
        # Also clear any stale config preset so a blank tracks the new scenario's <scenario>-steady.
        if self._ready and event.select.id == _SCENARIO_ID:
            self.query_one(_CONFIG_SEL, Select).value = self._config_blank
            self._sync_toggle_defaults()

    def action_cancel(self) -> None:
        self.dismiss(None)

    def action_launch(self) -> None:
        self.dismiss(self._fields())

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "close":
            self.dismiss(None)
        elif event.button.id == "launch":
            self.dismiss(self._fields())

    def _val(self, wid: str) -> Optional[str]:
        v = self.query_one(f"#{wid}", Input).value.strip()
        return v or None

    def _fields(self) -> dict:
        return {
            "scenario": self.query_one(_SCENARIO_SEL, Select).value,
            "config_name": self._config_value(),
            "parallelism": self._val("parallelism") or 1,
            "target_url": self._val("target"),
            "rate": self._val("rate"),
            "duration": self._val("duration"),
            "vus": self._val("vus"),
            # The checkboxes are authoritative: an unchecked box means "off" (False), not
            # "inherit" (None). This is what lets the user actually disable a toggle that a
            # preset turns on (e.g. REGISTRY_ENABLED=true in the mixed-* presets) — otherwise
            # unchecking would send None, no override would be emitted, and the preset's value
            # would silently win. The boxes are seeded from the preset default in _sync_toggle_
            # defaults() so leaving them alone reproduces the preset's behavior.
            "registry_enabled": bool(self.query_one("#registry", Checkbox).value),
            "control_enabled": bool(self.query_one("#control", Checkbox).value),
            "overrides_text": self.query_one("#overrides", TextArea).text or None,
        }
