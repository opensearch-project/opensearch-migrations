import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
import yaml
from kubernetes.client.rest import ApiException

from console_link.models.cluster import Cluster
from integ_test.test_cases import k6_load_test_tests as k6_test


_REPO = Path(__file__).resolve().parents[4]


def test_k6_test_submits_one_suite_and_checks_three_verdicts(monkeypatch):
    from console_link.loadtest import health, runs, utils
    from console_link.loadtest import testrun_utils

    create = Mock(return_value="k6-suite-abc")
    wait = Mock(return_value=runs.SUCCESS_PHASE)
    watcher = Mock(return_value=SimpleNamespace(
        verdict=lambda: {
            "known": True,
            "failed_runners": [],
            "thresholds_crossed": False,
        },
    ))
    monkeypatch.setattr(utils, "load_k8s_config", lambda: None)
    monkeypatch.setattr(testrun_utils, "create_workflow", create)
    monkeypatch.setattr(runs, "wait_for_run", wait)
    monkeypatch.setattr(health, "HealthWatcher", watcher)

    case = object.__new__(k6_test.Test0080CdcK6LoadTest)
    case.source_cluster = Cluster({"endpoint": "http://source:9200", "no_auth": None})
    case._index_name = "nyc_taxis"

    case._run_k6(
        "ma", "https://capture-proxy:9201", k6_test.Test0080CdcK6LoadTest.K6_LOAD
    )

    create.assert_called_once()
    wait.assert_called_once()
    body = create.call_args.args[1]
    assert body["spec"]["workflowTemplateRef"]["name"] == "k6-ingest-connection-modes"
    assert body["metadata"]["labels"] == {
        "app": "k6-load-test",
        "k6-scenario": "ingest",
    }
    parameters = {
        item["name"]: item["value"] for item in body["spec"]["arguments"]["parameters"]
    }
    assert parameters["INDEX_NAME"] == "nyc_taxis"
    assert "RETRY_ENABLED" not in parameters
    suite_runs = json.loads(parameters["runs"])
    assert suite_runs[2]["connectionMode"] == "spread"
    assert suite_runs[2]["noConnectionReuse"] == "true"
    assert [args.args[1] for args in watcher.call_args_list] == [
        "k6-suite-abc-pinned",
        "k6-suite-abc-spread",
        "k6-suite-abc-no-reuse",
    ]


def test_k6_scale_test_rejects_an_unknown_tester_verdict(monkeypatch):
    from console_link.loadtest import health, runs, utils
    from console_link.loadtest import testrun_utils

    monkeypatch.setattr(utils, "load_k8s_config", lambda: None)
    monkeypatch.setattr(testrun_utils, "create_workflow", lambda *_: "k6-suite-abc")
    monkeypatch.setattr(runs, "wait_for_run", lambda *_args, **_kwargs: runs.SUCCESS_PHASE)
    monkeypatch.setattr(health, "HealthWatcher", lambda *_: SimpleNamespace(
        verdict=lambda: {
            "known": False,
            "failed_runners": [],
            "thresholds_crossed": False,
        },
    ))
    case = SimpleNamespace(
        source_cluster=Cluster({"endpoint": "http://source:9200", "no_auth": None}),
        _index_name="nyc_taxis",
        K6_DURATION="20s",
        K6_RUN_TIMEOUT_SECONDS=300,
        K6_PARAMETERS={},
    )

    with pytest.raises(AssertionError, match="failed its request checks"):
        k6_test.Test0080CdcK6LoadTest._run_k6(
            case, "ma", "https://capture-proxy:9201", k6_test.Test0080CdcK6LoadTest.K6_LOAD
        )


def _named_values(node, name):
    if isinstance(node, dict):
        if node.get("name") == name and "value" in node:
            yield node["value"]
        for value in node.values():
            yield from _named_values(value, name)
    elif isinstance(node, list):
        for value in node:
            yield from _named_values(value, name)


@pytest.mark.parametrize(("case", "brokers"), (
    (k6_test.Test0081CdcK6StressTest, "3"),
    (k6_test.Test0082CdcK6HighLoadStressTest, "12"),
))
def test_stress_tests_churn_kafka_replayer_and_proxy_pods(case, brokers):
    assert case.K6_PARAMETERS["podChurnEnabled"] == "true"
    assert case.K6_PARAMETERS["podChurnTargets"] == "kafka,replayer,proxy"
    assert case.K6_PARAMETERS["podChurnExpectedKafkaBrokers"] == brokers


