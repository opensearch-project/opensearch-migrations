"""Tests for the load-test launch form (the `n` modal of the `workflow loadtest` TUI).

Driven headless via Textual's run_test(); wrapped in asyncio.run so no pytest-asyncio is needed.
The form returns the build_k6_parameters kwargs directly, or None when cancelled.
"""
import asyncio

from textual.app import App
from textual.widgets import Input, Checkbox, TextArea, Select

from console_link.workflow.tui.loadtest_launch_modal import LoadTestLaunchModal


class _Host(App):
    def __init__(self, presets=None, scenarios=None):
        super().__init__()
        self._presets = presets
        self._scenarios = scenarios
        self.result = "UNSET"

    def on_mount(self) -> None:
        self.push_screen(
            LoadTestLaunchModal(default_target="http://t:9200",
                                presets=self._presets, scenarios=self._scenarios),
            lambda v: setattr(self, "result", v))


def _drive(steps, key=None, click=None, presets=None, scenarios=None):
    async def _run():
        app = _Host(presets=presets, scenarios=scenarios)
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
        m.query_one("#config", Select).value = "ingest-burst"
        m.query_one("#rate", Input).value = "100"
        m.query_one("#parallelism", Input).value = "4"
        m.query_one("#registry", Checkbox).value = True
        m.query_one("#overrides", TextArea).load_text("FOO=bar")
    f = _drive(steps, key="ctrl+s")
    assert f["scenario"] == "ingest" and f["config_name"] == "ingest-burst"
    assert f["rate"] == "100" and f["registry_enabled"] is True
    assert f["parallelism"] == "4"
    assert f["overrides_text"] == "FOO=bar"
    # Unchecked toggles are authoritative False now (not None), so they can force a preset's
    # default off rather than silently inheriting it.
    assert f["duration"] is None and f["control_enabled"] is False


def test_launch_via_button():
    f = _drive(lambda m: None, click="#launch")
    assert f["scenario"] == "ingest"


def test_toggles_default_false_for_ingest():
    # ingest-steady preset ships both toggles off -> unchecked boxes -> explicit False
    f = _drive(lambda m: None, key="ctrl+s")
    assert f["registry_enabled"] is False and f["control_enabled"] is False
    # blank config Select -> None (build_k6_parameters resolves it to <scenario>-steady)
    assert f["config_name"] is None


def test_mixed_scenario_seeds_registry_on():
    # Selecting the mixed scenario pre-checks registry (mixed-* presets set REGISTRY_ENABLED=true),
    # so leaving it alone reproduces the preset default.
    def steps(m):
        m.query_one("#scenario", Select).value = "mixed"
    f = _drive(steps, key="ctrl+s")
    assert f["scenario"] == "mixed"
    assert f["registry_enabled"] is True


def test_mixed_registry_can_be_unchecked_to_false():
    # The whole point: after mixed seeds registry on, the user can uncheck it and get False,
    # which forces REGISTRY_ENABLED=false over the preset's true. The pause between selecting
    # the scenario and unchecking mirrors real use — the auto-seed (an async Select.Changed)
    # lands first, then the user unchecks.
    async def _run():
        app = _Host()
        async with app.run_test(size=(120, 60)) as pilot:
            await pilot.pause()
            app.screen.query_one("#scenario", Select).value = "mixed"
            await pilot.pause()  # let the scenario-change re-seed registry -> True
            assert app.screen.query_one("#registry", Checkbox).value is True
            app.screen.query_one("#registry", Checkbox).value = False
            await pilot.pause()
            await pilot.press("ctrl+s")
            await pilot.pause()
        return app.result
    assert asyncio.run(_run())["registry_enabled"] is False


def test_scenario_options_come_from_passed_scenarios():
    # Scenarios discovered in-cluster are passed in; the scenario select offers exactly those.
    def steps(m):
        m.query_one("#scenario", Select).value = "other-scn"
    f = _drive(steps, key="ctrl+s", scenarios=["custom-scn", "other-scn"])
    assert f["scenario"] == "other-scn"


def test_config_options_come_from_passed_presets():
    # Presets discovered in-cluster are passed in; the config dropdown offers exactly those.
    # Setting the Select to a passed preset only works if it became an option (Textual rejects
    # values outside the option list), so a successful launch with it proves the wiring.
    def steps(m):
        m.query_one("#config", Select).value = "beta-burst"
    f = _drive(steps, key="ctrl+s", presets=["alpha-steady", "beta-burst"])
    assert f["config_name"] == "beta-burst"


def test_parallelism_defaults_to_one():
    # empty parallelism input -> 1 (build_k6_parameters coerces to int)
    assert _drive(lambda m: None, key="ctrl+s")["parallelism"] == 1


def test_cancel_returns_none():
    assert _drive(lambda m: None, key="escape") is None


def test_close_button_returns_none():
    assert _drive(lambda m: None, click="#close") is None
