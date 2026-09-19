"""Edit-time connectivity inventory and checks."""

from __future__ import annotations

import base64
import boto3
from botocore.config import Config as BotoConfig
from concurrent.futures import ThreadPoolExecutor, as_completed
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import logging
import os
from pathlib import Path
import shlex
import tempfile
import time
from typing import Any, Callable, Mapping, Optional, Sequence
from uuid import uuid4

from kubernetes import client
from kubernetes.client.rest import ApiException

from ...middleware.clusters import connection_check
from ...models.cluster import Cluster


RESULT_MARKER = "__MIGRATION_CONNECTIVITY_RESULT__"
MAX_LOG_BYTES = 64 * 1024
logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class ConnectivityTarget:
    id: str
    kind: str
    ref_name: str
    label: str
    edit_path: tuple[str, ...]
    client_config: Mapping[str, Any]
    provider: Optional[str] = None

    def inventory_dict(self) -> dict[str, Any]:
        result = {
            "id": self.id,
            "kind": self.kind,
            "refName": self.ref_name,
            "label": self.label,
            "editPath": list(self.edit_path),
        }
        if self.provider:
            result["provider"] = self.provider
        return result


@dataclass(frozen=True)
class PreparedConnectivityChecks:
    config_nonce: str
    targets: tuple[ConnectivityTarget, ...]


