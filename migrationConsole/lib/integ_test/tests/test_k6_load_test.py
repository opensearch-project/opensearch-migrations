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
    assert parameters["PREPARE_INDEX"] == "false"
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


def _deployment(name, desired, updated, ready, available, *, generation=1, observed=1):
    return SimpleNamespace(
        metadata=SimpleNamespace(name=name, generation=generation),
        spec=SimpleNamespace(replicas=desired),
        status=SimpleNamespace(
            observed_generation=observed,
            updated_replicas=updated,
            ready_replicas=ready,
            available_replicas=available,
        ),
    )


def test_k6_test_capacity_gate_waits_for_ninety_percent_of_each_fleet(monkeypatch):
    proxy_states = [
        _deployment("capture-proxy", 128, 128, 115, 115),
        _deployment("capture-proxy", 128, 128, 116, 116),
    ]
    replayer = _deployment("replayer", 320, 320, 288, 288)

    class FakeAppsApi:
        def read_namespaced_deployment(self, name, namespace):
            assert (name, namespace) == ("capture-proxy", "ma")
            return proxy_states.pop(0)

        def list_namespaced_deployment(self, namespace, label_selector):
            assert (namespace, label_selector) == ("ma", "migrations.opensearch.org/component=traffic-replayer")
            return SimpleNamespace(items=[replayer])

    monkeypatch.setattr(k6_test.client, "AppsV1Api", FakeAppsApi)
    monkeypatch.setattr(k6_test.time, "sleep", lambda seconds: None)

    k6_test.wait_for_k6_test_fleet_capacity("ma", timeout_seconds=60)

    assert proxy_states == []


def test_k6_test_capacity_gate_requires_the_current_deployment_generation():
    stale = _deployment(
        "capture-proxy",
        10,
        10,
        10,
        10,
        generation=2,
        observed=1,
    )

    assert not k6_test._deployment_has_test_capacity(stale)


def test_k6_workflow_waits_for_test_fleet_capacity_before_submitting_load(monkeypatch):
    order = []

    class LoadSubmitted(Exception):
        pass

    def submit_load(namespace, endpoint, load):
        order.append(("k6", namespace, endpoint, load))
        raise LoadSubmitted

    case = object.__new__(k6_test.Test0080CdcK6LoadTest)
    case.workflow_name = "outer-workflow"
    case.argo_service = SimpleNamespace(namespace="ma")
    case.imported_clusters = True
    case._index_name = "nyc_taxis"
    case._prepare_index_directly = lambda: order.append("index")
    case._run_k6 = submit_load

    monkeypatch.setattr(
        k6_test,
        "wait_for_proxy_ready",
        lambda namespace, timeout, workflow_name: (
            order.append("proxy") or "https://proxy:9201"
        ),
    )
    monkeypatch.setattr(
        k6_test,
        "wait_for_replayer_consuming",
        lambda namespace, workflow_name: order.append("replayer"),
    )
    monkeypatch.setattr(
        k6_test,
        "wait_for_k6_test_fleet_capacity",
        lambda namespace: order.append("capacity"),
    )
    monkeypatch.setattr(
        k6_test,
        "_assert_console_cluster_access",
        lambda expected_index: order.append(("console", expected_index)),
    )

    with pytest.raises(LoadSubmitted):
        case.workflow_perform_migrations(timeout_seconds=30)

    assert order == [
        "index",
        "proxy",
        "replayer",
        "capacity",
        ("console", "nyc_taxis"),
        ("k6", "ma", "https://proxy:9201", case.K6_LOAD),
    ]


def _named_values(node, name):
    if isinstance(node, dict):
        if node.get("name") == name and "value" in node:
            yield node["value"]
        for value in node.values():
            yield from _named_values(value, name)
    elif isinstance(node, list):
        for value in node:
            yield from _named_values(value, name)


def test_recovery_stress_churns_kafka_replayer_and_proxy_pods():
    case = k6_test.Test0081CdcK6StressTest

    assert case.K6_PARAMETERS["podChurnEnabled"] == "true"
    assert case.K6_PARAMETERS["podChurnTargets"] == "kafka,replayer,proxy"
    assert case.K6_PARAMETERS["podChurnExpectedKafkaBrokers"] == "3"


