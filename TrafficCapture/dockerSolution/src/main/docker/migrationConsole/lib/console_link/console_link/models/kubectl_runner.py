import logging

from kubernetes import client, config
from typing import Optional

from console_link.models.command_result import CommandResult
from console_link.models.utils import DeploymentStatus

logger = logging.getLogger(__name__)


class KubectlRunner:
    def __init__(self, namespace: str, deployment_name: str):
        self.namespace = namespace
        self.deployment_name = deployment_name
        try:
            config.load_incluster_config()
        except config.ConfigException:
            logger.warning("Unable to load in-cluster config, falling back to local kubeconfig")
            config.load_kube_config()
        self.k8s_core = client.CoreV1Api()
        self.k8s_apps = client.AppsV1Api()

    def perform_scale_command(self, replicas: int) -> CommandResult:
        body = {"spec": {"replicas": replicas}}
        try:
            self.k8s_apps.patch_namespaced_deployment_scale(name=self.deployment_name, namespace=self.namespace,
                                                            body=body)
            return CommandResult(True, f"The {self.deployment_name} deployment has been set "
                                       f"to {replicas} desired count.")
        except Exception as e:
            logger.error(f"Error faced when performing k8s patch_namespaced_deployment_scale(): {e}")
            return CommandResult(success=False, value=f"Kubernetes action failed: {e}")

    def retrieve_deployment_status(self) -> Optional[DeploymentStatus]:
        try:
            pods = self.k8s_core.list_namespaced_pod(self.namespace, label_selector=f"app={self.deployment_name}")
        except Exception as e:
            logger.error(f"Error faced when performing k8s list_namespaced_pod(): {e}")
            return None

        terminating_pods = 0
        running_pods = 0
        pending_pods = 0
        for pod in pods.items:
            if pod.metadata.deletion_timestamp:
                terminating_pods += 1
            else:
                phase = pod.status.phase
                if phase == "Running":
                    running_pods += 1
                elif phase == "Pending":
                    pending_pods += 1

        try:
            deployment = self.k8s_apps.read_namespaced_deployment(namespace=self.namespace, name=self.deployment_name)
        except Exception as e:
            logger.error(f"Error faced when performing k8s read_namespaced_deployment(): {e}")
            return None

        desired_pods = deployment.spec.replicas
        return DeploymentStatus(
            running=running_pods,
            pending=pending_pods,
            desired=desired_pods,
            terminating=terminating_pods
        )
