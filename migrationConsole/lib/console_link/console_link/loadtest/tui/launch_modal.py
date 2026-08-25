"""New-run form for the `loadtest` TUI (opened with `n`).

Returns the build_k6_parameters kwargs for the app to submit, or None on close/cancel. Listing and
stopping runs is the job of the app's run table, not this form.

A run is one load profile with overrides, so the form asks for one profile and then shows what that
profile already sets — the form never has to guess, because the profile's WorkflowTemplate states
every setting with a value (see runs.profile_catalog).
"""
from typing import Dict, Optional

from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Container, Horizontal, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Static, Button, Input, Select, Checkbox, TextArea

# Profiles are discovered from the cluster (the chart's k6-<profile> WorkflowTemplates) and passed
# in as a catalog. This literal is only a fallback for when nothing is passed — an API error, or a
# headless test that constructs the modal directly — so the dropdown is never empty. A fallback
# entry carries no settings, which the form reads as "unknown", not as "has none".
_FALLBACK_PROFILES = [
    "ingest-steady", "ingest-ramp", "ingest-burst",
    "search-steady", "search-deep-paging", "search-ramp", "search-burst",
    "mixed-steady", "mixed-ramp", "mixed-burst",
]
_DEFAULT_PROFILE = "ingest-steady"

# The settings behind each of the two checkboxes, and the settings each free-text field overrides.
# A profile that has none of a field's settings gets no value from it.
_REGISTRY_ENV = "REGISTRY_ENABLED"
_CONTROL_ENV = "CONTROL_ENABLED"
_FIELD_ENV = {
    "rate": ("INGEST_RATE", "SEARCH_RATE"),
    "duration": ("DURATION",),
    "vus": ("INGEST_VUS", "SEARCH_VUS"),
}

_PROFILE_ID = "profile"
_PROFILE_SEL = f"#{_PROFILE_ID}"


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
    #about { margin-bottom: 1; }
    #overrides { height: 5; }
    """
    BINDINGS = [
        Binding("escape", "cancel", "Cancel", show=False),
        Binding("ctrl+s", "launch", "Launch", show=False),
    ]

    def __init__(self, default_target: str = "", catalog: Optional[Dict[str, dict]] = None):
        super().__init__()
        self._default_target = default_target
        self._catalog = dict(catalog) if catalog else {p: {} for p in _FALLBACK_PROFILES}
        self._profiles = sorted(self._catalog)
        # Gate the Select.Changed handler until the modal has finished mounting, so the initial
        # Changed event (fired while children are still being mounted) can't touch widgets before
        # they exist. on_mount() does the first seed.
        self._ready = False

    def compose(self) -> ComposeResult:
        default = _DEFAULT_PROFILE if _DEFAULT_PROFILE in self._catalog else self._profiles[0]
        with Container(id="dialog"):
            yield Static("New k6 load test run", id="title")
            with VerticalScroll(id="body"):
                yield Select([(p, p) for p in self._profiles], value=default,
                             allow_blank=False, id=_PROFILE_ID)
                yield Static("", id="about")
                yield Input(value=self._default_target,
                            placeholder="target URL (optional) — default https://capture-proxy:9200",
                            id="target")
                with Horizontal(id="row"):
                    yield Input(placeholder="rate", id="rate")
                    yield Input(placeholder="duration", id="duration")
                    yield Input(placeholder="vus", id="vus")
                    yield Input(placeholder="parallelism", id="parallelism")
                yield Checkbox("registry enabled (consistency ring)", id="registry")
                yield Checkbox("control enabled (chaos bus)", id="control")
                yield Static("overrides (one KEY=VALUE per line):")
                yield TextArea(id="overrides")
            with Horizontal(id="buttons"):
                yield Button("Launch", id="launch", variant="success")
                yield Button("Close", id="close")

    def on_mount(self) -> None:
        self._sync_to_profile()
        self._ready = True
        self.query_one(_PROFILE_SEL, Select).focus()

    # --- Profile-driven state ---

    @property
    def _env(self) -> Dict[str, str]:
        """The selected profile's settings and their values. Empty means "not known" (the caller
        passed no catalog), which the form treats as "override nothing on this profile's behalf"."""
        return self._catalog.get(self.query_one(_PROFILE_SEL, Select).value, {}).get("env", {})

    def on_select_changed(self, event: Select.Changed) -> None:
        if self._ready and event.select.id == _PROFILE_ID:
            self._sync_to_profile()

    def _sync_to_profile(self) -> None:
        """Point the form at the selected profile: describe it, show what each field would replace,
        and seed the checkboxes from the template's own values rather than from the profile's name.

        A checkbox for a setting the profile does not have is disabled and cleared, so it cannot
        send an override that the submission would have to reject.
        """
        profile = self.query_one(_PROFILE_SEL, Select).value
        entry = self._catalog.get(profile, {})
        env = entry.get("env", {})

        about = entry.get("description") or ""
        if env:
            about = f"{about}\n[dim]{len(env)} settings — override any by name below[/]".strip()
        self.query_one("#about", Static).update(about or "[dim]settings unknown (no cluster)[/]")

        # A ramping profile takes its timing and rate from the stage list, so saying what --rate and
        # --duration currently are would be a lie: they are not what the run follows.
        ramping = env.get("EXECUTOR", "").startswith("ramping")
        for field, names in _FIELD_ENV.items():
            values = [env[n] for n in names if n in env]
            if ramping and field in ("rate", "duration"):
                shown = "set by stages"
            else:
                shown = "/".join(values)
            self.query_one(f"#{field}", Input).placeholder = f"{field} ({shown})" if shown else field

        for widget_id, name in (("registry", _REGISTRY_ENV), ("control", _CONTROL_ENV)):
            box = self.query_one(f"#{widget_id}", Checkbox)
            box.disabled = name not in env
            box.value = env.get(name, "").lower() == "true"

    # --- Result ---

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

    def _toggle(self, wid: str) -> Optional[bool]:
        """A toggle's value, or None when the profile has no such setting.

        None means "leave the profile alone". For a setting the profile does have, the checkbox is
        authoritative: an unchecked box means off, not "inherit" — that is what lets a user turn off
        something a profile turns on. _sync_to_profile() seeds the box from the profile, so leaving
        it alone reproduces the profile's own behavior.
        """
        box = self.query_one(f"#{wid}", Checkbox)
        return None if box.disabled else bool(box.value)

    def _fields(self) -> dict:
        return {
            "config_name": self.query_one(_PROFILE_SEL, Select).value,
            "parallelism": self._val("parallelism") or 1,
            "target_url": self._val("target"),
            "rate": self._val("rate"),
            "duration": self._val("duration"),
            "vus": self._val("vus"),
            "registry_enabled": self._toggle("registry"),
            "control_enabled": self._toggle("control"),
            "overrides_text": self.query_one("#overrides", TextArea).text or None,
        }
