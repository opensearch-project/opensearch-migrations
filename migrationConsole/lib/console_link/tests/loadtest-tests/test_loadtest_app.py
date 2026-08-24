"""Tests for the `loadtest` TUI (run table + launch/stop/logs).

Driven headless via Textual's run_test(). The cluster calls all live in runs.py and
testrun_utils.py, so they are patched at those module paths — the app imports them at call
time.
"""
import time
from unittest.mock import MagicMock, patch

import pytest

from console_link.loadtest.tui.app import LoadTestApp
from console_link.loadtest.tui.launch_modal import LoadTestLaunchModal

LT = "console_link.loadtest.runs"
TESTRUN_UTILS = "console_link.loadtest.testrun_utils"

RUNS = [
    {"name": "k6-run-a", "scenario": "ingest", "phase": "started",
     "parallelism": "4", "age": "1m"},
    {"name": "k6-run-b", "scenario": "mixed", "phase": "finished",
     "parallelism": "1", "age": "12m"},
]

LAUNCH_FIELDS = {
    "scenario": "ingest", "config_name": None, "parallelism": 1, "target_url": None,
    "rate": None, "duration": None, "vus": None, "registry_enabled": False,
    "control_enabled": False, "overrides_text": None,
}


async def wait_until(pilot, predicate, timeout=5.0, interval=0.1):
    """Poll a condition within a Textual test (the run table is filled by a thread worker)."""
    end_time = time.time() + timeout
    while time.time() < end_time:
        if predicate():
            return True
        await pilot.pause(interval)
    return False


def _notifications(notify_mock):
    return [call.args[0] for call in notify_mock.call_args_list]


def _app():
    # A long refresh interval keeps the periodic timer out of the way; tests drive refreshes.
    return LoadTestApp(namespace="ma", refresh_interval=100.0)


@pytest.mark.asyncio
async def test_table_lists_runs():
    app = _app()
    with patch(f"{LT}.list_runs", return_value=RUNS):
        async with app.run_test() as pilot:
            table = app.table
            assert await wait_until(pilot, lambda: table.row_count == 2)
            assert app.selected_run_name == "k6-run-a"
            # Finished runs stay visible — the table is the run history, not just what is active.
            assert [r["name"] for r in app._runs] == ["k6-run-a", "k6-run-b"]


@pytest.mark.asyncio
async def test_empty_table_hides_run_actions():
    """With no run highlighted, the run-scoped options are not advertised and their actions are
    inert — pressing them must not build a kubectl command against an empty name."""
    app = _app()
    with patch(f"{LT}.list_runs", return_value=[]):
        async with app.run_test() as pilot:
            assert await wait_until(pilot, lambda: app.table.row_count == 0)
            assert app.selected_run_name is None
            assert app.check_action("stop_run", None) is None
            assert app.check_action("new_run", None) is True
            with patch(f"{LT}.logs_command") as logs:
                await pilot.press("l")
                await pilot.pause()
            logs.assert_not_called()


@pytest.mark.asyncio
async def test_list_failure_notifies_and_keeps_last_table():
    app = _app()
    with patch(f"{LT}.list_runs", return_value=RUNS):
        async with app.run_test() as pilot:
            assert await wait_until(pilot, lambda: app.table.row_count == 2)
            with patch.object(app, "notify") as notify, \
                    patch(f"{LT}.list_runs", side_effect=RuntimeError("boom")):
                app.action_refresh()
                assert await wait_until(pilot, lambda: notify.called)
            assert app.table.row_count == 2
            assert "Could not list k6 runs" in _notifications(notify)[0]


@pytest.mark.asyncio
async def test_n_opens_the_launch_form():
    app = _app()
    with patch(f"{LT}.list_runs", return_value=RUNS), \
            patch(f"{TESTRUN_UTILS}.list_scenarios", return_value=["ingest"]):
        async with app.run_test() as pilot:
            assert await wait_until(pilot, lambda: app.table.row_count == 2)
            await pilot.press("n")
            assert await wait_until(pilot, lambda: isinstance(app.screen, LoadTestLaunchModal))


@pytest.mark.asyncio
async def test_launch_submits_run():
    app = _app()
    with patch(f"{LT}.list_runs", return_value=RUNS), \
            patch(f"{LT}.submit_k6_run", return_value="k6-run-z") as submit:
        async with app.run_test() as pilot:
            with patch.object(app, "notify") as notify:
                app._on_launch(dict(LAUNCH_FIELDS))
                await pilot.pause()

    submit.assert_called_once()
    namespace, params = submit.call_args.args
    assert namespace == "ma"
    # The form's fields go through build_k6_parameters, so the preset default is applied here.
    assert params["scenario"] == "ingest"
    assert params["configName"] == "ingest-steady"
    assert "k6-run-z" in _notifications(notify)[0]


