"""Memory-tier scaling console status: new fields appear ONLY when memory_scaling is configured
(feature-off output stays byte-identical), shard_awaiting_memory splits out of shard_waiting,
workers aggregate the per-tier deployment set, and the terminal Failed rollup lists succeeded vs
incomplete indices with the shards exceeding maxMemory."""

from datetime import datetime, timezone

import pytest
from kubernetes import config

import console_link.models.backfill_rfs as backfill_rfs
from console_link.models.backfill_base import BackfillOverallStatus
from console_link.models.backfill_rfs import (
    K8sRFSBackfill,
    MemoryScalingBackfillStatus,
    _index_name_of_hit,
    _shard_number_of_hit,
    build_memory_scaling_status,
    compute_failed_rollup,
    generate_awaiting_memory_query,
    generate_runnable_incomplete_query,
)
from console_link.models.factories import get_backfill
from console_link.models.step_state import StepStateWithPause
from console_link.models.utils import DeploymentStatus
from tests.utils import create_valid_cluster


@pytest.fixture(autouse=True)
def mock_kube_config(monkeypatch):
    monkeypatch.setattr(config, "load_incluster_config", lambda: None)
    monkeypatch.setattr(config, "load_kube_config", lambda: None)


MEMORY_SCALING = {
    "tiers": ["7000Mi", "14000Mi", "28000Mi"],
    "max_total_pods": 24,
    "escalation_approval": "auto",
}


def make_backfill(with_memory_scaling: bool):
    rfs = {
        "k8s": {"namespace": "ma", "deployment_name": "sess-rfs"},
        "backfill_session_name": "sess",
    }
    if with_memory_scaling:
        rfs["memory_scaling"] = MEMORY_SCALING
    return get_backfill({"reindex_from_snapshot": rfs}, target_cluster=create_valid_cluster())


def base_status(**overrides):
    values = dict(status=StepStateWithPause.RUNNING, percentage_completed=50.0, eta_ms=1000.0,
                  started=None, finished=None, shard_total=10, shard_complete=5,
                  shard_in_progress=2, shard_waiting=3)
    values.update(overrides)
    return BackfillOverallStatus(**values)


def test_memory_scaling_config_is_accepted_and_optional():
    assert isinstance(make_backfill(True), K8sRFSBackfill)
    plain = make_backfill(False)
    assert isinstance(plain, K8sRFSBackfill)
    assert plain.memory_scaling is None


def test_invalid_memory_scaling_config_is_rejected():
    with pytest.raises(ValueError):
        get_backfill({"reindex_from_snapshot": {
            "k8s": {"namespace": "ma", "deployment_name": "d"},
            "memory_scaling": {"tiers": "not-a-list", "max_total_pods": 24},
        }}, target_cluster=create_valid_cluster())


def test_feature_off_returns_the_plain_status_model(mocker):
    """Byte-identity: feature off must not even construct the extended model, so the JSON
    emitted by `console --json backfill status --deep-check` has exactly the old keys."""
    backfill = make_backfill(False)
    mocker.patch.object(backfill.kubectl_runner, "retrieve_deployment_status",
                        return_value=DeploymentStatus(running=1, pending=0, desired=1))
    plain = base_status()
    mocker.patch.object(backfill_rfs, "get_detailed_status_obj", return_value=plain)
    result = backfill.build_backfill_status()
    assert type(result) is BackfillOverallStatus
    assert sorted(result.model_dump(mode="json").keys()) == sorted([
        "status", "percentage_completed", "eta_ms", "started", "finished",
        "shard_total", "shard_complete", "shard_in_progress", "shard_waiting"])


def test_feature_on_appends_the_new_fields(mocker):
    backfill = make_backfill(True)
    tier_statuses = {
        0: DeploymentStatus(running=4, pending=1, desired=5),
        1: DeploymentStatus(running=2, pending=0, desired=2),
        2: None,
    }
    mocker.patch.object(K8sRFSBackfill, "_retrieve_tier_deployment_statuses",
                        return_value=tier_statuses)
    mocker.patch.object(backfill_rfs, "get_detailed_status_obj", return_value=base_status())
    mocker.patch.object(backfill_rfs, "parse_query_response", return_value=1)  # awaiting_memory
    mocker.patch.object(backfill_rfs, "count_busy_pods_by_tier", return_value={0: 4, 1: 2})
    mocker.patch.object(backfill_rfs, "compute_failed_rollup", return_value=None)

    result = backfill.build_backfill_status()
    assert isinstance(result, MemoryScalingBackfillStatus)
    payload = result.model_dump(mode="json")
    assert payload["shard_awaiting_memory"] == 1
    # shard_waiting keeps its eligible-and-unclaimed meaning: awaiting shards move out of it.
    assert payload["shard_waiting"] == 2
    assert payload["shard_total"] == 10
    # total = complete + in_progress + waiting + awaiting_memory
    assert (payload["shard_complete"] + payload["shard_in_progress"]
            + payload["shard_waiting"] + payload["shard_awaiting_memory"]) == payload["shard_total"]
    assert payload["workers"] == {"desired": 7, "running": 6, "pending": 1}
    assert payload["memory_scaling"]["tiers"] == MEMORY_SCALING["tiers"]
    assert payload["memory_scaling"]["active"] == [
        {"tier": 0, "replicas": 5, "busy": 4},
        {"tier": 1, "replicas": 2, "busy": 2},
    ]
    assert payload["status"] == "Running"
    assert payload["indices"] is None


