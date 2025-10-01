"""Storage implementation for workflow configurations using Kubernetes ConfigMaps."""

import logging
from typing import Optional, List

from ...models.command_result import CommandResult
from .config import WorkflowConfig
from kubernetes import client, config
from kubernetes.client.rest import ApiException

logger = logging.getLogger(__name__)


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

            self.v1 = client.CoreV1Api()

    def _get_config_map_name(self, session_name: str) -> str:
        """Generate ConfigMap name for a session"""
        # Kubernetes names must be DNS-1123 compliant
        safe_session = session_name.lower().replace('_', '-').replace('.', '-')
        return f"{self.config_map_prefix}-{safe_session}"

    def save_config(self, config: WorkflowConfig, session_name: str = "default") -> CommandResult:
        """Save workflow configuration to Kubernetes ConfigMap"""
        try:
            config_map_name = self._get_config_map_name(session_name)
            config_json = config.to_json()

            # Create ConfigMap body
            config_map_body = client.V1ConfigMap(
                metadata=client.V1ObjectMeta(
                    name=config_map_name,
                    labels={
                        "app": "migration-assistant",
                        "component": "workflow-config",
                        "session": session_name
                    }
                ),
                data={
                    "config.json": config_json,
                    "session_name": session_name
                }
            )

            try:
                # Try to update existing ConfigMap
                self.v1.patch_namespaced_config_map(
                    name=config_map_name,
                    namespace=self.namespace,
                    body=config_map_body
                )
                logger.info(f"Updated workflow config ConfigMap for session: {session_name}")
                action = "updated"
            except ApiException as e:
                if e.status == 404:
                    # ConfigMap doesn't exist, create it
                    self.v1.create_namespaced_config_map(
                        namespace=self.namespace,
                        body=config_map_body
                    )
                    logger.info(f"Created workflow config ConfigMap for session: {session_name}")
                    action = "created"
                else:
                    raise

            return CommandResult(success=True, value=f"Configuration {action} for session: {session_name}")

        except ApiException as e:
            logger.error(f"Kubernetes API error saving config for session {session_name}: {e}")
            return CommandResult(success=False, value=f"Failed to save configuration: {e.reason}")
        except Exception as e:
            logger.error(f"Failed to save config for session {session_name}: {str(e)}")
            return CommandResult(success=False, value=f"Failed to save configuration: {str(e)}")

    def load_config(self, session_name: str = "default") -> CommandResult:
        """Load workflow configuration from Kubernetes ConfigMap"""
        try:
            config_map_name = self._get_config_map_name(session_name)

            try:
                config_map = self.v1.read_namespaced_config_map(
                    name=config_map_name,
                    namespace=self.namespace
                )
            except ApiException as e:
                if e.status == 404:
                    logger.info(f"No configuration found for session: {session_name}")
                    return CommandResult(success=True, value=None)
                else:
                    raise

            if not config_map.data or "config.json" not in config_map.data:
                logger.warning(f"ConfigMap {config_map_name} exists but has no config.json data")
                return CommandResult(success=True, value=None)

            config_json = config_map.data["config.json"]
            config = WorkflowConfig.from_json(config_json)

            logger.info(f"Loaded workflow config for session: {session_name}")
            return CommandResult(success=True, value=config)

        except ApiException as e:
            logger.error(f"Kubernetes API error loading config for session {session_name}: {e}")
            return CommandResult(success=False, value=None)
        except Exception as e:
            logger.error(f"Failed to load config for session {session_name}: {str(e)}")
            return CommandResult(success=False, value=None)

    def delete_config(self, session_name: str = "default") -> CommandResult:
        """Delete workflow configuration from Kubernetes ConfigMap"""
        try:
            config_map_name = self._get_config_map_name(session_name)

            try:
                self.v1.delete_namespaced_config_map(
                    name=config_map_name,
                    namespace=self.namespace
                )
                logger.info(f"Deleted workflow config ConfigMap for session: {session_name}")
                return CommandResult(success=True, value=f"Configuration deleted for session: {session_name}")
            except ApiException as e:
                if e.status == 404:
                    return CommandResult(success=False, value=f"No configuration found for session: {session_name}")
                else:
                    raise

        except ApiException as e:
            logger.error(f"Kubernetes API error deleting config for session {session_name}: {e}")
            return CommandResult(success=False, value=f"Failed to delete configuration: {e.reason}")
        except Exception as e:
            logger.error(f"Failed to delete config for session {session_name}: {str(e)}")
            return CommandResult(success=False, value=f"Failed to delete configuration: {str(e)}")

    def list_sessions(self) -> CommandResult:
        """List all available workflow sessions from Kubernetes ConfigMaps"""
        try:
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
            return CommandResult(success=True, value=sessions)

        except ApiException as e:
            logger.error(f"Kubernetes API error listing sessions: {e}")
            return CommandResult(success=False, value=[])
        except Exception as e:
            logger.error(f"Failed to list sessions: {str(e)}")
            return CommandResult(success=False, value=[])

    def close(self):
        """Close any connections (no-op for Kubernetes client)"""
        pass
