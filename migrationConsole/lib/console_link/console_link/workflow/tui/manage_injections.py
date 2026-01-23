import atexit
import ijson
import json
import requests
import subprocess
import threading

from dataclasses import dataclass
from typing import Any, Callable, Dict, List, Optional

from console_link.workflow.services.workflow_service import WorkflowApproveResult, logger, WorkflowService
from console_link.workflow.tree_utils import clean_display_name


@dataclass
class WaiterInterface:
    trigger: Callable[[], None]
    checker: Callable[[], bool]
    reset: Callable[[], None]

    @classmethod
    def default(cls, workflow_name: str, namespace: str) -> "WaiterInterface":
        _running = threading.Event()
        _ready_signal = threading.Event()
        _active_process: List[subprocess.Popen] = []
        _lock = threading.Lock()  # The gatekeeper for process management

        def cleanup_subprocess():
            """Kill kubectl processes safely using the lock."""
            with _lock:
                for p in _active_process:
                    if p.poll() is None:
                        try:
                            logger.info(f"Terminating kubectl process: {p.pid}...")
                            p.terminate()
                            p.wait(timeout=0.2)
                        except Exception:
                            p.kill()
                _active_process.clear()

        atexit.register(cleanup_subprocess)

        def run_kubectl_wait_loop():
            while _running.is_set():
                cmd = [
                    "kubectl", "wait", f"workflow/{workflow_name}",
                    "--for=create", "-n", namespace, "--timeout=300s"
                ]

                proc: Optional[subprocess.Popen] = None

                # Ensure we don't start a process if cleanup is happening (or about to)
                with _lock:  # CRITICAL SECTION:
                    if not _running.is_set():
                        logger.debug("Spawn aborted: waiter is stopping.")
                        return

                    try:
                        proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                        logger.info(f"Tracking process {proc.pid} as kubectl wait...")
                        _active_process.append(proc)
                    except Exception as e:
                        logger.error(f"Failed to spawn kubectl: {e}")
                        _running.clear()
                        return

                try:
                    exit_code = proc.wait()

                    with _lock:
                        if proc in _active_process:
                            _active_process.remove(proc)

                    if exit_code == 0:
                        logger.info(f"Kubectl wait {proc.pid} succeeded.")
                        _running.clear()
                        _ready_signal.set()
                        return

                except Exception:
                    logger.exception("Caught exception while waiting for kubectl process")
                    # Use event.wait for a interruptible sleep
                    if not _running.wait(timeout=2):
                        break

        def trigger():
            with _lock:
                if not _running.is_set():
                    logger.debug("Starting background wait thread.")
                    _ready_signal.clear()
                    _running.set()
                    threading.Thread(target=run_kubectl_wait_loop, daemon=True, name="run_kubectl_wait_loop").start()

        return cls(
            trigger=trigger,
            checker=lambda: _ready_signal.is_set(),
            reset=lambda: _ready_signal.clear()
        )


@dataclass
class ArgoWorkflowInterface:
    # This must return an immutable copy of the dictionary
    get_workflow: Callable[[str, str], tuple[str, dict]]
    approve_step: Callable[[str, str, dict], WorkflowApproveResult]


def make_argo_service(argo_url: str, insecure: bool, token: str) -> ArgoWorkflowInterface:
    def _get_workflow_data_internal(service, name, namespace) -> tuple[str, dict]:
        res = service.get_workflow_status(name, namespace, argo_url, token, insecure)
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        url = f"{argo_url}/api/v1/workflows/{namespace}/{name}"

        resp = requests.get(url, headers=headers, verify=not insecure, stream=True)
        if resp.status_code != 200:
            raise requests.HTTPError(f"Request failed with status {resp.status_code}")

        slim_nodes = {}
        try:
            parser = ijson.kvitems(resp.raw, 'status.nodes')
            for node_id, node in parser:
                # Only keep what is actually rendered in tree_utils
                slim_nodes[node_id] = {
                    "id": node_id,
                    "displayName": clean_display_name(node.get("displayName") or node_id),
                    # no reason to keep the massive version
                    "phase": node.get("phase"),
                    "type": node.get("type"),
                    "boundaryID": node.get("boundaryID"),
                    "children": node.get("children", []),
                    "startedAt": node.get("startedAt"),
                    "finishedAt": node.get("finishedAt"),
                    # Only keep inputs/outputs if they contain specific UI keys
                    "inputs": {"parameters": [p for p in node.get("inputs", {}).get("parameters", []) if
                                              p['name'] in ('groupName', 'configContents', 'name')]},
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
