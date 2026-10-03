"""Teardown must not let one failing step preempt the others — especially the dump.

A workflow parked on an approval gate nobody will approve never reaches an ending phase, so
the gate check inside wait_for_ending_phase raises during teardown's quiesce step. That step
used to run before the diagnostic dump, so a raise there skipped the dump entirely — losing the
only record of why the run failed, precisely in the case that needs it most. These tests pin
that the dump runs first, that each step is isolated, and that the error still surfaces.
"""
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest

from integ_test.ma_workflow_test import _perform_teardown, _quiesce_workflow
from integ_test.parked_gate_detection import ParkedApprovalGateError

DUMP_METHODS = (
    "print_workflow_status",
    "print_migration_resource_status",
    "print_workflow_details",
    "print_namespace_diagnostics",
    "save_namespace_diagnostics",
)


def _make_request(failed: bool):
    return SimpleNamespace(node=SimpleNamespace(rep_call=SimpleNamespace(failed=failed)))


def _make_test_case(phase: str = "Running"):
    test_case = MagicMock()
    test_case.workflow_name = "wf-under-test"
    test_case.argo_service.get_workflow_status.return_value = SimpleNamespace(value={"phase": phase})
    return test_case


def _teardown(test_case, failed=True, keep_workflows=False, skip_workflow_reset=True):
    return _perform_teardown(
        request=_make_request(failed),
        keep_workflows=keep_workflows,
        skip_workflow_reset=skip_workflow_reset,
        dump_all_workflow_output_artifacts=False,
        test_case=test_case,
    )


def _assert_dumped(test_case):
    for method in DUMP_METHODS:
        assert getattr(test_case.argo_service, method).called, f"{method} must still run"


def test_parked_gate_during_quiesce_does_not_preempt_the_dump():
    """The regression this file exists for."""
    test_case = _make_test_case()
    test_case.argo_service.wait_for_ending_phase.side_effect = ParkedApprovalGateError("gate is parked")

    with pytest.raises(ParkedApprovalGateError, match="gate is parked"):
        _teardown(test_case)

    _assert_dumped(test_case)


def test_failed_test_dumps_before_the_workflow_is_stopped():
    """Order matters beyond just not-being-preempted.

    A workflow that hasn't been stopped yet still shows the suspended node it's parked on and
    which resource's apply was denied. stop_workflow tears that down, so dumping afterwards
    would capture a workflow whose interesting state has already been dismantled.
    """
    test_case = _make_test_case()
    calls = []
    for method in DUMP_METHODS:
        getattr(test_case.argo_service, method).side_effect = \
            lambda *a, _m=method, **kw: calls.append(_m)
    test_case.argo_service.stop_workflow.side_effect = lambda *a, **kw: calls.append("stop_workflow")

    _teardown(test_case)

    assert "stop_workflow" in calls, "the still-running workflow must still be stopped"
    assert calls.index("stop_workflow") > calls.index(DUMP_METHODS[-1]), \
        f"every dump must precede stop_workflow, got {calls}"


def test_dump_is_not_repeated_when_both_the_test_and_teardown_fail():
    """Two dumps of the same namespace is just noise in an already-long CI log."""
    test_case = _make_test_case()
    test_case.argo_service.wait_for_ending_phase.side_effect = ParkedApprovalGateError("gate is parked")

    with pytest.raises(ParkedApprovalGateError):
        _teardown(test_case, failed=True)

    test_case.argo_service.save_namespace_diagnostics.assert_called_once()


def test_quiesce_failure_is_reraised_with_its_original_type():
    """A parked gate must keep surfacing as ParkedApprovalGateError, not a generic wrapper.

    It subclasses AssertionError so pytest renders it as a test failure; wrapping would lose that.
    """
    test_case = _make_test_case()
    test_case.argo_service.wait_for_ending_phase.side_effect = ParkedApprovalGateError("gate is parked")

    with pytest.raises(AssertionError):
        _teardown(test_case)


def test_quiesce_failure_still_deletes_the_workflow_and_cleans_up():
    """Otherwise one parked run leaves resources behind and fails every later test at setup."""
    test_case = _make_test_case()
    test_case.argo_service.wait_for_ending_phase.side_effect = ParkedApprovalGateError("gate is parked")

    with patch("integ_test.ma_workflow_test._run_workflow_reset") as mock_reset:
        with pytest.raises(ParkedApprovalGateError):
            _teardown(test_case, keep_workflows=False, skip_workflow_reset=False)

    test_case.argo_service.delete_workflow.assert_called_once_with(workflow_name="wf-under-test")
    mock_reset.assert_called_once()
    test_case.cleanup.assert_called_once()


