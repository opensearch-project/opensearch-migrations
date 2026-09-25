import base64
import json
from types import SimpleNamespace

import pytest

from console_link.workflow.application.connectivity import (
    ConnectivityCheckService,
    ConnectivityTarget,
    RepositoryCheckJobRunner,
    RESULT_MARKER,
)


class _ConfigService:
    def resolve_console_resources(self, raw_yaml):
        assert raw_yaml == "config"
        return {
            "sources": [{
                "refName": "source",
                "editPath": ["sourceClusters", "source"],
                "clientConfig": {
                    "endpoint": "https://source.example",
                    "basic_auth": {"k8s_secret_name": "source-auth"},
                },
                "repositories": [{
                    "refName": "repo",
                    "provider": "s3",
                    "editPath": [
                        "sourceClusters", "source", "snapshotInfo", "repos", "repo",
                    ],
                    "clientConfig": {
                        "repo_uri": "s3://bucket/prefix",
                        "s3_region": "us-east-2",
                    },
                }],
            }],
            "targets": [{
                "refName": "target",
                "editPath": ["targetClusters", "target"],
                "clientConfig": {
                    "endpoint": "https://target.example",
                    "no_auth": None,
                },
            }],
        }


class _InvalidConfigService:
    def resolve_console_resources(self, _raw_yaml):
        raise RuntimeError("sourceClusters.source.endpoint is required")


class _CoreApi:
    def read_namespaced_secret(self, name, namespace):
        assert namespace == "ma"
        assert name == "source-auth"
        return SimpleNamespace(
            data={
                "username": base64.b64encode(b"user").decode(),
                "password": base64.b64encode(b"pass").decode(),
            },
            string_data=None,
        )


class _ConnectionResult:
    def to_dict(self):
        return {
            "status": "valid",
            "connection_established": True,
            "connection_message": "Connected.",
            "cluster_version": "2.19.0",
            "stages": [{
                "name": "cluster-api",
                "status": "passed",
                "message": "Connected and authenticated.",
                "http_status": 200,
            }],
        }


class _FailedConnectionResult:
    def to_dict(self):
        return {
            "status": "failed",
            "connection_established": False,
            "connection_message": "Forbidden.",
            "diagnostic_log": "HTTP 403 Forbidden",
            "stages": [{
                "name": "cluster-api",
                "status": "failed",
                "message": "The principal is not authorized.",
                "code": "authorization-failed",
                "http_status": 403,
            }],
        }


class _RepositoryRunner:
    def run(self, target):
        assert target.id == "repository:source:repo"
        return {
            "status": "partially_verified",
            "summary": "Prefix is empty.",
            "stages": [],
            "logs": "No objects found.",
        }


def test_prepare_builds_stable_source_target_and_repository_inventory():
    service = ConnectivityCheckService(
        namespace="ma",
        config_service=_ConfigService(),
        core_api=_CoreApi(),
        repository_runner=_RepositoryRunner(),
    )

    prepared = service.prepare("config", "nonce-1")

    assert prepared.config_nonce == "nonce-1"
    assert [target.id for target in prepared.targets] == [
        "repository:source:repo",
        "source:source",
        "target:target",
    ]
    assert prepared.targets[0].inventory_dict() == {
        "id": "repository:source:repo",
        "kind": "repository",
        "refName": "repo",
        "label": "Repository repo",
        "editPath": [
            "sourceClusters", "source", "snapshotInfo", "repos", "repo",
        ],
        "provider": "s3",
    }


def test_prepare_reports_strict_schema_failures_as_validation_errors():
    service = ConnectivityCheckService(
        namespace="ma",
        config_service=_InvalidConfigService(),
        core_api=_CoreApi(),
        repository_runner=_RepositoryRunner(),
    )

    with pytest.raises(
        ValueError,
        match="sourceClusters.source.endpoint is required",
    ):
        service.prepare("invalid", "nonce-invalid")


def test_run_parallel_checks_resolves_kubernetes_basic_auth():
    seen_configs = []
    service = ConnectivityCheckService(
        namespace="ma",
        config_service=_ConfigService(),
        core_api=_CoreApi(),
        repository_runner=_RepositoryRunner(),
        cluster_factory=lambda config: seen_configs.append(config) or config,
        cluster_checker=lambda _cluster: _ConnectionResult(),
    )
    prepared = service.prepare("config", "nonce-2")

    result = service.run(prepared)

    assert result["configNonce"] == "nonce-2"
    assert result["status"] == "partially_verified"
    assert [check["targetId"] for check in result["checks"]] == [
        "repository:source:repo",
        "source:source",
        "target:target",
    ]
    assert any(
        config.get("basic_auth") == {
            "username": "user",
            "password": "pass",
        }
        for config in seen_configs
    )


