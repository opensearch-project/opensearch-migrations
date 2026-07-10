"""Unit tests for the consumer-group readiness gate used by
`log_kafka_consumer_group_state` and `assert_replay_drained` in the CDC
integ-test base.

Covers two checkpoints:
* [replay-start]: pure snapshot, no waits, no asserts.
* [replay-end]: wait for max LAG <= threshold, log snapshot, raise on
  timeout. The replay-end assertion exists because we hit cases on EKS
  where verify_clusters passed (target had the expected docs) while the
  replayer's consumer group never committed past offset 0.
"""
from subprocess import CompletedProcess
from unittest.mock import patch

import pytest

from integ_test.test_cases import cdc_base


# Realistic native describe output (the augmented TIME LAG section is appended
# by the augmenter and is not relevant to the readiness gate).
DESCRIBE_OUTPUT_FULLY_DRAINED = """\
Consumer group 'logging-group-default' has no active members.

GROUP                  TOPIC                  PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT
logging-group-default  logging-traffic-topic  0          42              42              0    -            -     -
logging-group-default  logging-traffic-topic  1          7               7               0    -            -     -
"""

DESCRIBE_OUTPUT_GROUP_MISSING = """\
Error: Consumer group 'logging-group-default' does not exist.
"""

DESCRIBE_OUTPUT_HEADERS_ONLY = """\
GROUP                  TOPIC                  PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT
"""

DESCRIBE_OUTPUT_NO_COMMITS_YET = """\
GROUP             TOPIC          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT-ID
replayer-target1  capture-proxy  0          -               42              -    consumer-1   /1.2.3.4  consumer-1
"""

DESCRIBE_OUTPUT_NEAR_DRAINED = """\
GROUP             TOPIC          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT-ID
replayer-target1  capture-proxy  0          224             225             1    consumer-1   /1.2.3.4  consumer-1
replayer-target1  capture-proxy  1          50              50              0    consumer-1   /1.2.3.4  consumer-1
"""

DESCRIBE_OUTPUT_STILL_BEHIND = """\
GROUP             TOPIC          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT-ID
replayer-target1  capture-proxy  0          216             225             9    consumer-1   /1.2.3.4  consumer-1
replayer-target1  capture-proxy  1          50              50              0    consumer-1   /1.2.3.4  consumer-1
"""


def _completed(stdout: str, returncode: int = 0) -> CompletedProcess:
    return CompletedProcess(args=["console"], returncode=returncode, stdout=stdout, stderr="")


# --- Max-lag parsing ---------------------------------------------------------

@patch.object(cdc_base.subprocess, "run")
def test_max_lag_returns_largest_lag_across_partitions(mock_run):
    mock_run.return_value = _completed(DESCRIBE_OUTPUT_STILL_BEHIND)
    assert cdc_base._consumer_group_max_lag() == 9


@patch.object(cdc_base.subprocess, "run")
def test_max_lag_returns_one_for_in_order_commit_residue(mock_run):
    # The replayer's in-order commit constraint can leave LAG=1 around even
    # after all responses landed on the target. Parser returns the actual
    # numeric max so the caller decides.
    mock_run.return_value = _completed(DESCRIBE_OUTPUT_NEAR_DRAINED)
    assert cdc_base._consumer_group_max_lag() == 1


@patch.object(cdc_base.subprocess, "run")
def test_max_lag_returns_zero_when_fully_drained(mock_run):
    mock_run.return_value = _completed(DESCRIBE_OUTPUT_FULLY_DRAINED)
    assert cdc_base._consumer_group_max_lag() == 0


@patch.object(cdc_base.subprocess, "run")
def test_max_lag_returns_none_when_lag_is_dash(mock_run):
    # An uncommitted partition (`LAG = -`) must not be silently treated as
    # caught up — return None so the caller keeps polling.
    mock_run.return_value = _completed(DESCRIBE_OUTPUT_NO_COMMITS_YET)
    assert cdc_base._consumer_group_max_lag() is None


@patch.object(cdc_base.subprocess, "run")
def test_max_lag_returns_none_when_group_does_not_exist(mock_run):
    mock_run.return_value = _completed(DESCRIBE_OUTPUT_GROUP_MISSING)
    assert cdc_base._consumer_group_max_lag() is None


@patch.object(cdc_base.subprocess, "run")
def test_max_lag_returns_none_when_only_headers_present(mock_run):
    mock_run.return_value = _completed(DESCRIBE_OUTPUT_HEADERS_ONLY)
    assert cdc_base._consumer_group_max_lag() is None


@patch.object(cdc_base.subprocess, "run")
def test_max_lag_returns_none_when_describe_command_exits_nonzero(mock_run):
    mock_run.return_value = _completed("", returncode=1)
    assert cdc_base._consumer_group_max_lag() is None


@patch.object(cdc_base.subprocess, "run")
def test_max_lag_swallows_filenotfound(mock_run):
    mock_run.side_effect = FileNotFoundError("console")
    assert cdc_base._consumer_group_max_lag() is None


