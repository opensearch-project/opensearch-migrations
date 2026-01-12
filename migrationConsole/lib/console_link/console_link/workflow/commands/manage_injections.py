import json
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, List, Dict, Any, Optional

import ijson
import requests

from console_link.workflow.commands.approve import approve_command
from console_link.workflow.services.workflow_service import WorkflowApproveResult, logger, WorkflowService
from console_link.workflow.tree_utils import clean_display_name


@dataclass
class WaiterInterface:
    trigger: Callable[[], None]
    checker: Callable[[], bool]
    reset: Callable[[], None]
    marker_file: Optional[Path] = None

    @classmethod
    def default(cls, workflow_name: str, namespace: str) -> "WaiterInterface":
        marker = Path(tempfile.gettempdir()) / f"wf_ready_{workflow_name}.tmp"

        def trigger():
            if marker.exists():
                try:
                    marker.unlink()
                except Exception:
                    pass
            cmd = (
                f"kubectl wait workflow/{workflow_name} "
                f"--for=create -n {namespace} --timeout=300s && touch {marker}"
            )
            subprocess.Popen(cmd, shell=True, start_new_session=True)

        def reset():
            if marker.exists():
                try:
                    marker.unlink()
                except Exception:
                    pass

        return cls(
            trigger=trigger,
            checker=lambda: marker.exists(),
            reset=reset,
            marker_file=marker
        )

@dataclass
class ArgoService:
    get_workflow: Callable[[str, str], tuple[str, dict]]
    approve_step: Callable[[str, str, dict], WorkflowApproveResult]

def make_argo_service(argo_url: str, insecure: bool, token: str) -> ArgoService:
    def _get_workflow_data_internal(service, name, namespace) -> tuple[str, dict]:
        res = service.get_workflow_status(name, namespace, argo_url, token, insecure)
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        url = f"{argo_url}/api/v1/workflows/{namespace}/{name}"

        resp = requests.get(url, headers=headers, verify=not insecure, stream=True)
        if resp.status_code != 200:
            raise requests.HTTPError(f"Request failed with status {resp.status_code}")

        slim_nodes = {}
        node_count = 0
        try:
            parser = ijson.kvitems(resp.raw, 'status.nodes')
            for node_id, node in parser:
                node_count += 1
                # Only keep what is actually rendered in tree_utils
                slim_nodes[node_id] = {
                    "id": node_id,
                    "displayName": clean_display_name(node.get("displayName")),
                    # no reason to keep the massive version
                    "phase": node.get("phase"),
                    "type": node.get("type"),
                    "boundaryID": node.get("boundaryID"),
                    "children": node.get("children", []),
                    "startedAt": node.get("startedAt"),
                    "finishedAt": node.get("finishedAt"),
                    # Only keep inputs/outputs if they contain specific UI keys
                    "inputs": {"parameters": [p for p in node.get("inputs", {}).get("parameters", []) if
                                              p['name'] in ('groupName', 'configContents')]},
                    "outputs": {"parameters": [p for p in node.get("outputs", {}).get("parameters", []) if
                                               p['name'] in ('statusOutput', 'overriddenPhase')]}
                }
        except Exception as e:
            logger.error(f"Streaming parse failed: {e}")
            raise
        finally:
            resp.close()

        slim_data = {
            "metadata": {"name": name,
                         "resourceVersion": res.get('workflow', {}).get('metadata', {}).get('resourceVersion')},
            "status": {
                "nodes": slim_nodes,
                "startedAt": res.get('workflow', {}).get('status', {}).get('startedAt')
            }
        }

        return res, slim_data

    def approve(namespace: str, workflow_name: str, node_data: dict) -> WorkflowApproveResult:
        return WorkflowService().approve_workflow(workflow_name, namespace, argo_url, token, insecure,
                                           f"id={node_data.get('id')}")

    return ArgoService(
        get_workflow=lambda name, namespace: _get_workflow_data_internal(WorkflowService(), name, namespace),
        approve_step=approve
    )

@dataclass
class PodScraperInterface:
    fetch_pods_metadata: Callable[[str, str, bool], List[Dict]]
    read_pod: Callable[[str, str], Any]
    read_pod_log: Callable[[str, str, str, int], str]

def make_k8s_pod_scraper(k8s_client) -> PodScraperInterface:
    def fetch_metadata(wf_name, ns, use_cache):
        """High-performance fetch of pod metadata for a specific workflow."""
        query_params = [('labelSelector', f"workflows.argoproj.io/workflow={wf_name}")]

        # Hybrid Consistency: resourceVersion=0 hits the API cache (fast).
        # Omitting it forces a strongly consistent read from etcd (safe/slow).
        if use_cache: query_params.append(('resourceVersion', '0'))
        try:
            # use call_api to bypass the V1Pod object creation, which is much slower
            resp = k8s_client.api_client.call_api(
                f'/api/v1/namespaces/{ns}/pods', 'GET',
                # Use headers to request ONLY metadata (strips spec and status)
                header_params={'Accept': 'application/json;as=PartialObjectMetadataList;v=v1;g=meta.k8s.io'},
                query_params=query_params, _preload_content=False, _request_timeout=10
            )
            data = json.loads(resp[0].read())
            return data.get('items', [])
        except Exception as e:
            logger.error(f"Failed to fetch pod metadata: {e}")
            return []
        finally:
            if 'resp' in locals(): resp[0].close()

    return PodScraperInterface(
        fetch_pods_metadata=fetch_metadata,
        read_pod=lambda name, ns: k8s_client.read_namespaced_pod(name=name, namespace=ns),
        read_pod_log=lambda name, ns, c, lines: \
            k8s_client.read_namespaced_pod_log(name, ns, container=c, tail_lines=lines)
    )