class RepositoryCheckJobRunner:
    """Run the shared Java repository check in the workflow execution context."""

    def __init__(
        self,
        *,
        namespace: str,
        core_api: Any,
        batch_api: Any,
        image: Optional[str] = None,
        service_account_name: str = "argo-workflow-executor",
        timeout_seconds: float = 120.0,
        poll_seconds: float = 1.0,
    ):
        self.namespace = namespace
        self.core_api = core_api
        self.batch_api = batch_api
        self.image = image
        self.service_account_name = service_account_name
        self.timeout_seconds = timeout_seconds
        self.poll_seconds = poll_seconds

    def run(self, target: ConnectivityTarget) -> dict[str, Any]:
        job_name = f"repository-check-{uuid4().hex[:12]}"
        image = self.image or self._current_console_image()
        body = self._job(job_name, image, target.client_config)
        logs = ""
        try:
            self.batch_api.create_namespaced_job(
                namespace=self.namespace,
                body=body,
            )
            succeeded = self._wait(job_name)
            logs = self._read_logs(job_name)
            result = self._parse_result(logs)
            if result is None:
                result = _failed_result(
                    "Repository check did not produce structured output.",
                    "probe-output-unavailable",
                    "The repository probe exited without a readable result.",
                )
            elif not succeeded and result.get("status") != "failed":
                result = _failed_result(
                    "Repository check workload failed.",
                    "probe-workload-failed",
                    "The repository probe Job did not complete successfully.",
                )
            return {
                **result,
                "logs": _bounded_logs(logs),
                "executionContext": {
                    "namespace": self.namespace,
                    "serviceAccount": self.service_account_name,
                    "workload": job_name,
                },
            }
        except Exception as error:
            if not logs:
                logs = str(error)
            return {
                **_failed_result(
                    "Repository check workload could not run.",
                    "probe-workload-unavailable",
                    str(error) or type(error).__name__,
                ),
                "logs": _bounded_logs(logs),
                "executionContext": {
                    "namespace": self.namespace,
                    "serviceAccount": self.service_account_name,
                    "workload": job_name,
                },
            }
        finally:
            try:
                self.batch_api.delete_namespaced_job(
                    name=job_name,
                    namespace=self.namespace,
                    propagation_policy="Background",
                )
            except ApiException as error:
                if error.status != 404:
                    logger.warning(
                        "Failed to delete repository check Job %s",
                        job_name,
                        exc_info=True,
                    )
            except Exception:
                logger.warning(
                    "Failed to delete repository check Job %s",
                    job_name,
                    exc_info=True,
                )

    def _current_console_image(self) -> str:
        configured = os.environ.get("MIGRATION_CONSOLE_IMAGE", "").strip()
        if configured:
            return configured
        pod_name = os.environ.get("HOSTNAME", "").strip()
        if not pod_name:
            raise RuntimeError(
                "MIGRATION_CONSOLE_IMAGE is required outside the migration-console pod"
            )
        pod = self.core_api.read_namespaced_pod(
            name=pod_name,
            namespace=self.namespace,
        )
        statuses = (
            getattr(getattr(pod, "status", None), "container_statuses", ())
            or ()
        )
        for status in statuses:
            if getattr(status, "name", "") != "console":
                continue
            image_id = str(getattr(status, "image_id", "") or "")
            if image_id:
                return image_id.removeprefix("docker-pullable://")
        containers = getattr(getattr(pod, "spec", None), "containers", ()) or ()
        for container in containers:
            if getattr(container, "name", "") == "console":
                return str(container.image)
        if containers:
            return str(containers[0].image)
        raise RuntimeError("The migration-console image could not be determined")

    def _job(
        self,
        job_name: str,
        image: str,
        config: Mapping[str, Any],
    ) -> client.V1Job:
        command = [
            "/root/metadataMigration/bin/MetadataMigration",
            "check-repository",
            "--repo-uri",
            str(config["repo_uri"]),
            "--output",
            "json",
            "--output-file",
            "/tmp/repository-check.json",
        ]
        if config.get("s3_region"):
            command.extend(["--s3-region", str(config["s3_region"])])
        if config.get("endpoint"):
            command.extend(["--endpoint", str(config["endpoint"])])
        shell_command = " ".join(shlex.quote(value) for value in command)
        shell_command = (
            "set +e; "
            f"{shell_command}; code=$?; "
            f"printf '\\n{RESULT_MARKER}\\n'; "
            "cat /tmp/repository-check.json 2>/dev/null || true; "
            "exit $code"
        )
        use_local_stack = bool(config.get("use_local_stack"))
        volumes = []
        mounts = []
        environment = []
        if use_local_stack:
            volumes.append(client.V1Volume(
                name="test-creds",
                config_map=client.V1ConfigMapVolumeSource(
                    name="localstack-test-creds",
                    optional=True,
                ),
            ))
            mounts.append(client.V1VolumeMount(
                name="test-creds",
                mount_path="/config/credentials",
                read_only=True,
            ))
            environment.append(client.V1EnvVar(
                name="AWS_SHARED_CREDENTIALS_FILE",
                value="/config/credentials/configuration",
            ))
        container = client.V1Container(
            name="repository-check",
            image=image,
            image_pull_policy="IfNotPresent",
            command=["/bin/sh", "-c"],
            args=[shell_command],
            env=environment,
            volume_mounts=mounts,
        )
        return client.V1Job(
            metadata=client.V1ObjectMeta(
                name=job_name,
                labels={
                    "app": "migration-console-connectivity-check",
                    "migrations.opensearch.org/check-kind": "repository",
                },
            ),
            spec=client.V1JobSpec(
                backoff_limit=0,
                ttl_seconds_after_finished=300,
                template=client.V1PodTemplateSpec(
                    metadata=client.V1ObjectMeta(labels={"job-name": job_name}),
                    spec=client.V1PodSpec(
                        restart_policy="Never",
                        service_account_name=self.service_account_name,
                        containers=[container],
                        volumes=volumes,
                    ),
                ),
            ),
        )

    def _wait(self, job_name: str) -> bool:
        deadline = time.monotonic() + self.timeout_seconds
        while time.monotonic() < deadline:
            job = self.batch_api.read_namespaced_job_status(
                name=job_name,
                namespace=self.namespace,
            )
            status = getattr(job, "status", None)
            if getattr(status, "succeeded", 0):
                return True
            if getattr(status, "failed", 0):
                return False
            time.sleep(self.poll_seconds)
        raise TimeoutError(
            f"Timed out waiting for repository check Job '{job_name}'"
        )

    def _read_logs(self, job_name: str) -> str:
        pods = self.core_api.list_namespaced_pod(
            namespace=self.namespace,
            label_selector=f"job-name={job_name}",
        )
        items = getattr(pods, "items", ()) or ()
        if not items:
            return ""
        pod_name = str(items[0].metadata.name)
        return str(self.core_api.read_namespaced_pod_log(
            name=pod_name,
            namespace=self.namespace,
            container="repository-check",
            timestamps=False,
        ))

    @staticmethod
    def _parse_result(logs: str) -> Optional[dict[str, Any]]:
        if RESULT_MARKER not in logs:
            return None
        payload = logs.rsplit(RESULT_MARKER, 1)[1].strip()
        if not payload:
            return None
        try:
            return json.loads(payload)
        except json.JSONDecodeError:
            return None


