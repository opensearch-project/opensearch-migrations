"""Tests for detection of workflows blocked on an unapproved approval gate.

The signal under test is what `console_link`'s approve command extracts from the
Argo node graph — a Running approval node, plus (for a runtime gate) a Failed
tryApply sibling carrying the denial — so these tests stub `waiting_gates` and
exercise the throttling, confirmation, and message-formatting logic on top of it.
"""

import pytest
from unittest.mock import patch

from integ_test.parked_gate_detection import (
    INNER_WORKFLOW_NAME,
    ParkedApprovalGateError,
    ParkedGate,
    ParkedGateWatcher,
    find_parked_gates,
    format_parked_gate_failure,
    summarize_parked_gates,
)

NAMESPACE = "test-namespace"
RETRY_GATE = "datasnapshot.source1-testsnapshot.vapretry"
RETRY_REASON = "Impossible field change on datasnapshot: spec.snapshotName"
CHANGE_GATE = "captureproxy.capture-proxy.vapretry"
CHANGE_REASON = "Gated field change on captureproxy: spec.replicas"
STEP_GATE = "documentbackfill.source1-target1-testsnapshot-migration-0"


class FakeClock:
    """Monotonic clock a test can advance by hand."""

    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


def _patch_waiting(gates):
    return patch("integ_test.parked_gate_detection.waiting_gates", return_value=gates)


def _patch_waiting_side_effect(side_effect):
    return patch("integ_test.parked_gate_detection.waiting_gates", side_effect=side_effect)


@pytest.fixture(autouse=True)
def no_k8s_config():
    """find_parked_gates loads kube config; no cluster exists in unit tests."""
    with patch("integ_test.parked_gate_detection.load_k8s_config"):
        yield


# --- find_parked_gates ---

def test_find_parked_gates_returns_waiting_runtime_gates():
    with _patch_waiting([(RETRY_GATE, RETRY_REASON, "retry")]):
        parked = find_parked_gates(NAMESPACE, INNER_WORKFLOW_NAME)

    assert len(parked) == 1
    assert parked[0].name == RETRY_GATE
    assert parked[0].reason == RETRY_REASON
    assert parked[0].kind == "retry"


def test_find_parked_gates_includes_step_gates():
    """A step gate hangs the wait exactly like a runtime one, so it counts too."""
    with _patch_waiting([(STEP_GATE, None, "step")]):
        parked = find_parked_gates(NAMESPACE, INNER_WORKFLOW_NAME)

    assert [(gate.name, gate.kind) for gate in parked] == [(STEP_GATE, "step")]


def test_find_parked_gates_empty_when_nothing_waiting():
    with _patch_waiting([]):
        assert find_parked_gates(NAMESPACE, INNER_WORKFLOW_NAME) == []


def test_find_parked_gates_excludes_expected_gates():
    with _patch_waiting([(RETRY_GATE, RETRY_REASON, "retry"), (CHANGE_GATE, CHANGE_REASON, "change")]):
        parked = find_parked_gates(NAMESPACE, INNER_WORKFLOW_NAME, expected_gate_names=[RETRY_GATE])

    assert [gate.name for gate in parked] == [CHANGE_GATE]


def test_find_parked_gates_excludes_expected_step_gates():
    """Test0003 approves its own step gates, so only unlisted ones are failures."""
    with _patch_waiting([(STEP_GATE, None, "step"), (RETRY_GATE, RETRY_REASON, "retry")]):
        parked = find_parked_gates(NAMESPACE, INNER_WORKFLOW_NAME, expected_gate_names=[STEP_GATE])

    assert [gate.name for gate in parked] == [RETRY_GATE]


def test_find_parked_gates_swallows_errors():
    """Detection must never break the wait loop it is called from."""
    with _patch_waiting_side_effect(RuntimeError("workflow not found")):
        assert find_parked_gates(NAMESPACE, INNER_WORKFLOW_NAME) == []


def test_find_parked_gates_swallows_kube_config_errors():
    with patch("integ_test.parked_gate_detection.load_k8s_config", side_effect=Exception("no kubeconfig")):
        assert find_parked_gates(NAMESPACE, INNER_WORKFLOW_NAME) == []


# --- ParkedGateWatcher ---

def test_watcher_raises_after_required_consecutive_observations():
    clock = FakeClock()
    watcher = ParkedGateWatcher(NAMESPACE, [INNER_WORKFLOW_NAME], check_interval_seconds=30,
                                required_consecutive_observations=2, clock=clock)

    with _patch_waiting([(RETRY_GATE, RETRY_REASON, "retry")]):
        # First observation only warns - a single sighting could be a tryApply
        # that is about to be retried.
        watcher.check()
        clock.advance(30)
        with pytest.raises(ParkedApprovalGateError) as exc_info:
            watcher.check()

    assert RETRY_GATE in str(exc_info.value)
    assert RETRY_REASON in str(exc_info.value)


def test_watcher_is_throttled_between_checks():
    clock = FakeClock()
    watcher = ParkedGateWatcher(NAMESPACE, [INNER_WORKFLOW_NAME], check_interval_seconds=30,
                                required_consecutive_observations=2, clock=clock)

    with _patch_waiting([(RETRY_GATE, RETRY_REASON, "retry")]) as mock_waiting:
        watcher.check()
        # Calls inside the interval are no-ops, so a loop polling every 5s can't
        # confirm a park in 5s or hammer the API.
        for _ in range(5):
            clock.advance(5)
            watcher.check()
        assert mock_waiting.call_count == 1

        clock.advance(5)
        with pytest.raises(ParkedApprovalGateError):
            watcher.check()


