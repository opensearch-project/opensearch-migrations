import base64
import json
import logging
import os
from pathlib import Path
import subprocess
import time
from typing import Any

from console_link.middleware.clusters import cat_indices
from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.command_runner import FlagOnlyArgument

from ..cluster_version import ElasticsearchV7_X, OpensearchV2_X
from ..integration_test_argo_service import IntegrationTestArgoService
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)

WORKFLOW_TEMPLATE_NAME = os.environ.get("WORKFLOW_TEMPLATE_NAME", "full-migration-with-workflow-cli")
WORKFLOW_NAME = os.environ.get("WORKFLOW_NAME", "byoc-captured-traffic-smoke")
INNER_WORKFLOW_NAME = os.environ.get("INNER_WORKFLOW_NAME", "migration-workflow")
S3_BUCKET = os.environ.get("S3_BUCKET", "byoc-traffic")
S3_KEY = os.environ.get("S3_KEY", "fixtures/byoc-put.proto.gz")
S3_ENDPOINT = os.environ.get("S3_ENDPOINT", "http://localstack.ma.svc.cluster.local:4566")
CAPTURED_TRAFFIC_NAME = os.environ.get("CAPTURED_TRAFFIC_NAME", "byoc-put-topic")
TRAFFIC_REPLAY_NAME = os.environ.get("TRAFFIC_REPLAY_NAME", "byoc-put-target-replay1")
KAFKA_TOPIC_NAME = os.environ.get("KAFKA_TOPIC_NAME", "byoc-put")
MIGRATION_CONSOLE_POD_NAME = os.environ.get("MIGRATION_CONSOLE_POD_NAME", "migration-console-0")
MIGRATION_CONSOLE_CONTAINER_NAME = os.environ.get("MIGRATION_CONSOLE_CONTAINER_NAME", "console")
MIGRATION_CONSOLE_PULL_POLICY = os.environ.get("MIGRATION_CONSOLE_PULL_POLICY", "Always")
SOURCE_AUTH_SECRET_NAME = os.environ.get("BYOC_SOURCE_AUTH_SECRET_NAME", "source-creds")
SOURCE_AUTH_USERNAME = os.environ.get("BYOC_SOURCE_AUTH_USERNAME", "admin")
SOURCE_AUTH_PASSWORD = os.environ.get("BYOC_SOURCE_AUTH_PASSWORD", "admin")
TARGET_ENDPOINT = os.environ.get("BYOC_TARGET_ENDPOINT", "https://opensearch-cluster-master-headless:9200")
TARGET_AUTH_SECRET_NAME = os.environ.get("BYOC_TARGET_AUTH_SECRET_NAME", "target-creds")
TARGET_AUTH_USERNAME = os.environ.get("BYOC_TARGET_AUTH_USERNAME", "admin")
TARGET_AUTH_PASSWORD = os.environ.get("BYOC_TARGET_AUTH_PASSWORD", "admin")
TARGET_INDEX = os.environ.get("BYOC_TARGET_INDEX", "byoc-e2e")
TARGET_DOC_ID = os.environ.get("BYOC_TARGET_DOC_ID", "1")
FIXTURE_PATH = Path(
    os.environ.get("BYOC_CAPTURE_FIXTURE_PATH", "/root/lib/integ_test/resources/byoc/byoc-put.proto.gz")
)


def _localstack_aws_env() -> dict[str, str]:
    env = os.environ.copy()
    env.setdefault("AWS_ACCESS_KEY_ID", "test")
    env.setdefault("AWS_SECRET_ACCESS_KEY", "test")
    env.setdefault("AWS_DEFAULT_REGION", "us-east-1")
    return env


def _run(
    command: list[str],
    timeout: int = 120,
    env: dict[str, str] | None = None,
    input_text: str | None = None,
) -> subprocess.CompletedProcess:
    logger.info("Running: %s", " ".join(command))
    return subprocess.run(
        command,
        check=True,
        capture_output=True,
        text=True,
        timeout=timeout,
        env=env,
        input=input_text,
    )


def _kubectl(service: IntegrationTestArgoService, args: dict[str, Any], print_output: bool = False):
    return service._run_kubectl_command(args, print_output=print_output)


def _delete_if_present(service: IntegrationTestArgoService, resource_type: str, key_name: str):
    _kubectl(service, {
        "delete": resource_type,
        key_name: FlagOnlyArgument,
        "--namespace": service.namespace,
        "--ignore-not-found": FlagOnlyArgument,
    })


def _cleanup_static_resources(service: IntegrationTestArgoService, include_outer_workflow: bool):
    if include_outer_workflow:
        _delete_if_present(service, "workflow", WORKFLOW_NAME)
    _delete_if_present(service, "workflow", INNER_WORKFLOW_NAME)
    _delete_if_present(service, "trafficreplay", TRAFFIC_REPLAY_NAME)
    _delete_if_present(service, "capturedtraffic", CAPTURED_TRAFFIC_NAME)
    _delete_if_present(service, "approvalgate", f"capturedtraffic.{CAPTURED_TRAFFIC_NAME}.vapretry")


