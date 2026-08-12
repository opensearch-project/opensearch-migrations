"""Presentation-neutral access to the subset of Argo state used by manage."""

from dataclasses import dataclass
from typing import Any, Callable, Dict, Optional, Tuple

import ijson
import requests

from .workflow_service import logger, WorkflowService


@dataclass(frozen=True)
class ArgoObservationInterface:
    get_workflow: Callable[[str, str], Tuple[Dict[str, Any], Dict[str, Any]]]


def load_slim_workflow(
    service,
    name: str,
    namespace: str,
    *,
    argo_url: str,
    token: Optional[str],
    insecure: bool,
    request_get=requests.get,
) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    """Load workflow summary data while streaming and slimming the node map."""
    result = service.get_workflow_status(
        name,
        namespace,
        argo_url,
        token,
        insecure,
    )
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{argo_url}/api/v1/workflows/{namespace}/{name}"
    response = request_get(
        url,
        headers=headers,
        verify=not insecure,
        stream=True,
    )
    if response.status_code != 200:
        response.close()
        raise requests.HTTPError(
            f"Request failed with status {response.status_code}",
        )

    slim_nodes = {}
    try:
        for node_id, node in ijson.kvitems(response.raw, "status.nodes"):
            slim_nodes[node_id] = {
                "id": node_id,
                "displayName": node.get("displayName") or node_id,
                "phase": node.get("phase"),
                "type": node.get("type"),
                "boundaryID": node.get("boundaryID"),
                "children": node.get("children", []),
                "startedAt": node.get("startedAt"),
                "finishedAt": node.get("finishedAt"),
                **(
                    {"templateRef": node["templateRef"]}
                    if "templateRef" in node else {}
                ),
                **(
                    {"templateName": node["templateName"]}
                    if "templateName" in node else {}
                ),
                **({"message": node["message"]} if "message" in node else {}),
                "inputs": {
                    "parameters": [
                        parameter
                        for parameter in node.get("inputs", {}).get(
                            "parameters",
                            [],
                        )
                        if parameter["name"] in (
                            "groupName_view",
                            "sortOrder_view",
                            "configContents",
                            "name",
                            "resourceName",
                        )
                    ],
                },
                "outputs": {
                    "parameters": [
                        parameter
                        for parameter in node.get("outputs", {}).get(
                            "parameters",
                            [],
                        )
                        if parameter["name"] in (
                            "statusOutput",
                            "overriddenPhase",
                        )
                    ],
                    "artifacts": [
                        artifact
                        for artifact in node.get("outputs", {}).get(
                            "artifacts",
                            [],
                        )
                        if artifact["name"] in (
                            "statusOutput",
                            "metadataOutput",
                        )
                    ],
                },
            }
    except Exception:
        logger.exception("Streaming parse failed")
        raise
    finally:
        response.close()

    slim_data = {
        "metadata": {
            "name": name,
            "resourceVersion": (
                result.get("workflow", {})
                .get("metadata", {})
                .get("resourceVersion")
            ),
        },
        "status": {
            "nodes": slim_nodes,
            "phase": result.get("phase"),
            "startedAt": result.get("started_at"),
            "finishedAt": result.get("finished_at"),
        },
    }
    return result, slim_data


def make_argo_observation_service(
    argo_url: str,
    insecure: bool,
    token: Optional[str],
) -> ArgoObservationInterface:
    return ArgoObservationInterface(
        get_workflow=lambda name, namespace: load_slim_workflow(
            WorkflowService(),
            name,
            namespace,
            argo_url=argo_url,
            token=token,
            insecure=insecure,
        ),
    )
