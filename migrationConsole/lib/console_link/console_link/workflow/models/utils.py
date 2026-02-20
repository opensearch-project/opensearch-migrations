"""Utility classes and functions for the workflow library."""

import logging
from enum import Enum
from kubernetes import config

logger = logging.getLogger(__name__)


class ExitCode(Enum):
    """Exit codes for command operations."""
    SUCCESS = 0
    FAILURE = 1
    INVALID_INPUT = 2
    NOT_FOUND = 3
    ALREADY_EXISTS = 4
    PERMISSION_DENIED = 5


class KubernetesConfigNotFoundError(Exception):
    """Raised when the Kubernetes ConfigMap is not found."""

    def __init__(self, *args: object) -> None:
        super().__init__(*args)


def load_k8s_config():
    """Load Kubernetes configuration.

    Attempts to load in-cluster config first (when running in a pod),
    then falls back to kubeconfig file (for local development).

    Raises:
        KubernetesConfigNotFoundError: If neither configuration method succeeds
    """
    try:
        # Try to load in-cluster config first (when running in a pod)
        config.load_incluster_config()
        logger.info("Loaded in-cluster Kubernetes configuration")
    except config.ConfigException:
        try:
            # Fall back to local kubeconfig (for development/minikube)
            config.load_kube_config()
            logger.info("Loaded local Kubernetes configuration")
        except config.ConfigException as e:
            logger.error(f"Failed to load Kubernetes configuration: {e}")
            raise KubernetesConfigNotFoundError("Failed to load Kubernetes configuration") from e


def get_workflow_config_store(ctx):
    """Lazy initialization of WorkflowConfigStore"""
    from ..models.workflow_config_store import WorkflowConfigStore
    if ctx.obj['config_store'] is None:
        ctx.obj['config_store'] = WorkflowConfigStore(namespace=ctx.obj['namespace'])
    return ctx.obj['config_store']


def get_secret_store(ctx, use_case: str):
    """Lazy initialization of SecretStore"""
    from ..models.secret_store import SecretStore
    if ctx.obj['secret_store'] is None:
        ctx.obj['secret_store'] = SecretStore(
            namespace=ctx.obj['namespace'],
            default_labels={"use-case": use_case}
        )
    return ctx.obj['secret_store']


def get_credentials_secret_store(ctx):
    return get_secret_store(ctx, "http-basic-credentials")