class ConnectivityCheckService:
    """Resolve and execute connectivity checks for one strict draft."""

    def __init__(
        self,
        *,
        namespace: str,
        config_service: Any,
        core_api: Any,
        repository_runner: RepositoryCheckJobRunner,
        cluster_factory: Callable[[dict[str, Any]], Cluster] = Cluster,
        cluster_checker: Callable[[Cluster], Any] = connection_check,
        principal_resolver: Callable[
            [Mapping[str, Any]],
            Optional[str],
        ] = lambda config: _resolve_aws_principal(config),
        max_workers: int = 4,
    ):
        self.namespace = namespace
        self.config_service = config_service
        self.core_api = core_api
        self.repository_runner = repository_runner
        self.cluster_factory = cluster_factory
        self.cluster_checker = cluster_checker
        self.principal_resolver = principal_resolver
        self.max_workers = max(1, max_workers)

    def prepare(
        self,
        raw_yaml: str,
        config_nonce: str,
    ) -> PreparedConnectivityChecks:
        try:
            resources = self.config_service.resolve_console_resources(raw_yaml)
        except RuntimeError as error:
            raise ValueError(str(error)) from error
        targets = _connectivity_targets(resources)
        return PreparedConnectivityChecks(
            config_nonce=config_nonce,
            targets=targets,
        )

    def run(
        self,
        prepared: PreparedConnectivityChecks,
        target_ids: Sequence[str] = (),
    ) -> dict[str, Any]:
        selected = _selected_targets(prepared.targets, target_ids)
        if not selected:
            return {
                "configNonce": prepared.config_nonce,
                "status": "valid",
                "summary": "No applicable connectivity checks are configured.",
                "checks": [],
            }
        results = []
        with ThreadPoolExecutor(
            max_workers=min(self.max_workers, len(selected)),
            thread_name_prefix="connectivity-check",
        ) as executor:
            futures = {
                executor.submit(self._check, target): target
                for target in selected
            }
            for future in as_completed(futures):
                target = futures[future]
                try:
                    result = future.result()
                except Exception as error:
                    result = _failed_result(
                        "Connectivity check could not run.",
                        "check-unavailable",
                        str(error) or type(error).__name__,
                    )
                results.append(_target_result(target, result))
        results.sort(key=lambda result: result["targetId"])
        status = _overall_status(results)
        return {
            "configNonce": prepared.config_nonce,
            "status": status,
            "summary": _overall_summary(status, len(results)),
            "checks": results,
        }

    def _check(self, target: ConnectivityTarget) -> dict[str, Any]:
        if target.kind == "repository":
            return self.repository_runner.run(target)
        if not str(target.client_config.get("endpoint") or "").strip():
            return {
                "status": "not_applicable",
                "summary": (
                    "No direct cluster endpoint is configured, so a direct "
                    "connectivity check does not apply."
                ),
                "stages": [{
                    "id": "direct-endpoint",
                    "label": "Check direct cluster endpoint",
                    "status": "skipped",
                    "message": "No direct endpoint is configured.",
                }],
                "logs": "",
            }
        return self._check_cluster(target)

    def _check_cluster(self, target: ConnectivityTarget) -> dict[str, Any]:
        with tempfile.TemporaryDirectory(
            prefix="migration-connectivity-cluster-"
        ) as temp_dir:
            config = self._materialize_cluster_config(
                target.client_config,
                Path(temp_dir),
            )
            try:
                result = self.cluster_checker(self.cluster_factory(config))
                payload = result.to_dict()
                diagnostic_log = str(payload.pop("diagnostic_log", "") or "")
                execution_context = self._cluster_execution_context(
                    config,
                    failed=payload.get("status") == "failed",
                )
                stages = [
                    {
                        "id": stage.pop("name"),
                        "label": "Connect to cluster API",
                        **stage,
                    }
                    for stage in payload.pop("stages", [])
                ]
                logs = "\n".join(
                    f"{stage['status'].upper()}: {stage['message']}"
                    for stage in stages
                )
                return {
                    "status": payload["status"],
                    "summary": payload["connection_message"],
                    "stages": stages,
                    "details": {
                        key: value
                        for key, value in payload.items()
                        if key not in {
                            "status",
                            "connection_message",
                            "connection_established",
                        }
                    },
                    "logs": diagnostic_log or logs,
                    "executionContext": execution_context,
                }
            except Exception as error:
                return {
                    **_failed_result(
                        "Unable to initialize the configured cluster client.",
                        "cluster-client-initialization-failed",
                        str(error) or type(error).__name__,
                    ),
                    "logs": _bounded_logs(str(error)),
                    "executionContext": self._cluster_execution_context(
                        config,
                        failed=True,
                    ),
                }

    def _cluster_execution_context(
        self,
        config: Mapping[str, Any],
        *,
        failed: bool,
    ) -> dict[str, str]:
        context = {
            "namespace": self.namespace,
            "serviceAccount": "migration-console-access-role",
        }
        if failed:
            principal = self.principal_resolver(config)
            if principal:
                context["principal"] = principal
        return context

    def _materialize_cluster_config(
        self,
        source: Mapping[str, Any],
        temp_dir: Path,
    ) -> dict[str, Any]:
        config = deepcopy(dict(source))
        basic_auth = config.get("basic_auth")
        if isinstance(basic_auth, dict) and basic_auth.get("k8s_secret_name"):
            secret_name = str(basic_auth["k8s_secret_name"])
            values = self._secret_values(secret_name)
            missing = [
                key for key in ("username", "password")
                if key not in values
            ]
            if missing:
                raise ValueError(
                    f"Secret '{secret_name}' is missing: {', '.join(missing)}"
                )
            config["basic_auth"] = {
                "username": values["username"],
                "password": values["password"],
            }

        client_cert = config.get("client_cert")
        if isinstance(client_cert, dict) and client_cert.get("k8s_secret_name"):
            config["client_cert"] = self._materialize_client_cert(
                str(client_cert["k8s_secret_name"]),
                temp_dir,
            )

        mtls_auth = config.pop("mtls_auth", None)
        if isinstance(mtls_auth, dict):
            secret_name = str(mtls_auth.get("clientSecretName") or "")
            if not secret_name:
                raise ValueError("mTLS clientSecretName is required")
            config["client_cert"] = self._materialize_client_cert(
                secret_name,
                temp_dir,
            )
            ca_cert = str(mtls_auth.get("caCert") or "")
            if ca_cert:
                ca_path = temp_dir / "server-ca.pem"
                ca_path.write_text(ca_cert, encoding="utf-8")
                ca_path.chmod(0o600)
                config["ca_cert_path"] = str(ca_path)
            config["no_auth"] = None
        return config

    def _materialize_client_cert(
        self,
        secret_name: str,
        temp_dir: Path,
    ) -> dict[str, str]:
        values = self._secret_values(secret_name)
        missing = [key for key in ("tls.crt", "tls.key") if key not in values]
        if missing:
            raise ValueError(
                f"Secret '{secret_name}' is missing: {', '.join(missing)}"
            )
        cert_path = temp_dir / "client.crt"
        key_path = temp_dir / "client.key"
        cert_path.write_text(values["tls.crt"], encoding="utf-8")
        key_path.write_text(values["tls.key"], encoding="utf-8")
        cert_path.chmod(0o600)
        key_path.chmod(0o600)
        return {
            "cert_path": str(cert_path),
            "key_path": str(key_path),
        }

    def _secret_values(self, secret_name: str) -> dict[str, str]:
        secret = self.core_api.read_namespaced_secret(
            name=secret_name,
            namespace=self.namespace,
        )
        values = {}
        for key, value in (getattr(secret, "data", None) or {}).items():
            values[str(key)] = base64.b64decode(value).decode("utf-8")
        for key, value in (getattr(secret, "string_data", None) or {}).items():
            values[str(key)] = str(value)
        return values


