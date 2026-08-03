"""Tests for the combined k6 panel modal (launch + list/stop).

Driven headless via Textual's run_test(); wrapped in asyncio.run so no pytest-asyncio is needed.
"""
import asyncio

from textual.app import App
from textual.widgets import Input, Checkbox, TextArea, Select

from console_link.workflow.tui.k6_panel_modal import K6PanelModal

RUNS = [
    {"name": "k6-run-a", "scenario": "ingest", "phase": "Running", "age": "1m"},
    {"name": "k6-run-b", "scenario": "mixed", "phase": "Running", "age": "2m"},
]


class _Host(App):
    def __init__(self, runs, presets=None, scenarios=None):
        super().__init__()
        self._runs = runs
        self._presets = presets
        self._scenarios = scenarios
        self.result = "UNSET"

    def on_mount(self) -> None:
        self.push_screen(
            K6PanelModal(self._runs, default_target="http://t:9200",
                         presets=self._presets, scenarios=self._scenarios),
            lambda v: setattr(self, "result", v))


def _drive(runs, steps, key=None, click=None, presets=None, scenarios=None):
    async def _run():
        app = _Host(runs, presets=presets, scenarios=scenarios)
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
    res = _drive(RUNS, steps, key="ctrl+s")
    assert res["kind"] == "launch"
    f = res["fields"]
    assert f["scenario"] == "ingest" and f["config_name"] == "ingest-burst"
    assert f["rate"] == "100" and f["registry_enabled"] is True
    assert f["parallelism"] == "4"
    assert f["overrides_text"] == "FOO=bar"
    # Unchecked toggles are authoritative False now (not None), so they can force a preset's
    # default off rather than silently inheriting it.
    assert f["duration"] is None and f["control_enabled"] is False


def test_toggles_default_false_for_ingest():
    # ingest-steady preset ships both toggles off -> unchecked boxes -> explicit False
    res = _drive(RUNS, lambda m: None, key="ctrl+s")
    f = res["fields"]
    assert f["registry_enabled"] is False and f["control_enabled"] is False
    # blank config Select -> None (build_k6_parameters resolves it to <scenario>-steady)
    assert f["config_name"] is None


def test_mixed_scenario_seeds_registry_on():
    # Selecting the mixed scenario pre-checks registry (mixed-* presets set REGISTRY_ENABLED=true),
    # so leaving it alone reproduces the preset default.
    def steps(m):
        m.query_one("#scenario", Select).value = "mixed"
    res = _drive(RUNS, steps, key="ctrl+s")
    assert res["fields"]["scenario"] == "mixed"
    assert res["fields"]["registry_enabled"] is True


def test_mixed_registry_can_be_unchecked_to_false():
    # The whole point: after mixed seeds registry on, the user can uncheck it and get False,
    # which forces REGISTRY_ENABLED=false over the preset's true. The pause between selecting
    # the scenario and unchecking mirrors real use — the auto-seed (an async Select.Changed)
    # lands first, then the user unchecks.
    async def _run():
        app = _Host(RUNS)
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
    res = asyncio.run(_run())
    assert res["fields"]["registry_enabled"] is False


def test_scenario_options_come_from_passed_scenarios():
    # Scenarios discovered in-cluster are passed in; the scenario select offers exactly those.
    def steps(m):
        m.query_one("#scenario", Select).value = "other-scn"
    res = _drive(RUNS, steps, key="ctrl+s", scenarios=["custom-scn", "other-scn"])
    assert res["fields"]["scenario"] == "other-scn"


def test_config_options_come_from_passed_presets():
    # Presets discovered in-cluster are passed in; the config dropdown offers exactly those.
    # Setting the Select to a passed preset only works if it became an option (Textual rejects
    # values outside the option list), so a successful launch with it proves the wiring.
    def steps(m):
        m.query_one("#config", Select).value = "beta-burst"
    res = _drive(RUNS, steps, key="ctrl+s", presets=["alpha-steady", "beta-burst"])
    assert res["fields"]["config_name"] == "beta-burst"


def test_parallelism_defaults_to_one():
    # empty parallelism input -> 1 (build_k6_parameters coerces to int)
    res = _drive(RUNS, lambda m: None, key="ctrl+s")
    assert res["fields"]["parallelism"] == 1


def test_stop_one():
    # stopping deletes the TestRun; no separate delete flag
    res = _drive(RUNS, lambda m: None, click="#stop-0")
    assert res == {"kind": "stop", "names": ["k6-run-a"]}


def test_stop_all():
    res = _drive(RUNS, lambda m: None, click="#stopall")
    assert res["kind"] == "stop" and set(res["names"]) == {"k6-run-a", "k6-run-b"}


def test_cancel_returns_none():
    assert _drive(RUNS, lambda m: None, key="escape") is None


def test_empty_runs_still_launches():
    res = _drive([], lambda m: None, key="ctrl+s")
    assert res["kind"] == "launch" and res["fields"]["scenario"] == "ingest"