def test_high_load_stress_uses_the_160k_two_hour_rig():
    case = k6_test.Test0082CdcK6HighLoadStressTest

    assert case.K6_DURATION == "2h"
    assert case.K6_RUN_TIMEOUT_SECONDS == 14_400
    assert case.K6_PARAMETERS == {
        "BULK_BATCH_SIZE": "1",
        "DOCUMENT_PADDING_BYTES": "1300",
        "INDEX_NUMBER_OF_SHARDS": "48",
        "RAMP_UP_DURATION": "25m",
    }
    assert sum(int(rate) for rate, *_ in case.K6_LOAD) == 160_000
    assert sum(parallelism for _, parallelism, *_ in case.K6_LOAD) == 220
    assert max(parallelism for _, parallelism, *_ in case.K6_LOAD) <= 100
    assert case.K6_WORKFLOW_PARAMETERS == {
        "capture-proxy-pod-replicas": "128",
        "kafka-broker-replicas": "12",
        "kafka-node-pool-spec-overrides": (
            '{"resources":{"requests":{"cpu":"12","memory":"24Gi"},'
            '"limits":{"cpu":"12","memory":"24Gi"}},'
            '"storage":{"type":"persistent-claim","size":"3Ti","class":"kafka-gp3-provisioned","deleteClaim":true}}'
        ),
        "traffic-topic-partitions": "512",
        "replayer-pod-replicas": "64",
        "speedup-factor": "1.1",
        "capture-proxy-config-overrides": (
            '{"resources":{"requests":{"cpu":"5000m","memory":"5000Mi"},'
            '"limits":{"cpu":"5000m","memory":"5000Mi"}}}'
        ),
        "replayer-config-overrides": (
            '{"readyRequestsBufferPerThread":256,"maxConcurrentRequests":20000,'
            '"resources":{"requests":{"cpu":"8000m","memory":"24Gi","ephemeral-storage":"2Gi"},'
            '"limits":{"cpu":"12000m","memory":"24Gi"}}}'
        ),
    }
    # Whole partitions per proxy and per replayer, so no pod carries a heavier share.
    partitions = int(case.K6_WORKFLOW_PARAMETERS["traffic-topic-partitions"])
    assert partitions % int(case.K6_WORKFLOW_PARAMETERS["capture-proxy-pod-replicas"]) == 0
    assert partitions % int(case.K6_WORKFLOW_PARAMETERS["replayer-pod-replicas"]) == 0
    replayer_overrides = json.loads(
        case.K6_WORKFLOW_PARAMETERS["replayer-config-overrides"]
    )
    kafka_overrides = json.loads(
        case.K6_WORKFLOW_PARAMETERS["kafka-node-pool-spec-overrides"]
    )
    assert kafka_overrides == {
        "resources": {
            "requests": {"cpu": "12", "memory": "24Gi"},
            "limits": {"cpu": "12", "memory": "24Gi"},
        },
        "storage": {
            "type": "persistent-claim",
            "size": "3Ti",
            "class": "kafka-gp3-provisioned",
            "deleteClaim": True,
        },
    }
    assert replayer_overrides["readyRequestsBufferPerThread"] == 256
    assert replayer_overrides["resources"] == {
        "requests": {
            "cpu": "8000m",
            "memory": "24Gi",
            "ephemeral-storage": "2Gi",
        },
        "limits": {"cpu": "12000m", "memory": "24Gi"},
    }
    assert replayer_overrides["maxConcurrentRequests"] == 20000
    assert "numClientThreads" not in replayer_overrides
    assert "RETRY_ENABLED" not in case.K6_PARAMETERS
    assert "HTTP_REQ_FAILED_THRESHOLD" not in case.K6_PARAMETERS
    assert "INGEST_ERROR_THRESHOLD" not in case.K6_PARAMETERS
    assert "podChurnEnabled" not in case.K6_PARAMETERS