def _connectivity_targets(
    resources: Mapping[str, Any],
) -> tuple[ConnectivityTarget, ...]:
    sources = resources.get("sources") or ()
    targets = [
        _cluster_connectivity_target(source, "source")
        for source in sources
    ]
    targets.extend(
        _repository_connectivity_target(source, repository)
        for source in sources
        for repository in source.get("repositories") or ()
    )
    targets.extend(
        _cluster_connectivity_target(target, "target")
        for target in resources.get("targets") or ()
    )
    return tuple(sorted(targets, key=lambda target: target.id))


def _cluster_connectivity_target(
    resource: Mapping[str, Any],
    kind: str,
) -> ConnectivityTarget:
    ref_name = str(resource.get("refName") or "")
    return ConnectivityTarget(
        id=f"{kind}:{ref_name}",
        kind=kind,
        ref_name=ref_name,
        label=f"{kind.capitalize()} {ref_name}",
        edit_path=tuple(resource.get("editPath") or ()),
        client_config=dict(resource.get("clientConfig") or {}),
    )


def _repository_connectivity_target(
    source: Mapping[str, Any],
    repository: Mapping[str, Any],
) -> ConnectivityTarget:
    source_name = str(source.get("refName") or "")
    repository_name = str(repository.get("refName") or "")
    return ConnectivityTarget(
        id=f"repository:{source_name}:{repository_name}",
        kind="repository",
        ref_name=repository_name,
        label=f"Repository {repository_name}",
        edit_path=tuple(repository.get("editPath") or ()),
        client_config=dict(repository.get("clientConfig") or {}),
        provider=str(repository.get("provider") or ""),
    )