def _create_basic_auth_credentials(secret_name: str, username: str, password: str):
    _run(
        ["workflow", "configure", "credentials", "create", "--stdin", secret_name],
        input_text=f"{username}:{password}",
    )


def _create_auth_secrets():
    _create_basic_auth_credentials(SOURCE_AUTH_SECRET_NAME, SOURCE_AUTH_USERNAME, SOURCE_AUTH_PASSWORD)
    _create_basic_auth_credentials(TARGET_AUTH_SECRET_NAME, TARGET_AUTH_USERNAME, TARGET_AUTH_PASSWORD)


def _delete_auth_secrets(service: IntegrationTestArgoService):
    _delete_if_present(service, "secret", SOURCE_AUTH_SECRET_NAME)
    _delete_if_present(service, "secret", TARGET_AUTH_SECRET_NAME)


def _migration_console_image(service: IntegrationTestArgoService) -> str:
    env_image = os.environ.get("MIGRATION_CONSOLE_IMAGE")
    if env_image:
        return env_image

    result = _run([
        "kubectl",
        "get",
        "pod",
        MIGRATION_CONSOLE_POD_NAME,
        "--namespace",
        service.namespace,
        "-o",
        f"jsonpath={{.spec.containers[?(@.name=='{MIGRATION_CONSOLE_CONTAINER_NAME}')].image}}",
    ])
    return result.stdout.strip()


def _kubectl_wait_for_jsonpath(
    service: IntegrationTestArgoService,
    resource_type: str,
    name: str,
    jsonpath: str,
    expected: str,
    timeout_seconds: int,
    interval: int = 5,
):
    start = time.time()
    last_value = ""
    while time.time() - start < timeout_seconds:
        result = _kubectl(service, {
            "get": resource_type,
            name: FlagOnlyArgument,
            "--namespace": service.namespace,
            "-o": f"jsonpath={{{jsonpath}}}",
        })
        last_value = result.output.stdout.strip()
        if last_value == expected:
            return
        logger.info("Waiting for %s/%s %s=%s, saw %s", resource_type, name, jsonpath, expected, last_value)
        time.sleep(interval)
    raise TimeoutError(
        f"Timed out waiting for {resource_type}/{name} {jsonpath}={expected}; last value was {last_value!r}"
    )


def _get_json(service: IntegrationTestArgoService, resource_type: str, name: str) -> dict[str, Any]:
    result = _kubectl(service, {
        "get": resource_type,
        name: FlagOnlyArgument,
        "--namespace": service.namespace,
        "-o": "json",
    })
    return json.loads(result.output.stdout)


def _target_cluster() -> Cluster:
    return Cluster({
        "endpoint": TARGET_ENDPOINT,
        "allow_insecure": True,
        "basic_auth": {
            "k8s_secret_name": TARGET_AUTH_SECRET_NAME,
        },
    })


def _delete_target_fixture_index():
    response = _target_cluster().call_api(f"/{TARGET_INDEX}", method=HttpMethod.DELETE, raise_error=False, timeout=30)
    if response.status_code not in (200, 404):
        response.raise_for_status()


def _wait_for_target_fixture_document(timeout_seconds: int = 300, interval: int = 5) -> dict[str, Any]:
    cluster = _target_cluster()
    start = time.time()
    last_status = None
    last_body = ""
    while time.time() - start < timeout_seconds:
        cluster.call_api(f"/{TARGET_INDEX}/_refresh", method=HttpMethod.POST, raise_error=False, timeout=30)
        response = cluster.call_api(
            f"/{TARGET_INDEX}/_doc/{TARGET_DOC_ID}",
            method=HttpMethod.GET,
            raise_error=False,
            timeout=30,
        )
        last_status = response.status_code
        last_body = response.text
        if response.status_code == 200:
            document = response.json()
            if document.get("found") is True:
                return document
        logger.info("Waiting for target document %s/_doc/%s, saw HTTP %s", TARGET_INDEX, TARGET_DOC_ID, last_status)
        time.sleep(interval)
    raise TimeoutError(
        f"Timed out waiting for target document {TARGET_INDEX}/_doc/{TARGET_DOC_ID}; "
        f"last response was HTTP {last_status}: {last_body}"
    )


def _target_fixture_document_count() -> int:
    response = _target_cluster().call_api(f"/{TARGET_INDEX}/_count", method=HttpMethod.GET, timeout=30)
    return response.json()["count"]


def _upload_fixture_to_localstack():
    if not FIXTURE_PATH.exists():
        raise FileNotFoundError(
            f"BYOC fixture not found at {FIXTURE_PATH}. "
            "The migration-console image build should stage it from :libraries:kafkaUtils:generateByocPutTrafficFixture."
        )
    aws_env = _localstack_aws_env()
    subprocess.run(
        ["aws", "--endpoint-url", S3_ENDPOINT, "s3", "mb", f"s3://{S3_BUCKET}"],
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
        env=aws_env,
    )
    _run([
        "aws",
        "--endpoint-url",
        S3_ENDPOINT,
        "s3",
        "cp",
        str(FIXTURE_PATH),
        f"s3://{S3_BUCKET}/{S3_KEY}",
    ], timeout=120, env=aws_env)


