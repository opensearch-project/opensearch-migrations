"""Service layer for K8s Deployment pause/resume/scale operations."""

import fnmatch
import logging
from typing import List, Optional

from kubernetes import client

from ..models.utils import load_k8s_config

logger = logging.getLogger(__name__)

# Deployments with this label are discoverable for pause/resume/scale operations.
# The label value encodes the max replica count: "0" for unlimited, or a positive
# integer (e.g. "1") to cap scaling. Presence of the label implies the Deployment
# is both pausable and scaleable.
SCALEABLE_LABEL = "migrations.opensearch.org/scaleable"
TASK_LABEL = "migrations.opensearch.org/task"
WORKFLOW_LABEL = "workflows.argoproj.io/workflow"
PRE_PAUSE_ANNOTATION = "migrations.opensearch.org/pre-pause-replicas"


class DeploymentInfo:
    """Lightweight representation of a pausable Deployment."""
    def __init__(self, name: str, namespace: str, replicas: int, task_name: Optional[str],
                 pre_pause_replicas: Optional[int], max_replicas: int = 0):
        self.name = name
        self.namespace = namespace
        self.replicas = replicas
        self.task_name = task_name
        self.pre_pause_replicas = pre_pause_replicas
        self.max_replicas = max_replicas

    @property
    def is_paused(self) -> bool:
        return self.replicas == 0 and self.pre_pause_replicas is not None

    @property
    def display_name(self) -> str:
        return self.task_name or self.name


class DeploymentService:
    """Manages pause/resume/scale for pausable K8s Deployments owned by an Argo Workflow."""

    def __init__(self):
        load_k8s_config()
        self.apps_api = client.AppsV1Api()

    def discover_pausable_deployments(self, workflow_name: str, namespace: str) -> List[DeploymentInfo]:
        """Find all Deployments with the scaleable label owned by the given workflow."""
        label_selector = f"{SCALEABLE_LABEL},{WORKFLOW_LABEL}={workflow_name}"
        result = self.apps_api.list_namespaced_deployment(namespace=namespace, label_selector=label_selector)
        return [self._to_info(d) for d in result.items]

    def filter_by_task_names(self, deployments: List[DeploymentInfo],
                             task_names: tuple) -> List[DeploymentInfo]:
        """Filter deployments by task name patterns (glob supported)."""
        if not task_names:
            return deployments
        matched = []
        for dep in deployments:
            for pattern in task_names:
                if fnmatch.fnmatch(dep.display_name, pattern):
                    if dep not in matched:
                        matched.append(dep)
                    break
        return matched

    def pause_deployment(self, dep: DeploymentInfo) -> dict:
        """Annotate with current replica count and scale to 0."""
        if dep.is_paused:
            return {"success": False, "message": f"{dep.display_name} is already paused"}
        if dep.replicas == 0:
            return {"success": False, "message": f"{dep.display_name} has 0 replicas, nothing to pause"}

        body = {
            "metadata": {
                "annotations": {PRE_PAUSE_ANNOTATION: str(dep.replicas)}
            },
            "spec": {"replicas": 0}
        }
        self.apps_api.patch_namespaced_deployment(name=dep.name, namespace=dep.namespace, body=body)
        return {"success": True, "message": f"Paused {dep.display_name} (was {dep.replicas} replicas)"}

    def resume_deployment(self, dep: DeploymentInfo) -> dict:
        """Restore pre-pause replica count and remove annotation."""
        if not dep.is_paused:
            return {"success": False, "message": f"{dep.display_name} is not paused"}

        target_replicas = dep.pre_pause_replicas
        body = {
            "metadata": {
                "annotations": {PRE_PAUSE_ANNOTATION: None}
            },
            "spec": {"replicas": target_replicas}
        }
        self.apps_api.patch_namespaced_deployment(name=dep.name, namespace=dep.namespace, body=body)
        return {"success": True, "message": f"Resumed {dep.display_name} to {target_replicas} replicas"}

    def scale_deployment(self, dep: DeploymentInfo, replicas: int) -> dict:
        """Set replica count for a Deployment. Manages the pre-pause annotation for consistency."""
        if replicas > 0 and dep.max_replicas > 0 and replicas > dep.max_replicas:
            return {"success": False,
                    "message": f"{dep.display_name}: cannot scale to {replicas} replicas (max: {dep.max_replicas})"}
        body: dict = {"spec": {"replicas": replicas}}
        if replicas == 0 and dep.replicas > 0:
            # Scaling to 0 behaves like pause — store current count
            body["metadata"] = {"annotations": {PRE_PAUSE_ANNOTATION: str(dep.replicas)}}
        elif replicas > 0 and dep.pre_pause_replicas is not None:
            # Scaling to >0 clears the paused state
            body["metadata"] = {"annotations": {PRE_PAUSE_ANNOTATION: None}}
        self.apps_api.patch_namespaced_deployment(name=dep.name, namespace=dep.namespace, body=body)
        return {"success": True, "message": f"Scaled {dep.display_name} to {replicas} replicas"}

    def _to_info(self, deployment) -> DeploymentInfo:
        labels = deployment.metadata.labels or {}
        annotations = deployment.metadata.annotations or {}
        pre_pause = annotations.get(PRE_PAUSE_ANNOTATION)
        scaleable_val = labels.get(SCALEABLE_LABEL, "0")
        try:
            max_replicas = int(scaleable_val)
        except (ValueError, TypeError):
            max_replicas = 0
        return DeploymentInfo(
            name=deployment.metadata.name,
            namespace=deployment.metadata.namespace,
            replicas=deployment.spec.replicas or 0,
            task_name=labels.get(TASK_LABEL),
            pre_pause_replicas=int(pre_pause) if pre_pause is not None else None,
            max_replicas=max_replicas,
        )