def test_terminal_failed_rollup_sets_status_and_message(mocker):
    backfill = make_backfill(True)
    mocker.patch.object(K8sRFSBackfill, "_retrieve_tier_deployment_statuses",
                        return_value={0: DeploymentStatus(running=0, pending=0, desired=0)})
    mocker.patch.object(backfill_rfs, "get_detailed_status_obj",
                        return_value=base_status(status=StepStateWithPause.PAUSED,
                                                 shard_complete=8, shard_in_progress=0,
                                                 shard_waiting=2))
    mocker.patch.object(backfill_rfs, "parse_query_response", return_value=2)
    mocker.patch.object(backfill_rfs, "count_busy_pods_by_tier", return_value={})
    rollup = {
        "succeeded": ["logs-2024-01", "orders"],
        "incomplete": [{"index": "logs-2024-02", "shards_complete": 14, "shards_total": 16,
                        "shards_exceeding_max_memory": [3, 7]}],
    }
    mocker.patch.object(backfill_rfs, "compute_failed_rollup", return_value=rollup)

    result = backfill.build_backfill_status()
    payload = result.model_dump(mode="json")
    assert payload["status"] == "Failed"
    assert payload["indices"] == rollup
    assert payload["message"] == "2 shards still exceed maxMemory (28000Mi) at tier 2"


def test_awaiting_memory_query_uses_tier_range_only_when_a_fleet_is_active():
    with_fleet = generate_awaiting_memory_query(max_active_tier=1)
    must = with_fleet["query"]["bool"]["must"]
    assert {"range": {"requiredMemoryTier": {"gt": 1}}} in must

    no_fleet = generate_awaiting_memory_query(max_active_tier=-1)
    must = no_fleet["query"]["bool"]["must"]
    assert all("requiredMemoryTier" not in list(clause.get("range", {})) for clause in must)


def test_runnable_incomplete_query_treats_missing_tier_as_tier_zero():
    query = generate_runnable_incomplete_query(max_tier=2)["query"]["bool"]
    assert {"range": {"requiredMemoryTier": {"lte": 2}}} in query["should"]
    assert {"bool": {"must_not": [{"exists": {"field": "requiredMemoryTier"}}]}} in query["should"]
    assert query["minimum_should_match"] == 1


def test_index_name_decodes_base64url_ids_when_plaintext_is_absent():
    assert _index_name_of_hit({"_id": "x__0__0", "_source": {"indexName": "plain"}}) == "plain"
    # "logs-2024-02" encoded base64url without padding
    import base64
    encoded = base64.urlsafe_b64encode(b"logs-2024-02").decode().rstrip("=")
    assert _index_name_of_hit({"_id": f"{encoded}__3__0", "_source": {}}) == "logs-2024-02"
    assert _shard_number_of_hit({"_id": f"{encoded}__3__0"}) == 3
    assert _shard_number_of_hit({"_id": "shard_setup"}) is None


def test_compute_failed_rollup_classifies_terminal_state(mocker):
    cluster = create_valid_cluster()
    now = int(datetime.now(timezone.utc).timestamp())
    import base64
    heavy = base64.urlsafe_b64encode(b"logs-2024-02").decode().rstrip("=")

    # Not terminal while anything is runnable at a configured tier.
    mocker.patch.object(backfill_rfs, "parse_query_response", return_value=1)
    assert compute_failed_rollup(cluster, ".migrations_working_state", 2) is None

    # Not terminal while a live lease exists (in progress).
    mocker.patch.object(backfill_rfs, "parse_query_response", return_value=0)
    mocker.patch.object(backfill_rfs, "_fetch_hits", return_value=[
        {"_id": f"{heavy}__3__0", "_source": {"requiredMemoryTier": 3, "expiration": now + 500}},
    ])
    assert compute_failed_rollup(cluster, ".migrations_working_state", 2) is None

    # Terminal: all incomplete items exceed the ladder and nothing is in progress.
    mocker.patch.object(backfill_rfs, "_fetch_hits", return_value=[
        {"_id": f"{heavy}__3__0", "_source": {"requiredMemoryTier": 3, "expiration": 0}},
        {"_id": f"{heavy}__7__100", "_source": {"indexName": "logs-2024-02",
                                                "requiredMemoryTier": 4, "expiration": 0}},
    ])
    mocker.patch.object(backfill_rfs, "_completed_shard_counts",
                        return_value={"logs-2024-01": 4, "orders": 2, "logs-2024-02": 14})
    rollup = compute_failed_rollup(cluster, ".migrations_working_state", 2)
    assert rollup == {
        "succeeded": ["logs-2024-01", "orders"],
        "incomplete": [{
            "index": "logs-2024-02",
            "shards_complete": 14,
            "shards_total": 16,
            "shards_exceeding_max_memory": [3, 7],
        }],
    }


def test_query_failure_never_declares_failed(mocker):
    cluster = create_valid_cluster()
    mocker.patch.object(backfill_rfs, "parse_query_response", return_value=None)
    assert compute_failed_rollup(cluster, ".migrations_working_state", 2) is None


def test_build_memory_scaling_status_with_no_fleets_counts_all_unclaimed_as_awaiting(mocker):
    mocker.patch.object(backfill_rfs, "parse_query_response", return_value=3)
    mocker.patch.object(backfill_rfs, "count_busy_pods_by_tier", return_value={})
    mocker.patch.object(backfill_rfs, "compute_failed_rollup", return_value=None)
    result = build_memory_scaling_status(
        base=base_status(shard_waiting=3, shard_in_progress=2),
        target_cluster=create_valid_cluster(),
        session_name="sess",
        memory_scaling_config=MEMORY_SCALING,
        tier_statuses={0: None, 1: None, 2: None})
    assert result.shard_awaiting_memory == 3
    assert result.shard_waiting == 0
    assert result.workers == {"desired": 0, "running": 0, "pending": 0}
    assert result.memory_scaling["active"] == []
