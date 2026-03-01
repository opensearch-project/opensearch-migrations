import ijson
import json
import requests
import threading

from dataclasses import dataclass
from typing import Any, Callable, Dict, List

from console_link.workflow.services.workflow_service import WorkflowApproveResult, logger, WorkflowService
from console_link.workflow.tree_utils import clean_display_name


@dataclass
class WaiterInterface:
    trigger: Callable[[], None]
    checker: Callable[[], bool]
    reset: Callable[[], None]

    @classmethod
    def default(cls, workflow_name: str, namespace: str,
                argo_url: str, insecure: bool = False, token: str = None) -> "WaiterInterface":
        _stop = threading.Event()
        _ready_signal = threading.Event()
        _thread = [None]

        def _poll_argo_api():
            """Poll Argo REST API to detect workflow creation."""
            headers = {"Authorization": f"Bearer {token}"} if token else {}
            url = f"{argo_url}/api/v1/workflows/{namespace}/{workflow_name}"

            while not _stop.is_set():
                try:
                    resp = requests.get(url, headers=headers, verify=not insecure, timeout=10)
                    if resp.status_code == 200:
                        logger.info(f"Workflow {workflow_name} found via Argo API.")
                        _ready_signal.set()
                        return
                except Exception as e:
                    logger.debug(f"Argo API poll error: {e}")

                # Interruptible sleep — returns early if _stop is set
                _stop.wait(timeout=2)

        def trigger():
            if not _ready_signal.is_set():
                # Don't spawn if a thread is already polling
                if _thread[0] and _thread[0].is_alive():
                    return
                logger.debug("Starting background wait thread.")
                _stop.clear()
                _ready_signal.clear()
                _thread[0] = threading.Thread(target=_poll_argo_api, daemon=True, name="argo_api_poll")
                _thread[0].start()

        def reset():
            _stop.set()
            _ready_signal.clear()

        return cls(
            trigger=trigger,
            checker=lambda: _ready_signal.is_set(),
            reset=reset
        )


@dataclass
class ArgoWorkflowInterface:
    # This must return an immutable copy of the dictionary
    get_workflow: Callable[[str, str], tuple[str, dict]]
    approve_step: Callable[[str, str, dict], WorkflowApproveResult]


def _build_slim_node(node_id: str, node: dict) -> dict:
    """Extract only the fields needed for tree rendering from a workflow node."""
    return {
        "id": node_id,
        "displayName": clean_display_name(node.get("displayName") or node_id),
        "phase": node.get("phase"),
        "type": node.get("type"),
        "boundaryID": node.get("boundaryID"),
        "children": node.get("children", []),
        "startedAt": node.get("startedAt"),
        "finishedAt": node.get("finishedAt"),
        "inputs": {"parameters": [p for p in node.get("inputs", {}).get("parameters", []) if
                                  p.get('name') in ('groupName', 'configContents', 'name')]},
        "outputs": {"parameters": [p for p in node.get("outputs", {}).get("parameters", []) if
                                   p.get('name') in ('statusOutput', 'overriddenPhase')]}
    }


def make_argo_service(argo_url: str, insecure: bool, token: str) -> ArgoWorkflowInterface:
    def _get_workflow_data_internal(service, name, namespace) -> tuple[str, dict]:
        res = service.get_workflow_status(name, namespace, argo_url, token, insecure)
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        url = f"{argo_url}/api/v1/workflows/{namespace}/{name}"

        resp = requests.get(url, headers=headers, verify=not insecure, stream=True, timeout=30)

        slim_nodes = {}
        resource_version = None
        if resp.status_code == 200:
            try:
                parser = ijson.kvitems(resp.raw, 'status.nodes')
                for node_id, node in parser:
                    slim_nodes[node_id] = _build_slim_node(node_id, node)
            except Exception as e:
                logger.error(f"Streaming parse failed: {e}")
                raise
            finally:
                resp.close()
        else:
            resp.close()
            # Fall back to archive API
            archived = service.fetch_archived_workflow(name, namespace, argo_url, token, insecure)
            if archived:
                resource_version = archived.get("metadata", {}).get("resourceVersion")
                for node_id, node in archived.get("status", {}).get("nodes", {}).items():
                    slim_nodes[node_id] = _build_slim_node(node_id, node)
            else:
                raise Exception(f"Workflow {name} not found in live or archive API")

        slim_data = {
            "metadata": {"name": name,
                         "resourceVersion": resource_version},
            "status": {
                "nodes": slim_nodes,
                "startedAt": res.get('started_at')
            }
        }

        return res, slim_data

    def approve(namespace: str, workflow_name: str, node_data: dict) -> WorkflowApproveResult:
        return WorkflowService().approve_workflow(workflow_name, namespace, argo_url, token, insecure,
                                                  f"id={node_data.get('id')}")

    return ArgoWorkflowInterface(
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
        if use_cache:
            query_params.append(('resourceVersion', '0'))
        try:
            # use call_api to bypass the V1Pod object creation, which is much slower
            resp = k8s_client.api_client.call_api(
                f'/api/v1/namespaces/{ns}/pods', 'GET',
                header_params={'Accept': 'application/json;as=PartialObjectMetadataList;'
                                         'v=v1;g=meta.k8s.io'},  # Use headers to request ONLY metadata
                query_params=query_params, _preload_content=False, _request_timeout=10,
                auth_settings=['BearerToken']
            )
            data = json.loads(resp[0].read())
            return data.get('items', []) or []  # with PartialObjectMetadataList, items could be null
        except Exception as e:
            logger.error(f"Failed to fetch pod metadata: {e}")
            raise
        finally:
            if 'resp' in locals():
                resp[0].close()

    return PodScraperInterface(
        fetch_pods_metadata=fetch_metadata,
        read_pod=lambda name, ns: k8s_client.read_namespaced_pod(name=name, namespace=ns),
        read_pod_log=lambda name, ns, c, lines:
            k8s_client.read_namespaced_pod_log(name, ns, container=c, tail_lines=lines)
    )