def _make_migration_config_base64() -> str:
    config_yaml = f"""
kafkaClusterConfiguration:
  default:
    autoCreate:
      auth:
        type: "none"
      clusterSpecOverrides:
        kafka:
          config:
            offsets.topic.replication.factor: 1
            transaction.state.log.replication.factor: 1
            transaction.state.log.min.isr: 1
            default.replication.factor: 1
            min.insync.replicas: 1
      nodePoolSpecOverrides:
        replicas: 1
        roles:
          - controller
          - broker
        storage:
          type: persistent-claim
          size: 1Gi
          deleteClaim: true
      topicSpecOverrides:
        partitions: 1
        replicas: 1
sourceClusters:
  source:
    endpoint: "https://elasticsearch-master-headless:9200"
    allowInsecure: true
    version: "ES 7.10.2"
    authConfig:
      basic:
        secretName: "{SOURCE_AUTH_SECRET_NAME}"
targetClusters:
  target:
    endpoint: "{TARGET_ENDPOINT}"
    allowInsecure: true
    authConfig:
      basic:
        secretName: "{TARGET_AUTH_SECRET_NAME}"
snapshotMigrationConfigs: []
traffic:
  s3Sources:
    byoc-put:
      s3Uri: "s3://{S3_BUCKET}/{S3_KEY}"
      endpoint: "{S3_ENDPOINT}"
      awsRegion: "us-east-1"
      sourceLabel: "source"
      kafkaTopic: "{KAFKA_TOPIC_NAME}"
  replayers:
    replay1:
      fromCapturedTraffic: "byoc-put"
      toTarget: "target"
      replayerConfig:
        speedupFactor: 30
        observedPacketConnectionTimeout: 5
        lookaheadTimeSeconds: 10
"""
    return base64.b64encode(config_yaml.encode("utf-8")).decode("ascii")


class Test0050ByocCapturedTrafficS3Replay(MATestBase):
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="Loads a BYOC captured-traffic fixture from S3 and replays it into the target cluster.",
            migrations_required=[MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=[(ElasticsearchV7_X, OpensearchV2_X)],
        )
        self.imported_clusters = True
        self.workflow_template = WORKFLOW_TEMPLATE_NAME
        self.keep_workflows = False
        self.target_cluster = _target_cluster()

    def test_before(self):
        _cleanup_static_resources(self.argo_service, include_outer_workflow=True)
        _delete_auth_secrets(self.argo_service)
        _create_auth_secrets()
        _delete_target_fixture_index()
        _upload_fixture_to_localstack()

    def import_existing_clusters(self):
        self.imported_clusters = True

    def prepare_workflow_snapshot_and_migration_config(self):
        pass

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        self.keep_workflows = keep_workflows
        self.parameters = {
            "migrationConfigBase64": _make_migration_config_base64(),
            "imageMigrationConsoleLocation": _migration_console_image(self.argo_service),
            "imageMigrationConsolePullPolicy": MIGRATION_CONSOLE_PULL_POLICY,
            "keepMigrationWorkflow": "true",
        }

    def workflow_start(self):
        start_result = self.argo_service.start_workflow(
            self.workflow_template,
            parameters=self.parameters,
            workflow_name=WORKFLOW_NAME,
            service_account_name="argo-test-workflow-executor",
        )
        assert start_result.success, start_result.value
        self.workflow_name = start_result.value

    def workflow_setup_clusters(self):
        pass

    def prepare_clusters(self):
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 2700):
        self.argo_service.wait_for_ending_phase(workflow_name=self.workflow_name, timeout_seconds=timeout_seconds)
        status = self.argo_service.get_workflow_status(workflow_name=self.workflow_name).value
        assert status.get("phase") == "Succeeded"

    def display_final_cluster_state(self):
        logger.info("Target cluster indices after BYOC replay:")
        logger.info(cat_indices(cluster=self.target_cluster, refresh=True))

    def verify_clusters(self):
        _kubectl_wait_for_jsonpath(
            self.argo_service,
            "capturedtraffic",
            CAPTURED_TRAFFIC_NAME,
            ".status.phase",
            "Ready",
            timeout_seconds=300,
        )
        captured_traffic = _get_json(self.argo_service, "capturedtraffic", CAPTURED_TRAFFIC_NAME)
        status = captured_traffic.get("status", {})
        assert status.get("loadCompleted") is True
        assert status.get("loadStats", {}).get("sourceUri") == f"s3://{S3_BUCKET}/{S3_KEY}"

        target_document = _wait_for_target_fixture_document()
        assert target_document.get("_index") == TARGET_INDEX
        assert target_document.get("_id") == TARGET_DOC_ID
        assert target_document.get("_source") == {}
        assert _target_fixture_document_count() == 1

    def cleanup(self):
        if not self.keep_workflows:
            _cleanup_static_resources(self.argo_service, include_outer_workflow=False)
        _delete_target_fixture_index()
        _delete_auth_secrets(self.argo_service)