@pytest.mark.asyncio
async def test_launch_reports_invalid_override():
    """A malformed -e bag is rejected by build_k6_parameters before any k8s call — the TUI has to
    surface that as a notification rather than letting it escape and kill the app."""
    app = _app()
    fields = dict(LAUNCH_FIELDS, overrides_text="NOEQUALS")
    with patch(f"{LT}.list_runs", return_value=RUNS), \
            patch(f"{LT}.submit_k6_run") as submit:
        async with app.run_test() as pilot:
            with patch.object(app, "notify") as notify:
                app._on_launch(fields)
                await pilot.pause()

    submit.assert_not_called()
    assert "Invalid override" in _notifications(notify)[0]


@pytest.mark.asyncio
async def test_launch_failure_notifies_without_crashing():
    app = _app()
    with patch(f"{LT}.list_runs", return_value=RUNS), \
            patch(f"{LT}.submit_k6_run", side_effect=RuntimeError("boom")):
        async with app.run_test() as pilot:
            with patch.object(app, "notify") as notify:
                app._on_launch(dict(LAUNCH_FIELDS))
                await pilot.pause()
            assert app.is_running

    assert "k6 action failed" in _notifications(notify)[0]


@pytest.mark.asyncio
async def test_launch_ignores_cancel():
    """Closing the form dismisses with None; that must not be mistaken for a run."""
    app = _app()
    with patch(f"{LT}.list_runs", return_value=RUNS), \
            patch(f"{LT}.submit_k6_run") as submit:
        async with app.run_test() as pilot:
            app._on_launch(None)
            await pilot.pause()
    submit.assert_not_called()


@pytest.mark.asyncio
async def test_stop_counts_only_runs_actually_stopped():
    """delete_workflow reports False when the workflow could not be removed; the tally must reflect that
    rather than claiming every requested run was stopped."""
    app = _app()
    with patch(f"{LT}.list_runs", return_value=RUNS), \
            patch(f"{TESTRUN_UTILS}.delete_workflow", side_effect=[True, False]) as delete:
        async with app.run_test() as pilot:
            with patch.object(app, "notify") as notify:
                app._stop(["k6-run-a", "k6-run-b"])
                await pilot.pause()

    assert [c.args[1] for c in delete.call_args_list] == ["k6-run-a", "k6-run-b"]
    assert "Stopped 1/2" in _notifications(notify)[0]


@pytest.mark.asyncio
async def test_stop_asks_for_confirmation_first():
    app = _app()
    with patch(f"{LT}.list_runs", return_value=RUNS), \
            patch(f"{TESTRUN_UTILS}.delete_workflow", return_value=True) as delete:
        async with app.run_test() as pilot:
            assert await wait_until(pilot, lambda: app.table.row_count == 2)
            await pilot.press("s")
            await pilot.pause()
            delete.assert_not_called()
            await pilot.press("y")  # confirm
            assert await wait_until(pilot, lambda: delete.called)
    delete.assert_called_once_with("ma", "k6-run-a")


@pytest.mark.asyncio
async def test_stop_all_with_no_runs_warns():
    app = _app()
    with patch(f"{LT}.list_runs", return_value=[]), \
            patch(f"{TESTRUN_UTILS}.delete_workflow") as delete:
        async with app.run_test() as pilot:
            assert await wait_until(pilot, lambda: app.table.row_count == 0)
            with patch.object(app, "notify") as notify:
                await pilot.press("S")
                await pilot.pause()
    delete.assert_not_called()
    assert "No k6 runs to stop" in _notifications(notify)[0]


@pytest.mark.asyncio
async def test_logs_pipe_the_shared_kubectl_command_to_the_pager():
    app = _app()
    with patch(f"{LT}.list_runs", return_value=RUNS):
        async with app.run_test() as pilot:
            assert await wait_until(pilot, lambda: app.table.row_count == 2)
            with patch.object(app, "suspend", MagicMock()), \
                    patch("console_link.loadtest.tui.app.os.system") as system:
                await pilot.press("f")
                await pilot.pause()

    piped = system.call_args.args[0]
    assert piped.startswith("kubectl logs -n ma")
    assert "k6_cr=k6-run-a,runner=true" in piped
    assert piped.endswith("| less -R +F")