def _selected_targets(
    targets: Sequence[ConnectivityTarget],
    target_ids: Sequence[str],
) -> tuple[ConnectivityTarget, ...]:
    if not target_ids:
        return tuple(targets)
    by_id = {target.id: target for target in targets}
    missing = sorted(set(target_ids) - set(by_id))
    if missing:
        raise ValueError(
            "Connectivity target was not found in the current configuration: "
            + ", ".join(missing)
        )
    return tuple(by_id[target_id] for target_id in dict.fromkeys(target_ids))


def _target_result(
    target: ConnectivityTarget,
    result: Mapping[str, Any],
) -> dict[str, Any]:
    return {
        "targetId": target.id,
        "kind": target.kind,
        "refName": target.ref_name,
        "label": target.label,
        "editPath": list(target.edit_path),
        "checkedAt": datetime.now(timezone.utc).isoformat().replace(
            "+00:00",
            "Z",
        ),
        **dict(result),
    }


def _failed_result(
    summary: str,
    code: str,
    message: str,
) -> dict[str, Any]:
    return {
        "status": "failed",
        "summary": summary,
        "stages": [{
            "id": code,
            "label": "Run connectivity check",
            "status": "failed",
            "code": code,
            "message": message,
        }],
    }


def _overall_status(results: Sequence[Mapping[str, Any]]) -> str:
    statuses = {str(result.get("status") or "failed") for result in results}
    if "failed" in statuses:
        return "failed"
    if "partially_verified" in statuses:
        return "partially_verified"
    return "valid"


def _overall_summary(status: str, count: int) -> str:
    if status == "failed":
        return f"{count} connectivity checks completed; at least one failed."
    if status == "partially_verified":
        return (
            f"{count} connectivity checks completed; at least one could only "
            "be partially verified."
        )
    return f"All {count} applicable connectivity checks passed."


def _bounded_logs(logs: str) -> str:
    encoded = logs.encode("utf-8", errors="replace")
    if len(encoded) <= MAX_LOG_BYTES:
        return logs
    return (
        "[Earlier probe output omitted]\n"
        + encoded[-MAX_LOG_BYTES:].decode("utf-8", errors="replace")
    )


def _resolve_aws_principal(config: Mapping[str, Any]) -> Optional[str]:
    if "sigv4" not in config:
        return None
    sigv4 = config.get("sigv4")
    region = (
        str(sigv4.get("region") or "").strip()
        if isinstance(sigv4, Mapping)
        else ""
    )
    try:
        sts = boto3.client(
            "sts",
            region_name=region or None,
            config=BotoConfig(
                connect_timeout=2,
                read_timeout=2,
                retries={"max_attempts": 1, "mode": "standard"},
            ),
        )
        identity = sts.get_caller_identity()
        arn = str(identity.get("Arn") or "").strip()
        return arn or None
    except Exception:
        return None