def test_high_load_stress_uses_the_160k_two_hour_rig():
    case = k6_test.Test0082CdcK6HighLoadStressTest

    assert case.K6_DURATION == "2h"
    assert case.K6_RUN_TIMEOUT_SECONDS == 14_400
    assert case.K6_PARAMETERS["BULK_BATCH_SIZE"] == "1"
    assert case.K6_PARAMETERS["DOCUMENT_PADDING_BYTES"] == "1300"
    assert case.K6_PARAMETERS["INDEX_NUMBER_OF_SHARDS"] == "24"
    assert case.K6_PARAMETERS["SEQUENCE_FRACTION"] == "0"
    assert sum(int(rate) for rate, *_ in case.K6_LOAD) == 160_000
    assert sum(parallelism for _, parallelism, *_ in case.K6_LOAD) == 196
    assert max(parallelism for _, parallelism, *_ in case.K6_LOAD) <= 100
    assert case.K6_WORKFLOW_PARAMETERS == {
        "capture-proxy-pod-replicas": "128",
        "kafka-broker-replicas": "12",
        "traffic-topic-partitions": "480",
        "replayer-pod-replicas": "320",
        "replayer-config-overrides": (
            '{"maxConcurrentRequests":64,"numClientThreads":4}'
        ),
    }


def test_k6_chart_defaults_reserve_capacity_for_every_runner():
    chart = _REPO / "deployment/k8s/charts/components/k6LoadTest"
    values = yaml.safe_load((chart / "values.yaml").read_text())

    assert values["testRun"]["runner"]["resources"] == {
        "requests": {"cpu": "500m", "memory": "1Gi"},
        "limits": {"cpu": "2", "memory": "2Gi"},
    }
    workflow_template = (chart / "templates/k6-workflowtemplates.yaml").read_text()
    assert "toYaml $.Values.testRun.runner.resources | nindent 16" in workflow_template


def test_k6_chart_spreads_each_runner_fleet_across_availability_zones():
    chart = _REPO / "deployment/k8s/charts/components/k6LoadTest"
    values = yaml.safe_load((chart / "values.yaml").read_text())

    assert values["testRun"]["runner"]["zoneSpread"] == {
        "enabled": True,
        "maxSkew": 1,
        "topologyKey": "topology.kubernetes.io/zone",
        "whenUnsatisfiable": "DoNotSchedule",
    }
    workflow_template = (chart / "templates/k6-workflowtemplates.yaml").read_text()
    assert 'k6_cr: "{{`{{workflow.name}}`}}"' in workflow_template
    assert 'runner: "true"' in workflow_template


def test_small_k6_rigs_use_one_replayer_client_thread():
    for case in (
        k6_test.Test0080CdcK6LoadTest,
        k6_test.Test0081CdcK6StressTest,
    ):
        assert case.K6_WORKFLOW_PARAMETERS["replayer-config-overrides"] == (
            '{"numClientThreads":1}'
        )


def test_all_k6_load_tests_disable_snapshot_and_backfill():
    for case_type in (
        k6_test.Test0080CdcK6LoadTest,
        k6_test.Test0081CdcK6StressTest,
        k6_test.Test0082CdcK6HighLoadStressTest,
    ):
        case = object.__new__(case_type)
        case.prepare_workflow_snapshot_and_migration_config()
        assert case.workflow_snapshot_and_migration_config == []


@pytest.mark.parametrize("workflow_name", (
    "cdcE2eMigrationWithClusters.yaml",
    "cdcOnlyImportedClusters.yaml",
))
def test_k6_topology_parameters_reach_the_existing_cdc_overlay(workflow_name):
    workflow_path = (
        _REPO / "migrationConsole/lib/integ_test/testWorkflows" / workflow_name
    )
    workflow_text = workflow_path.read_text()
    workflow = yaml.safe_load(workflow_text)
    defaults = {
        parameter["name"]: parameter["value"]
        for parameter in workflow["spec"]["arguments"]["parameters"]
    }
    assert defaults["capture-proxy-pod-replicas"] == "1"
    assert defaults["traffic-topic-partitions"] == "2"
    assert defaults["replayer-pod-replicas"] == "1"

    for workflow_parameter, overlay_parameter in (
        ("capture-proxy-pod-replicas", "captureProxyPodReplicas"),
        ("kafka-broker-replicas", "kafkaBrokerReplicas"),
        ("traffic-topic-partitions", "trafficTopicPartitions"),
        ("replayer-pod-replicas", "replayerPodReplicas"),
    ):
        expected_binding = f"{{{{workflow.parameters.{workflow_parameter}}}}}"
        if workflow_name == "cdcOnlyImportedClusters.yaml":
            assert expected_binding in workflow_text
        else:
            assert expected_binding in set(_named_values(workflow, overlay_parameter))

    overlay = yaml.safe_load((
        _REPO / "migrationConsole/lib/integ_test/testWorkflows/cdcOverlay.yaml"
    ).read_text())
    traffic_template = next(
        template for template in overlay["spec"]["templates"]
        if template["name"] == "add-traffic-config"
    )
    overlay_defaults = {
        parameter["name"]: parameter.get("default")
        for parameter in traffic_template["inputs"]["parameters"]
    }
    assert overlay_defaults["captureProxyPodReplicas"] == "1"
    assert overlay_defaults["trafficTopicPartitions"] == "2"
    assert overlay_defaults["replayerPodReplicas"] == "1"


