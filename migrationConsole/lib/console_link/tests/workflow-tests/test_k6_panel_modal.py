"""Tests for the combined k6 panel modal (launch + list/stop).

Driven headless via Textual's run_test(); wrapped in asyncio.run so no pytest-asyncio is needed.
"""
import asyncio

from textual.app import App
from textual.widgets import Input, Checkbox, TextArea

from console_link.workflow.tui.k6_panel_modal import K6PanelModal

RUNS = [
    {"name": "k6-run-a", "scenario": "ingest", "phase": "Running", "age": "1m"},
    {"name": "k6-run-b", "scenario": "mixed", "phase": "Running", "age": "2m"},
]


class _Host(App):
    def __init__(self, runs):
        super().__init__()
        self._runs = runs
        self.result = "UNSET"

    def on_mount(self) -> None:
        self.push_screen(K6PanelModal(self._runs, default_target="http://t:9200"),
                         lambda v: setattr(self, "result", v))


def _drive(runs, steps, key=None, click=None):
    async def _run():
        app = _Host(runs)
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
        m.query_one("#config", Input).value = "ingest-burst"
        m.query_one("#rate", Input).value = "100"
        m.query_one("#registry", Checkbox).value = True
        m.query_one("#overrides", TextArea).load_text("FOO=bar")
    res = _drive(RUNS, steps, key="ctrl+s")
    assert res["kind"] == "launch"
    f = res["fields"]
    assert f["scenario"] == "ingest" and f["config_name"] == "ingest-burst"
    assert f["rate"] == "100" and f["registry_enabled"] is True
    assert f["overrides_text"] == "FOO=bar"
    assert f["duration"] is None and f["control_enabled"] is None


def test_stop_one():
    res = _drive(RUNS, lambda m: None, click="#stop-0")
    assert res == {"kind": "stop", "names": ["k6-run-a"], "delete": False}


def test_stop_one_with_delete():
    def steps(m):
        m.query_one("#delete", Checkbox).value = True
    res = _drive(RUNS, steps, click="#stop-1")
    assert res["kind"] == "stop" and res["names"] == ["k6-run-b"] and res["delete"] is True


def test_stop_all():
    res = _drive(RUNS, lambda m: None, click="#stopall")
    assert res["kind"] == "stop" and set(res["names"]) == {"k6-run-a", "k6-run-b"}


def test_cancel_returns_none():
    assert _drive(RUNS, lambda m: None, key="escape") is None


def test_empty_runs_still_launches():
    res = _drive([], lambda m: None, key="ctrl+s")
    assert res["kind"] == "launch" and res["fields"]["scenario"] == "ingest"
