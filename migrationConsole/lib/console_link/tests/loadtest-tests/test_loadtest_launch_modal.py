"""Tests for the load-test launch form (the `n` modal of the `loadtest` TUI).

Driven headless via Textual's run_test(); wrapped in asyncio.run so no pytest-asyncio is needed.
The form returns the build_k6_parameters kwargs directly, or None when cancelled.
"""
import asyncio

from textual.app import App
from textual.widgets import Input, Checkbox, TextArea, Select, Static

from console_link.loadtest.tui.launch_modal import LoadTestLaunchModal

# What runs.profile_catalog() hands the form: every launchable profile, with the settings it
# carries. The form reads these instead of inferring anything from a profile's name.
CATALOG = {
    "ingest-steady": {
        "scenario": "ingest", "description": "Steady ingest.",
        "env": {"INGEST_RATE": "50", "INGEST_VUS": "20", "DURATION": "5m",
                "CONTROL_ENABLED": "false", "EXECUTOR": "constant-arrival-rate"},
    },
    "ingest-burst": {
        "scenario": "ingest", "description": "Bursty ingest.",
        "env": {"INGEST_VUS": "60", "CONTROL_ENABLED": "false",
                "EXECUTOR": "ramping-arrival-rate"},
    },
    "mixed-steady": {
        "scenario": "mixed", "description": "Both streams.",
        "env": {"INGEST_RATE": "30", "SEARCH_RATE": "20", "INGEST_VUS": "15",
                "SEARCH_VUS": "15", "DURATION": "5m", "REGISTRY_ENABLED": "true",
                "CONTROL_ENABLED": "false", "EXECUTOR": "constant-arrival-rate"},
    },
}


class _Host(App):
    def __init__(self, catalog=CATALOG):
        super().__init__()
        self._catalog = catalog
        self.result = "UNSET"

    def on_mount(self) -> None:
        self.push_screen(
            LoadTestLaunchModal(default_target="http://t:9200", catalog=self._catalog),
            lambda v: setattr(self, "result", v))


def _drive(steps, key=None, click=None, catalog=CATALOG):
    async def _run():
        app = _Host(catalog=catalog)
        async with app.run_test(size=(120, 60)) as pilot:
            await pilot.pause()
            steps(app.screen)
            await pilot.pause()
            if click:
                await pilot.click(click)
            if key:
                await pilot.press(key)
            await pilot.pause()
        return app.result
    return asyncio.run(_run())


def test_launch_via_ctrl_s():
    def steps(m):
        m.query_one("#profile", Select).value = "mixed-steady"
        m.query_one("#rate", Input).value = "100"
        m.query_one("#parallelism", Input).value = "4"
        m.query_one("#overrides", TextArea).load_text("BULK_BATCH_SIZE=5")
    f = _drive(steps, key="ctrl+s")
    assert f["config_name"] == "mixed-steady"
    assert f["rate"] == "100" and f["parallelism"] == "4"
    assert f["overrides_text"] == "BULK_BATCH_SIZE=5"
    assert f["duration"] is None
    # There is no scenario field any more: the profile's template says which scenario it runs.
    assert "scenario" not in f


def test_launch_via_button():
    f = _drive(lambda m: None, click="#launch")
    assert f["config_name"] == "ingest-steady"


def test_toggles_are_seeded_from_the_profile():
    """The form reads the profile's own values, so leaving the boxes alone reproduces it. This used
    to be guessed from the profile name starting with "mixed-"."""
    f = _drive(lambda m: None, key="ctrl+s")
    assert f["control_enabled"] is False        # ingest-steady states CONTROL_ENABLED=false
    assert f["registry_enabled"] is None        # ingest has no such setting at all


def test_mixed_profile_seeds_registry_on():
    def steps(m):
        m.query_one("#profile", Select).value = "mixed-steady"
    f = _drive(steps, key="ctrl+s")
    assert f["registry_enabled"] is True


def test_mixed_registry_can_be_unchecked_to_false():
    # The whole point: after mixed seeds registry on, the user can uncheck it and get False, which
    # overrides the profile's true. The pause between selecting the profile and unchecking mirrors
    # real use — the auto-seed (an async Select.Changed) lands first, then the user unchecks.
    async def _run():
        app = _Host()
        async with app.run_test(size=(120, 60)) as pilot:
            await pilot.pause()
            app.screen.query_one("#profile", Select).value = "mixed-steady"
            await pilot.pause()  # let the profile change re-seed registry -> True
            assert app.screen.query_one("#registry", Checkbox).value is True
            app.screen.query_one("#registry", Checkbox).value = False
            await pilot.pause()
            await pilot.press("ctrl+s")
            await pilot.pause()
        return app.result
    assert asyncio.run(_run())["registry_enabled"] is False


def test_a_toggle_the_profile_lacks_is_disabled():
    """A setting the profile does not have cannot be sent — the submission would refuse it, so the
    form must not offer it."""
    def steps(m):
        assert m.query_one("#registry", Checkbox).disabled is True   # ingest has no ring
        assert m.query_one("#control", Checkbox).disabled is False
    _drive(steps, key="escape")


def test_fields_show_what_the_profile_currently_sets():
    def steps(m):
        assert "50" in m.query_one("#rate", Input).placeholder
        assert "5m" in m.query_one("#duration", Input).placeholder
        assert "Steady ingest." in str(m.query_one("#about", Static).content)
    _drive(steps, key="escape")


def test_a_ramping_profile_does_not_claim_a_rate():
    """A ramping profile takes its rate and timing from the stage list, so showing the constant-rate
    values would describe a run that does not happen."""
    async def _run():
        app = _Host()
        async with app.run_test(size=(120, 60)) as pilot:
            await pilot.pause()
            app.screen.query_one("#profile", Select).value = "ingest-burst"
            await pilot.pause()
            return app.screen.query_one("#rate", Input).placeholder
    assert "set by stages" in asyncio.run(_run())


def test_profile_options_come_from_the_catalog():
    # Setting the Select to a catalog profile only works if it became an option (Textual rejects
    # values outside the option list), so a successful launch with it proves the wiring.
    catalog = {"alpha-steady": {"scenario": "a", "description": "", "env": {}},
               "beta-burst": {"scenario": "b", "description": "", "env": {}}}

    def steps(m):
        m.query_one("#profile", Select).value = "beta-burst"
    f = _drive(steps, key="ctrl+s", catalog=catalog)
    assert f["config_name"] == "beta-burst"


def test_unknown_settings_override_nothing_on_their_own():
    """With no catalog (an API error, or a cluster the form could not reach) the form still opens,
    but it must not send toggles it cannot vouch for."""
    f = _drive(lambda m: None, key="ctrl+s", catalog=None)
    assert f["registry_enabled"] is None and f["control_enabled"] is None


def test_parallelism_defaults_to_one():
    # empty parallelism input -> 1 (build_k6_parameters coerces to int)
    assert _drive(lambda m: None, key="ctrl+s")["parallelism"] == 1


def test_cancel_returns_none():
    assert _drive(lambda m: None, key="escape") is None


def test_close_button_returns_none():
    assert _drive(lambda m: None, click="#close") is None
