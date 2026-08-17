"""Server-lifetime Kubernetes context and API clients."""

from __future__ import annotations

import logging
import os
from pathlib import Path
import subprocess
import tempfile
from typing import Mapping, Optional

import yaml
from kubernetes import client, config


logger = logging.getLogger(__name__)


class PinnedKubernetesRuntime:
    """Keep API calls and child processes on the startup Kubernetes context."""

    def __init__(
        self,
        *,
        context_name: str,
        api_client: client.ApiClient,
        kubeconfig_path: Optional[Path],
        previous_kubeconfig: Optional[str],
    ):
        self.context_name = context_name
        self.api_client = api_client
        self.core_api = client.CoreV1Api(api_client)
        self.custom_api = client.CustomObjectsApi(api_client)
        self.kubeconfig_path = kubeconfig_path
        self._previous_kubeconfig = previous_kubeconfig
        self.subprocess_env: Mapping[str, str] = dict(os.environ)

    def close(self) -> None:
        self.api_client.close()
        if self._previous_kubeconfig is None:
            os.environ.pop("KUBECONFIG", None)
        else:
            os.environ["KUBECONFIG"] = self._previous_kubeconfig
        if self.kubeconfig_path is not None:
            self.kubeconfig_path.unlink(missing_ok=True)

    def __enter__(self) -> "PinnedKubernetesRuntime":
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()


def pin_kubernetes_runtime() -> PinnedKubernetesRuntime:
    """Pin the server to its startup cluster, including kubectl subprocesses."""
    previous_kubeconfig = os.environ.get("KUBECONFIG")
    kubeconfig_path: Optional[Path] = None
    try:
        config.load_incluster_config()
        context_name = "in-cluster"
        logger.info("Pinned Kubernetes runtime to in-cluster configuration")
    except config.ConfigException:
        kubeconfig_path, context_name = _snapshot_active_kubeconfig()
        os.environ["KUBECONFIG"] = str(kubeconfig_path)
        try:
            config.load_kube_config(config_file=str(kubeconfig_path))
        except Exception:
            _restore_kubeconfig(previous_kubeconfig)
            kubeconfig_path.unlink(missing_ok=True)
            raise
        logger.info("Pinned Kubernetes runtime to context %s", context_name)

    api_configuration = client.Configuration.get_default_copy()
    return PinnedKubernetesRuntime(
        context_name=context_name,
        api_client=client.ApiClient(api_configuration),
        kubeconfig_path=kubeconfig_path,
        previous_kubeconfig=previous_kubeconfig,
    )


def _snapshot_active_kubeconfig() -> tuple[Path, str]:
    result = subprocess.run(
        [
            "kubectl",
            "config",
            "view",
            "--raw",
            "--flatten",
            "--minify",
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    document = yaml.safe_load(result.stdout) or {}
    context_name = str(document.get("current-context") or "").strip()
    if not context_name:
        raise RuntimeError("The active kubeconfig does not identify a current context")

    handle = tempfile.NamedTemporaryFile(
        mode="w",
        prefix="workflow-manage-kubeconfig-",
        suffix=".yaml",
        delete=False,
    )
    try:
        handle.write(result.stdout)
        handle.flush()
    finally:
        handle.close()
    path = Path(handle.name)
    path.chmod(0o600)
    return path, context_name


def _restore_kubeconfig(previous_kubeconfig: Optional[str]) -> None:
    if previous_kubeconfig is None:
        os.environ.pop("KUBECONFIG", None)
    else:
        os.environ["KUBECONFIG"] = previous_kubeconfig
