"""Kubernetes and exit-code helpers for the load-test client.

There is no config store or secret store here: a load-test run reads its whole definition from the
cluster's WorkflowTemplates.
"""

import logging
from enum import Enum

from kubernetes import config

logger = logging.getLogger(__name__)


SA_NAMESPACE_PATH = '/var/run/secrets/kubernetes.io/serviceaccount/namespace'


def get_current_namespace(fallback='ma'):
    """Return the namespace of the current pod, or fallback if not in-cluster."""
    try:
        with open(SA_NAMESPACE_PATH) as f:
            return f.read().strip()
    except OSError:
        return fallback


class ExitCode(Enum):
    """Exit codes for command operations."""
    SUCCESS = 0
    FAILURE = 1
    INVALID_INPUT = 2
    NOT_FOUND = 3
    ALREADY_EXISTS = 4
    PERMISSION_DENIED = 5


class KubernetesConfigNotFoundError(Exception):
    """Raised when the Kubernetes configuration cannot be loaded."""

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
            # Fall back to local kubeconfig (for development/kind)
            config.load_kube_config()
            logger.info("Loaded local Kubernetes configuration")
        except config.ConfigException as e:
            logger.error(f"Failed to load Kubernetes configuration: {e}")
            raise KubernetesConfigNotFoundError("Failed to load Kubernetes configuration") from e