def test_run_rejects_a_target_from_an_older_draft():
    service = ConnectivityCheckService(
        namespace="ma",
        config_service=_ConfigService(),
        core_api=_CoreApi(),
        repository_runner=_RepositoryRunner(),
    )
    prepared = service.prepare("config", "nonce-3")

    with pytest.raises(ValueError, match="not found"):
        service.run(prepared, ["source:removed"])


def test_failed_cluster_check_includes_optional_aws_principal_and_full_log():
    service = ConnectivityCheckService(
        namespace="ma",
        config_service=_ConfigService(),
        core_api=_CoreApi(),
        repository_runner=_RepositoryRunner(),
        cluster_factory=lambda config: config,
        cluster_checker=lambda _cluster: _FailedConnectionResult(),
        principal_resolver=lambda _config: (
            "arn:aws:sts::123456789012:assumed-role/migration/console"
        ),
    )
    prepared = service.prepare("config", "nonce-failed")

    result = service.run(prepared, ["target:target"])

    assert result["status"] == "failed"
    assert result["checks"][0]["logs"] == "HTTP 403 Forbidden"
    assert result["checks"][0]["executionContext"]["principal"] == (
        "arn:aws:sts::123456789012:assumed-role/migration/console"
    )


class _BatchApi:
    def __init__(self):
        self.created = None
        self.deleted = None

    def create_namespaced_job(self, namespace, body):
        self.created = (namespace, body)

    def read_namespaced_job_status(self, name, namespace):
        return SimpleNamespace(status=SimpleNamespace(succeeded=1, failed=0))

    def delete_namespaced_job(self, name, namespace, propagation_policy):
        self.deleted = (name, namespace, propagation_policy)


class _JobCoreApi:
    def list_namespaced_pod(self, namespace, label_selector):
        return SimpleNamespace(
            items=[SimpleNamespace(metadata=SimpleNamespace(name="probe-pod"))],
        )

    def read_namespaced_pod_log(self, **_kwargs):
        return (
            "probe output\n"
            f"{RESULT_MARKER}\n"
            + json.dumps({
                "status": "partially_verified",
                "summary": "The prefix is empty.",
                "stages": [],
            })
        )


def test_repository_runner_uses_the_running_console_image_digest(monkeypatch):
    monkeypatch.setenv("HOSTNAME", "migration-console-0")
    core_api = _JobCoreApi()
    core_api.read_namespaced_pod = lambda **_kwargs: SimpleNamespace(
        status=SimpleNamespace(container_statuses=[SimpleNamespace(
            name="console",
            image_id=(
                "docker-pullable://registry.example.com/migration-console"
                "@sha256:1234"
            ),
        )]),
        spec=SimpleNamespace(containers=[SimpleNamespace(
            name="console",
            image="registry.example.com/migration-console:mutable",
        )]),
    )
    runner = RepositoryCheckJobRunner(
        namespace="ma",
        core_api=core_api,
        batch_api=_BatchApi(),
    )

    assert runner._current_console_image() == (
        "registry.example.com/migration-console@sha256:1234"
    )


def test_repository_runner_uses_workflow_service_account_and_cleans_up():
    batch_api = _BatchApi()
    runner = RepositoryCheckJobRunner(
        namespace="ma",
        core_api=_JobCoreApi(),
        batch_api=batch_api,
        image="migration-console:test",
        poll_seconds=0,
    )
    target = ConnectivityTarget(
        id="repository:source:repo",
        kind="repository",
        ref_name="repo",
        label="Repository repo",
        edit_path=("sourceClusters", "source", "snapshotInfo", "repos", "repo"),
        client_config={
            "repo_uri": "s3://bucket/prefix",
            "s3_region": "us-east-2",
            "endpoint": "http://localstack:4566",
            "use_local_stack": True,
        },
        provider="s3",
    )

    result = runner.run(target)

    assert result["status"] == "partially_verified"
    job = batch_api.created[1]
    pod_spec = job.spec.template.spec
    assert pod_spec.service_account_name == "argo-workflow-executor"
    assert pod_spec.containers[0].image == "migration-console:test"
    assert pod_spec.containers[0].env[0].name == "AWS_SHARED_CREDENTIALS_FILE"
    assert batch_api.deleted[1:] == ("ma", "Background")