def test_console_cluster_access_requires_prepared_index_on_both_clusters(monkeypatch):
    expected_index = "k6-nyc-taxis-test"
    run = Mock(return_value=SimpleNamespace(
        returncode=0,
        stdout=json.dumps({
            "source_cluster": [{"index": expected_index}],
            "target_cluster": [{"index": expected_index}],
        }),
        stderr="",
    ))
    monkeypatch.setattr(k6_test.subprocess, "run", run)

    k6_test._assert_console_cluster_access(expected_index)

    run.assert_called_once_with(
        ["console", "--json", "clusters", "cat-indices"],
        capture_output=True,
        text=True,
        timeout=120,
    )


@pytest.mark.parametrize(
    "stdout",
    (
        "not-json",
        json.dumps({
            "source_cluster": [{"index": "different-index"}],
            "target_cluster": [{"index": "k6-nyc-taxis-test"}],
        }),
        json.dumps({
            "source_cluster": "Error: source unavailable",
            "target_cluster": [{"index": "k6-nyc-taxis-test"}],
        }),
    ),
)
def test_console_cluster_access_rejects_unusable_output(monkeypatch, stdout):
    monkeypatch.setattr(k6_test.subprocess, "run", Mock(return_value=SimpleNamespace(
        returncode=0,
        stdout=stdout,
        stderr="",
    )))

    with pytest.raises(AssertionError):
        k6_test._assert_console_cluster_access("k6-nyc-taxis-test")


def test_high_load_stress_accepts_the_es710_benchmark_topology():
    args = k6_test.MATestUserArguments(
        source_version="ES_7.10",
        target_version="ES_7.10",
        unique_id="test",
        reuse_clusters=True,
    )

    case = k6_test.Test0082CdcK6HighLoadStressTest(args)

    assert str(case.source_version) == "ES_7.10"
    assert str(case.target_version) == "ES_7.10"


def test_k6_index_mapping_is_packaged_with_the_integration_test():
    mapping = json.loads(k6_test.K6_INDEX_MAPPING_PATH.read_text())

    assert mapping["mappings"]["dynamic"] == "strict"
    assert "vendor_name" in mapping["mappings"]["properties"]


def test_k6_index_is_created_directly_on_target_and_source_with_the_same_shards(
    monkeypatch, tmp_path,
):
    mapping_path = tmp_path / "mapping.json"
    mapping_path.write_text(json.dumps({
        "mappings": {
            "dynamic": "strict",
            "properties": {"vendor_id": {"type": "keyword"}},
        },
    }))
    monkeypatch.setattr(k6_test, "K6_INDEX_MAPPING_PATH", mapping_path)

    target_operations = Mock()
    source_operations = Mock()
    response = Mock()
    response.json.return_value = {
        "nyc_taxis": {"settings": {"index": {"number_of_shards": "24"}}}
    }
    target_operations.get_index.return_value = response
    source_operations.get_index.return_value = response
    case = SimpleNamespace(
        K6_PARAMETERS={"INDEX_NUMBER_OF_SHARDS": "24"},
        _index_name="nyc_taxis",
        target_operations=target_operations,
        source_operations=source_operations,
        target_cluster=object(),
        source_cluster=object(),
    )

    k6_test.Test0080CdcK6LoadTest._prepare_index_directly(case)

    for operations in (target_operations, source_operations):
        operations.create_index.assert_called_once()
        definition = json.loads(operations.create_index.call_args.kwargs["data"])
        assert definition["settings"]["number_of_shards"] == 24
        assert definition["mappings"]["dynamic"] == "strict"
        operations.get_index.assert_called_once()


def test_k6_chart_defaults_reserve_capacity_for_every_runner():
    chart = _REPO / "deployment/k8s/charts/components/k6LoadTest"
    values = yaml.safe_load((chart / "values.yaml").read_text())

    assert values["testRun"]["runner"]["resources"] == {
        "requests": {"cpu": "1", "memory": "4Gi"},
        "limits": {"cpu": "2", "memory": "8Gi"},
    }
    workflow_template = (chart / "templates/k6-workflowtemplates.yaml").read_text()
    assert "toYaml $.Values.testRun.runner.resources | nindent 16" in workflow_template


