"""Storage implementation for workflow configurations using Kubernetes ConfigMaps."""

import logging
from typing import Optional, List

from .config import WorkflowConfig
from .utils import load_k8s_config
from kubernetes import client
from kubernetes.client.rest import ApiException

logger = logging.getLogger(__name__)

# Constants
CONFIG_YAML_KEY = "workflow_config.yaml"


class WorkflowConfigStore:
    """
    Workflow configuration store using Kubernetes etcd via ConfigMaps.
    Stores configurations persistently in the Kubernetes cluster and provides session management.
    """

    def __init__(
        self,
        namespace: str = "default",
        config_map_prefix: str = "workflow-config",
        k8s_client: Optional[client.CoreV1Api] = None
    ):
        """Initialize the store with Kubernetes configuration

        Args:
            namespace: Kubernetes namespace to use for ConfigMaps
            config_map_prefix: Prefix for ConfigMap names
            k8s_client: Optional pre-configured Kubernetes client (for testing)
        """
        self.namespace = namespace
        self.config_map_prefix = config_map_prefix

        if k8s_client:
            # Use provided client (useful for testing)
            self.v1 = k8s_client
            logger.info("Using provided Kubernetes client")
        else:
            # Load Kubernetes configuration
            load_k8s_config()
            self.v1 = client.CoreV1Api()

    def save_config(self, config: WorkflowConfig, session_name: str = "default") -> str:
        """Save workflow configuration to Kubernetes ConfigMap

        Args:
            config: The workflow configuration to save
            session_name: Name of the session/ConfigMap

        Returns:
            A message describing the action taken (created/updated)

        Raises:
            ApiException: If Kubernetes API call fails
            Exception: For other errors during save operation
        """
        config_yaml = config.to_yaml()

        # Create ConfigMap body
        config_map_body = client.V1ConfigMap(
            metadata=client.V1ObjectMeta(
                name=session_name,
                labels={
                    "app": "migration-assistant",
                    "component": "workflow-config",
                    "session": session_name
                }
            ),
            data={
                CONFIG_YAML_KEY: config_yaml,
                "session_name": session_name
            }
        )

        try:
            # Try to update existing ConfigMap
            self.v1.patch_namespaced_config_map(
                name=session_name,
                namespace=self.namespace,
                body=config_map_body
            )
            logger.info(f"Updated workflow config ConfigMap for session: {session_name}")
            return f"Configuration updated for session: {session_name}"
        except ApiException as e:
            if e.status == 404:
                # ConfigMap doesn't exist, create it
                self.v1.create_namespaced_config_map(
                    namespace=self.namespace,
                    body=config_map_body
                )
                logger.info(f"Created workflow config ConfigMap for session: {session_name}")
                return f"Configuration created for session: {session_name}"
            else:
                logger.error(f"Kubernetes API error saving config for session {session_name}: {e}")
                raise

    def load_config(self, session_name: str = "default") -> Optional[WorkflowConfig]:
        """Load workflow configuration from Kubernetes ConfigMap

        Args:
            session_name: Name of the session/ConfigMap to load

        Returns:
            WorkflowConfig if found, None if not found or empty

        Raises:
            ApiException: If Kubernetes API call fails (except 404)
            Exception: For other errors during load operation
        """
        try:
            config_map = self.v1.read_namespaced_config_map(
                name=session_name,
                namespace=self.namespace
            )
        except ApiException as e:
            if e.status == 404:
                logger.info(f"No configuration found for session: {session_name}")
                return None
            else:
                logger.error(f"Kubernetes API error loading config for session {session_name}: {e}")
                raise

        if not config_map.data or CONFIG_YAML_KEY not in config_map.data:
            logger.info(f"ConfigMap {session_name} exists but has no {CONFIG_YAML_KEY} data")
            return None

        config_yaml = config_map.data[CONFIG_YAML_KEY]
        config = WorkflowConfig.from_yaml(config_yaml)

        logger.info(f"Loaded workflow config for session: {session_name}")
        return config

    def delete_config(self, session_name: str = "default") -> str:
        """Delete workflow configuration from Kubernetes ConfigMap

        Args:
            session_name: Name of the session/ConfigMap to delete

        Returns:
            A message describing the deletion

        Raises:
            ApiException: If Kubernetes API call fails (including 404 if not found)
            Exception: For other errors during delete operation
        """
        try:
            self.v1.delete_namespaced_config_map(
                name=session_name,
                namespace=self.namespace
            )
            logger.info(f"Deleted workflow config ConfigMap for session: {session_name}")
            return f"Configuration deleted for session: {session_name}"
        except ApiException as e:
            if e.status == 404:
                logger.warning(f"No configuration found for session: {session_name}")
                raise ApiException(status=404, reason=f"No configuration found for session: {session_name}")
            else:
                logger.error(f"Kubernetes API error deleting config for session {session_name}: {e}")
                raise

    def list_sessions(self) -> List[str]:
        """List all available workflow sessions from Kubernetes ConfigMaps

        Returns:
            List of session names

        Raises:
            ApiException: If Kubernetes API call fails
            Exception: For other errors during list operation
        """
        # List ConfigMaps with our label selector
        config_maps = self.v1.list_namespaced_config_map(
            namespace=self.namespace,
            label_selector="app=migration-assistant,component=workflow-config"
        )

        sessions: List[str] = []
        for config_map in config_maps.items:
            if config_map.data and "session_name" in config_map.data:
                sessions.append(config_map.data["session_name"])
            elif config_map.metadata.labels and "session" in config_map.metadata.labels:
                sessions.append(config_map.metadata.labels["session"])

        logger.info(f"Found {len(sessions)} workflow sessions")
        return sessions

    def close(self):
        """Close any connections (no-op for Kubernetes client)"""