@patch.object(cdc_base.subprocess, "run")
def test_max_lag_omits_group_when_unspecified(mock_run):
    # Default `group_name=None` defers resolution to the console CLI so EKS
    # deployments hit `replayer-<targetLabel>` instead of `logging-group-default`.
    mock_run.return_value = _completed(DESCRIBE_OUTPUT_NEAR_DRAINED)
    assert cdc_base._consumer_group_max_lag() == 1

    invoked_cmd = mock_run.call_args[0][0]
    assert invoked_cmd == ["console", "kafka", "describe-consumer-group"]


# --- Caught-up bounded poll --------------------------------------------------

@patch.object(cdc_base, "_consumer_group_max_lag")
def test_wait_for_caught_up_returns_true_on_first_success(mock_lag):
    mock_lag.return_value = 1
    succeeded, last = cdc_base._wait_for_consumer_group_caught_up(
        None, "label", max_allowed_lag=1, timeout_seconds=5, interval_seconds=0.01,
    )
    assert succeeded is True
    assert last == 1
    assert mock_lag.call_count == 1


@patch("time.sleep", return_value=None)
@patch.object(cdc_base, "_consumer_group_max_lag")
def test_wait_for_caught_up_returns_false_when_persistently_behind(mock_lag, _sleep):
    mock_lag.return_value = 9
    succeeded, last = cdc_base._wait_for_consumer_group_caught_up(
        None, "label", max_allowed_lag=1, timeout_seconds=0.05, interval_seconds=0.01,
    )
    assert succeeded is False
    assert last == 9
    assert mock_lag.call_count >= 1


@patch("time.sleep", return_value=None)
@patch.object(cdc_base, "_consumer_group_max_lag")
def test_wait_for_caught_up_drops_below_threshold_eventually(mock_lag, _sleep):
    # Simulate the replayer draining: 9 -> 4 -> 1.
    mock_lag.side_effect = [9, 4, 1]
    succeeded, last = cdc_base._wait_for_consumer_group_caught_up(
        None, "label", max_allowed_lag=1, timeout_seconds=5, interval_seconds=0.01,
    )
    assert succeeded is True
    assert last == 1


@patch("time.sleep", return_value=None)
@patch.object(cdc_base, "_consumer_group_max_lag")
def test_wait_for_caught_up_treats_unparsed_max_as_not_ready(mock_lag, _sleep):
    # An uncommitted partition reports LAG=-, parser returns None. The wait
    # must keep polling until at least one commit lands.
    mock_lag.side_effect = [None, None, 0]
    succeeded, last = cdc_base._wait_for_consumer_group_caught_up(
        None, "label", max_allowed_lag=1, timeout_seconds=5, interval_seconds=0.01,
    )
    assert succeeded is True
    assert last == 0


# --- log_kafka_consumer_group_state (pure snapshot) --------------------------

@patch.object(cdc_base.subprocess, "run")
def test_log_kafka_state_emits_describe_with_no_wait(mock_run):
    # [replay-start] checkpoint: no waits, no asserts, just emit the snapshot.
    mock_run.return_value = _completed(DESCRIBE_OUTPUT_NO_COMMITS_YET)
    cdc_base.log_kafka_consumer_group_state(label="replay-start")

    invoked_cmd = mock_run.call_args[0][0]
    assert invoked_cmd == ["console", "kafka", "describe-consumer-group"]


@patch.object(cdc_base.subprocess, "run")
def test_log_kafka_state_does_not_raise_on_describe_error(mock_run):
    # Infrastructure-level failures must never break a passing test.
    mock_run.side_effect = FileNotFoundError("console")
    cdc_base.log_kafka_consumer_group_state(label="replay-start")  # no raise


# --- assert_replay_drained ---------------------------------------------------

@patch.object(cdc_base, "_emit_describe_snapshot")
@patch.object(cdc_base, "_wait_for_consumer_group_caught_up", return_value=(True, 1))
def test_assert_replay_drained_returns_when_drain_succeeds(mock_wait, mock_snapshot):
    cdc_base.assert_replay_drained(label="replay-end")
    mock_wait.assert_called_once()
    mock_snapshot.assert_called_once()  # snapshot still logged on success


@patch.object(cdc_base, "_emit_describe_snapshot")
@patch.object(cdc_base, "_wait_for_consumer_group_caught_up", return_value=(False, 348))
def test_assert_replay_drained_raises_on_timeout(mock_wait, mock_snapshot):
    with pytest.raises(cdc_base.ReplayLagDrainTimeout) as excinfo:
        cdc_base.assert_replay_drained(label="replay-end")

    # Snapshot must be emitted BEFORE the raise so the lag table is in CI logs.
    mock_snapshot.assert_called_once()
    assert "348" in str(excinfo.value)
    assert "LAG<=1" in str(excinfo.value)


def test_replay_lag_drain_timeout_is_assertion_error():
    # Surfaces in CI as a test failure, not an infrastructure error.
    assert issubclass(cdc_base.ReplayLagDrainTimeout, AssertionError)


@patch.object(cdc_base, "_emit_describe_snapshot")
@patch.object(cdc_base, "_wait_for_consumer_group_caught_up", return_value=(True, 0))
def test_assert_replay_drained_passes_max_lag_through(mock_wait, _mock_snapshot):
    cdc_base.assert_replay_drained(label="replay-end", max_lag=5, timeout_seconds=42)
    kwargs = mock_wait.call_args.kwargs
    assert kwargs.get("max_allowed_lag") == 5
    assert kwargs.get("timeout_seconds") == 42