def test_k6_chart_liveness_checks_the_runner_process_not_its_busy_api():
    chart = _REPO / "deployment/k8s/charts/components/k6LoadTest"
    values = yaml.safe_load((chart / "values.yaml").read_text())

    assert values["testRun"]["runner"]["livenessProbe"] == {
        "exec": {"command": ["/bin/sh", "-c", "kill -0 1"]},
        "periodSeconds": 30,
        "timeoutSeconds": 5,
        "failureThreshold": 3,
    }
    workflow_template = (chart / "templates/k6-workflowtemplates.yaml").read_text()
    assert "{{- with $.Values.testRun.runner.livenessProbe }}" in workflow_template
    assert "{{ toYaml . | nindent 16 }}" in workflow_template


def test_k6_chart_spreads_each_runner_fleet_across_availability_zones():
    chart = _REPO / "deployment/k8s/charts/components/k6LoadTest"
    values = yaml.safe_load((chart / "values.yaml").read_text())

    assert values["testRun"]["runner"]["zoneSpread"] == {
        "enabled": True,
        "maxSkew": 1,
        "topologyKey": "topology.kubernetes.io/zone",
        "whenUnsatisfiable": "DoNotSchedule",
    }
    assert values["testRun"]["runner"]["hostnameSpread"] == {
        "enabled": False,
        "maxSkew": 1,
        "topologyKey": "kubernetes.io/hostname",
        "whenUnsatisfiable": "ScheduleAnyway",
    }
    workflow_template = (chart / "templates/k6-workflowtemplates.yaml").read_text()
    assert 'k6_cr: "{{`{{workflow.name}}`}}"' in workflow_template
    assert 'runner: "true"' in workflow_template


def test_k6_chart_places_load_pods_but_not_initializer_on_dedicated_pool():
    chart = _REPO / "deployment/k8s/charts/components/k6LoadTest"
    values = yaml.safe_load((chart / "values.yaml").read_text())
    workflow_template = (chart / "templates/k6-workflowtemplates.yaml").read_text()

    assert values["testRun"]["nodePoolName"] == ""
    assert workflow_template.count(
        "migrations.opensearch.org/workload-pool: "
        "{{ $.Values.testRun.nodePoolName | quote }}"
    ) == 2
    assert workflow_template.count(
        "value: {{ $.Values.testRun.nodePoolName | quote }}"
    ) == 2
    assert workflow_template.count("effect: NoSchedule") == 2
    initializer = workflow_template.split("initializer:", 1)[1].split("runner:", 1)[0]
    assert "nodeSelector:" not in initializer


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
    assert defaults["kafka-node-pool-spec-overrides"] == "{}"
    assert defaults["traffic-topic-partitions"] == "2"
    assert defaults["replayer-pod-replicas"] == "1"

    for workflow_parameter, overlay_parameter in (
        ("capture-proxy-pod-replicas", "captureProxyPodReplicas"),
        ("kafka-broker-replicas", "kafkaBrokerReplicas"),
        ("kafka-node-pool-spec-overrides", "kafkaNodePoolSpecOverrides"),
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
    assert overlay_defaults["kafkaNodePoolSpecOverrides"] == "{}"
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


def test_k6_cdc_only_imported_workflow_uses_the_standard_tuple_bucket():
    workflow = yaml.safe_load((
        _REPO / "migrationConsole/lib/integ_test/testWorkflows/cdcOnlyImportedClusters.yaml"
    ).read_text())
    defaults = {
        parameter["name"]: parameter["value"]
        for parameter in workflow["spec"]["arguments"]["parameters"]
    }
    assert defaults["snapshot-configmap"] == "migrations-default-s3-config"

    main = next(
        template for template in workflow["spec"]["templates"]
        if template["name"] == "main"
    )
    main_inputs = {
        parameter["name"]: parameter
        for parameter in main["inputs"]["parameters"]
    }
    assert main_inputs["tupleS3BucketName"]["valueFrom"]["configMapKeyRef"]["key"] == "BUCKET_NAME"
    assert main_inputs["tupleS3Region"]["valueFrom"]["configMapKeyRef"]["key"] == "AWS_REGION"

    build = next(
        template for template in workflow["spec"]["templates"]
        if template["name"] == "build-cdc-only-config"
    )
    script = build["container"]["args"][0]
    assert '"tupleS3Bucket": $tupleS3Bucket' in script
    assert '"tupleS3Region": $tupleS3Region' in script


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