def test_k6_cdc_only_workflows_do_not_require_snapshot_migrations():
    imported = (
        _REPO / "migrationConsole/lib/integ_test/testWorkflows/cdcOnlyImportedClusters.yaml"
    ).read_text()
    overlay = (
        _REPO / "migrationConsole/lib/integ_test/testWorkflows/cdcOverlay.yaml"
    ).read_text()

    assert '"snapshotMigrationConfigs": []' in imported
    assert "dependsOnSnapshotMigrations" not in imported
    assert "if ((.snapshotMigrationConfigs // []) | length) > 0" in overlay


def test_k6_stress_topology_is_an_explicit_override():
    assert k6_test.Test0080CdcK6LoadTest.K6_WORKFLOW_PARAMETERS == {
        "replayer-config-overrides": '{"numClientThreads":1}',
    }
    topology = k6_test.Test0081CdcK6StressTest.K6_WORKFLOW_PARAMETERS
    assert topology == {
        "replayer-config-overrides": '{"numClientThreads":1}',
        "capture-proxy-pod-replicas": "4",
        "kafka-broker-replicas": "3",
        "traffic-topic-partitions": "8",
        "replayer-pod-replicas": "4",
    }
    assert int(topology["traffic-topic-partitions"]) >= (
        int(topology["capture-proxy-pod-replicas"]) + 1
    )


class _Session:
    region_name = "us-west-2"

    def __init__(self, credentials):
        self._credentials = credentials

    def get_credentials(self):
        return self._credentials


def _credentials(token="session-token"):
    frozen = SimpleNamespace(access_key="A" * 20, secret_key="secret", token=token)
    return SimpleNamespace(get_frozen_credentials=lambda: frozen)


def test_k6_auth_secret_data_supports_basic_auth():
    cluster = Cluster({
        "endpoint": "https://source:9200",
        "basic_auth": {"username": "admin", "password": "password"},
    })

    assert k6_test._k6_auth_secret_data(cluster) == {
        "K6_AUTH_MODE": "basic",
        "K6_AUTH_USERNAME": "admin",
        "K6_AUTH_PASSWORD": "password",
    }


def test_k6_auth_secret_data_supports_sigv4_through_proxy():
    cluster = Cluster({
        "endpoint": "https://search-source.us-east-1.es.amazonaws.com",
        "sigv4": {"region": "us-east-1", "service": "es"},
    })

    data = k6_test._k6_auth_secret_data(cluster, session=_Session(_credentials()))

    assert data == {
        "K6_AUTH_MODE": "sigv4",
        "AWS_ACCESS_KEY_ID": "A" * 20,
        "AWS_SECRET_ACCESS_KEY": "secret",
        "AWS_SESSION_TOKEN": "session-token",
        "AWS_REGION": "us-east-1",
        "SIGV4_SERVICE": "es",
        "SIGV4_SIGNING_ENDPOINT": cluster.endpoint,
    }


def test_k6_auth_secret_data_uses_session_region_and_omits_empty_token():
    cluster = Cluster({"endpoint": "https://source", "sigv4": None})

    data = k6_test._k6_auth_secret_data(
        cluster, session=_Session(_credentials(token=None)))

    assert data["AWS_REGION"] == "us-west-2"
    assert data["SIGV4_SERVICE"] == "es"
    assert "AWS_SESSION_TOKEN" not in data


def test_k6_auth_secret_data_requires_aws_credentials():
    cluster = Cluster({
        "endpoint": "https://source",
        "sigv4": {"region": "us-east-1"},
    })

    with pytest.raises(RuntimeError, match="requires AWS credentials"):
        k6_test._k6_auth_secret_data(cluster, session=_Session(None))


def test_k6_auth_secret_data_allows_no_auth():
    cluster = Cluster({"endpoint": "http://source:9200", "no_auth": None})

    assert k6_test._k6_auth_secret_data(cluster) == {}


def test_create_k6_auth_secret_marks_it_for_test_cleanup(monkeypatch):
    core = Mock()
    monkeypatch.setattr(k6_test.client, "CoreV1Api", lambda: core)

    k6_test._create_k6_auth_secret("ma", "k6-auth-123", {"K6_AUTH_MODE": "basic"})

    secret = core.create_namespaced_secret.call_args.kwargs["body"]
    assert core.create_namespaced_secret.call_args.kwargs["namespace"] == "ma"
    assert secret.metadata.name == "k6-auth-123"
    assert secret.metadata.labels == {"migration-test": "true"}
    assert secret.string_data == {"K6_AUTH_MODE": "basic"}


def test_delete_k6_auth_secret_ignores_not_found(monkeypatch):
    core = Mock()
    core.delete_namespaced_secret.side_effect = ApiException(status=404)
    monkeypatch.setattr(k6_test.client, "CoreV1Api", lambda: core)

    k6_test._delete_k6_auth_secret("ma", "gone")
