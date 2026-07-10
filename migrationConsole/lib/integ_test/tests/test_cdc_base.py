import pytest

import integ_test.test_cases.cdc_base as cdc_base
from console_link.models.cluster import AuthMethod, Cluster
from integ_test.test_cases.cdc_base import PROXY_ENDPOINT, make_proxy_cluster


def test_make_proxy_cluster_signs_sigv4_requests_with_source_endpoint():
    source = Cluster({
        "endpoint": "https://search-source.us-east-1.es.amazonaws.com",
        "allow_insecure": True,
        "version": "ES_7.10",
        "sigv4": {
            "region": "us-east-1",
            "service": "es",
        },
    })

    proxy = make_proxy_cluster(source)

    assert proxy.endpoint == PROXY_ENDPOINT
    assert proxy.allow_insecure
    assert proxy.auth_type == AuthMethod.SIGV4
    assert proxy.config["sigv4_signing_endpoint"] == source.endpoint


def test_wait_for_replayer_consuming_waits_for_pod_ready_first(monkeypatch):
    ready_calls = []

    def fake_wait_for_pod_ready(namespace, label_selector, timeout_seconds, dependency_error_check=None):
        ready_calls.append((namespace, label_selector, timeout_seconds, dependency_error_check))

    class FakeCompletedProcess:
        stdout = "KafkaHeartbeat partitions=[0]\n"

    run_calls = []

    def fake_run(args, **kwargs):
        run_calls.append((args, kwargs))
        return FakeCompletedProcess()

    monkeypatch.setattr(cdc_base, "wait_for_pod_ready", fake_wait_for_pod_ready)
    monkeypatch.setattr(cdc_base, "_raise_if_cdc_dependency_error", lambda *args, **kwargs: None)
    monkeypatch.setattr(cdc_base.subprocess, "run", fake_run)

    cdc_base.wait_for_replayer_consuming(
        namespace="ma",
        timeout_seconds=1,
        pod_ready_timeout_seconds=42,
    )

    assert len(ready_calls) == 1
    assert ready_calls[0][:3] == ("ma", cdc_base.REPLAYER_LABEL_SELECTOR, 42)
    assert ready_calls[0][3] is not None
    assert run_calls


def test_wait_for_replayer_consuming_default_pod_ready_timeout_covers_dependencies(monkeypatch):
    ready_calls = []

    def fake_wait_for_pod_ready(namespace, label_selector, timeout_seconds, dependency_error_check=None):
        ready_calls.append((namespace, label_selector, timeout_seconds, dependency_error_check))

    class FakeCompletedProcess:
        stdout = "KafkaHeartbeat partitions=[0]\n"

    monkeypatch.setattr(cdc_base, "wait_for_pod_ready", fake_wait_for_pod_ready)
    monkeypatch.setattr(cdc_base, "_raise_if_cdc_dependency_error", lambda *args, **kwargs: None)
    monkeypatch.setattr(cdc_base.subprocess, "run", lambda *args, **kwargs: FakeCompletedProcess())

    cdc_base.wait_for_replayer_consuming(namespace="ma", timeout_seconds=1)

    assert len(ready_calls) == 1
    assert ready_calls[0][:3] == (
        "ma",
        cdc_base.REPLAYER_LABEL_SELECTOR,
        cdc_base.DEFAULT_REPLAYER_POD_READY_TIMEOUT_SECONDS,
    )
    assert ready_calls[0][3] is not None


def test_wait_for_replayer_consuming_passes_workflow_name_to_dependency_check(monkeypatch):
    dependency_calls = []

    def fake_wait_for_pod_ready(namespace, label_selector, timeout_seconds, dependency_error_check=None):
        dependency_error_check()

    def fake_dependency_check(namespace, workflow_names=None):
        dependency_calls.append((namespace, workflow_names))

    class FakeCompletedProcess:
        stdout = "KafkaHeartbeat partitions=[0]\n"

    monkeypatch.setattr(cdc_base, "wait_for_pod_ready", fake_wait_for_pod_ready)
    monkeypatch.setattr(cdc_base, "_raise_if_cdc_dependency_error", fake_dependency_check)
    monkeypatch.setattr(cdc_base.subprocess, "run", lambda *args, **kwargs: FakeCompletedProcess())

    cdc_base.wait_for_replayer_consuming(namespace="ma", timeout_seconds=1, workflow_name="outer-workflow")

    assert ("ma", ["outer-workflow"]) in dependency_calls


def test_cdc_dependency_check_fails_on_failed_inner_migration_workflow(monkeypatch):
    monkeypatch.setattr(cdc_base, "_get_capture_proxy_readiness_status", lambda namespace: ("Ready", "", "", ""))
    monkeypatch.setattr(cdc_base, "_get_kafka_cluster_errors", lambda namespace: [])
    monkeypatch.setattr(cdc_base, "_get_migration_resource_errors", lambda namespace: [])
    monkeypatch.setattr(
        cdc_base,
        "_get_argo_workflow_errors",
        lambda namespace, workflow_names: [
            ("migration-workflow", "Failed", "failed nodes: Create Snapshot: source1: timed out")
        ],
    )
    monkeypatch.setattr(cdc_base, "_dump_argo_workflow_diagnostics", lambda namespace: None)

    with pytest.raises(RuntimeError, match="CDC dependency workflow failed.*migration-workflow.*Create Snapshot"):
        cdc_base._raise_if_cdc_dependency_error("ma", ["outer-workflow"])