def test_a_failing_dump_step_does_not_preempt_the_saved_artifacts():
    """save_namespace_diagnostics writes what Jenkins archives, so it must not be skipped."""
    test_case = _make_test_case(phase="Succeeded")
    test_case.argo_service.print_workflow_details.side_effect = RuntimeError("kubectl exploded")

    with pytest.raises(RuntimeError, match="kubectl exploded"):
        _teardown(test_case)

    test_case.argo_service.save_namespace_diagnostics.assert_called_once()


def test_multiple_teardown_failures_are_all_reported():
    test_case = _make_test_case()
    test_case.argo_service.wait_for_ending_phase.side_effect = ParkedApprovalGateError("gate is parked")
    test_case.cleanup.side_effect = RuntimeError("cleanup exploded")

    with pytest.raises(RuntimeError) as excinfo:
        _teardown(test_case)

    message = str(excinfo.value)
    assert "2 teardown steps failed" in message
    assert "gate is parked" in message
    assert "cleanup exploded" in message


def test_pytest_fail_inside_a_step_is_captured_rather_than_escaping_early():
    """_run_workflow_reset calls pytest.fail(), which raises a BaseException subclass.

    Catching only Exception would let it skip test_case.cleanup().
    """
    test_case = _make_test_case(phase="Succeeded")

    with patch("integ_test.ma_workflow_test._run_workflow_reset",
               side_effect=pytest.fail.Exception("reset exited with code 1")):
        with pytest.raises(pytest.fail.Exception, match="reset exited with code 1"):
            _teardown(test_case, failed=False, skip_workflow_reset=False)

    test_case.cleanup.assert_called_once()


def test_keyboard_interrupt_still_aborts_teardown():
    """Suppressing an interrupt would make the run unkillable during teardown."""
    test_case = _make_test_case()
    test_case.argo_service.wait_for_ending_phase.side_effect = KeyboardInterrupt

    with pytest.raises(KeyboardInterrupt):
        _teardown(test_case)


def test_clean_run_dumps_nothing_and_raises_nothing():
    """The dump is noise on success — this is what keeps the passing-case logs readable."""
    test_case = _make_test_case(phase="Succeeded")

    _teardown(test_case, failed=False)

    for method in DUMP_METHODS:
        assert not getattr(test_case.argo_service, method).called, f"{method} must not run on success"
    test_case.cleanup.assert_called_once()


def test_teardown_failure_on_a_passing_test_still_dumps():
    """A workflow that won't stop is a real defect even when the assertions passed.

    Teardown is the last moment the namespace still holds the evidence.
    """
    test_case = _make_test_case()
    test_case.argo_service.wait_for_ending_phase.side_effect = ParkedApprovalGateError("gate is parked")

    with pytest.raises(ParkedApprovalGateError):
        _teardown(test_case, failed=False)

    _assert_dumped(test_case)


def test_teardown_without_a_workflow_name_skips_workflow_steps():
    """Tests that fail before submitting have nothing to quiesce or dump."""
    test_case = _make_test_case()
    test_case.workflow_name = None

    _teardown(test_case)

    test_case.argo_service.get_workflow_status.assert_not_called()
    test_case.argo_service.delete_workflow.assert_not_called()
    test_case.cleanup.assert_called_once()


# --- _quiesce_workflow ---------------------------------------------------------------


@pytest.mark.parametrize("phase", ["Succeeded", "Failed", "Error", "Stopped", "Terminated"])
def test_quiesce_skips_workflows_that_already_ended(phase):
    test_case = _make_test_case(phase=phase)

    _quiesce_workflow(test_case)

    test_case.argo_service.stop_workflow.assert_not_called()
    test_case.argo_service.wait_for_ending_phase.assert_not_called()


@pytest.mark.parametrize("phase", ["Running", "Pending", ""])
def test_quiesce_stops_workflows_that_are_still_going(phase):
    test_case = _make_test_case(phase=phase)

    _quiesce_workflow(test_case)

    test_case.argo_service.stop_workflow.assert_called_once_with(workflow_name="wf-under-test")
    test_case.argo_service.wait_for_ending_phase.assert_called_once_with(workflow_name="wf-under-test")
