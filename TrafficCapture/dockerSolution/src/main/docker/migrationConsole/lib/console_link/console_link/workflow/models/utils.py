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


def load_k8s_config():
    """Load Kubernetes configuration.

    Attempts to load in-cluster config first (when running in a pod),
    then falls back to kubeconfig file (for local development).

    Raises:
        config.ConfigException: If neither configuration method succeeds
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
            raise


def get_store(ctx):
    """Lazy initialization of WorkflowConfigStore"""
    from ..models.store import WorkflowConfigStore
    if ctx.obj['store'] is None:
        ctx.obj['store'] = WorkflowConfigStore(namespace=ctx.obj['namespace'])
    return ctx.obj['store']