def test_watcher_does_not_raise_for_transient_observation():
    clock = FakeClock()
    watcher = ParkedGateWatcher(NAMESPACE, [INNER_WORKFLOW_NAME], check_interval_seconds=30,
                                required_consecutive_observations=2, clock=clock)

    # Parked, then not, then parked again: the count resets in between, so no
    # accumulation across non-consecutive sightings.
    with _patch_waiting_side_effect([
        [(RETRY_GATE, RETRY_REASON, "retry")],
        [],
        [(RETRY_GATE, RETRY_REASON, "retry")],
    ]):
        for _ in range(3):
            watcher.check()
            clock.advance(30)


def test_watcher_no_raise_when_never_parked():
    clock = FakeClock()
    watcher = ParkedGateWatcher(NAMESPACE, [INNER_WORKFLOW_NAME], check_interval_seconds=30, clock=clock)

    with _patch_waiting([]):
        for _ in range(4):
            watcher.check()
            clock.advance(30)

    assert watcher.summarize_last_seen() == ""


def test_watcher_checks_both_outer_and_inner_workflow():
    clock = FakeClock()
    watcher = ParkedGateWatcher(NAMESPACE, ["outer-workflow", INNER_WORKFLOW_NAME],
                                check_interval_seconds=30, clock=clock)

    with _patch_waiting([]) as mock_waiting:
        watcher.check()

    checked = [call.args[1] for call in mock_waiting.call_args_list]
    assert checked == ["outer-workflow", INNER_WORKFLOW_NAME]


def test_watcher_deduplicates_workflow_names():
    """wait loops pass (polled, inner); for the inner workflow those are equal."""
    clock = FakeClock()
    watcher = ParkedGateWatcher(NAMESPACE, [INNER_WORKFLOW_NAME, INNER_WORKFLOW_NAME], clock=clock)
    assert watcher.workflow_names == [INNER_WORKFLOW_NAME]


def test_watcher_drops_empty_workflow_names():
    """workflow_name is Optional on some CDC helpers."""
    watcher = ParkedGateWatcher(NAMESPACE, [None, INNER_WORKFLOW_NAME, ""])
    assert watcher.workflow_names == [INNER_WORKFLOW_NAME]


def test_watcher_honors_expected_gate_names():
    clock = FakeClock()
    watcher = ParkedGateWatcher(NAMESPACE, [INNER_WORKFLOW_NAME], expected_gate_names=[RETRY_GATE],
                                check_interval_seconds=30, required_consecutive_observations=2, clock=clock)

    with _patch_waiting([(RETRY_GATE, RETRY_REASON, "retry")]):
        for _ in range(4):
            watcher.check()
            clock.advance(30)


def test_watcher_last_seen_records_unconfirmed_observation():
    """A park seen once but not yet confirmed still enriches a later timeout."""
    clock = FakeClock()
    watcher = ParkedGateWatcher(NAMESPACE, [INNER_WORKFLOW_NAME], check_interval_seconds=30,
                                required_consecutive_observations=2, clock=clock)

    with _patch_waiting([(RETRY_GATE, RETRY_REASON, "retry")]):
        watcher.check()

    assert RETRY_GATE in watcher.summarize_last_seen()
    assert RETRY_REASON in watcher.summarize_last_seen()


# --- message formatting ---

def test_failure_message_includes_reset_guidance_for_retry_gate():
    message = format_parked_gate_failure({INNER_WORKFLOW_NAME: [ParkedGate(RETRY_GATE, RETRY_REASON, "retry")]})

    assert INNER_WORKFLOW_NAME in message
    assert RETRY_GATE in message
    assert "workflow reset" in message
    assert "workflow approve retry" in message


def test_failure_message_includes_approve_guidance_for_change_gate():
    message = format_parked_gate_failure({INNER_WORKFLOW_NAME: [ParkedGate(CHANGE_GATE, CHANGE_REASON, "change")]})

    assert "workflow approve change" in message
    assert "workflow reset" not in message


def test_failure_message_includes_step_guidance_for_step_gate():
    message = format_parked_gate_failure({INNER_WORKFLOW_NAME: [ParkedGate(STEP_GATE, None, "step")]})

    assert STEP_GATE in message
    assert "workflow approve step" in message
    assert "skipApprovals=true" in message
    # A step gate has no failed apply behind it, so don't invent a denial reason.
    assert "no denial reason" not in message


def test_failure_message_handles_missing_denial_reason():
    message = format_parked_gate_failure({INNER_WORKFLOW_NAME: [ParkedGate(RETRY_GATE, None, "retry")]})

    assert RETRY_GATE in message
    assert "no denial reason" in message


def test_failure_message_mentions_opt_in_for_intentional_parks():
    message = format_parked_gate_failure({INNER_WORKFLOW_NAME: [ParkedGate(RETRY_GATE, RETRY_REASON, "retry")]})

    assert "expected_parked_gate_names" in message


def test_summarize_parked_gates_skips_workflows_with_no_gates():
    summary = summarize_parked_gates({
        "outer-workflow": [],
        INNER_WORKFLOW_NAME: [ParkedGate(RETRY_GATE, RETRY_REASON, "retry")],
    })

    assert "outer-workflow" not in summary
    assert INNER_WORKFLOW_NAME in summary
