import atexit
import json
import requests
import subprocess
import threading

from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, TypedDict

from console_link.workflow.commands.approve import approve_gate
from console_link.workflow.services.argo_observation_service import (
    load_slim_workflow,
)
from console_link.workflow.services.workflow_service import logger, WorkflowService


class WorkflowApproveResult(TypedDict):
    success: bool
    workflow_name: str
    namespace: str
    message: str
    error: Optional[str]


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
        _wait_thread: List[Optional[threading.Thread]] = [None]
        _generation = [0]
        _stop_signal = threading.Event()

        def cleanup_subprocess():
            """Kill kubectl processes safely using the lock."""
            _running.clear()
            _stop_signal.set()
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

        def reset():
            _ready_signal.clear()
            _generation[0] += 1
            cleanup_subprocess()
            wait_thread = _wait_thread[0]
            if wait_thread and wait_thread is not threading.current_thread() and wait_thread.is_alive():
                wait_thread.join(timeout=0.5)

        atexit.register(cleanup_subprocess)

        def run_kubectl_wait_loop(thread_generation: int):
            while _running.is_set() and _generation[0] == thread_generation:
                cmd = [
                    "kubectl", "wait", f"workflow/{workflow_name}",
                    "--for=create", "-n", namespace, "--timeout=300s"
                ]

                proc: Optional[subprocess.Popen] = None

                # Ensure we don't start a process if cleanup is happening (or about to)
                with _lock:  # CRITICAL SECTION:
                    if not _running.is_set() or _generation[0] != thread_generation:
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

                    if _generation[0] != thread_generation:
                        return

                    if exit_code == 0:
                        logger.info(f"Kubectl wait {proc.pid} succeeded.")
                        _running.clear()
                        _ready_signal.set()
                        return

                    if not _running.is_set() or _generation[0] != thread_generation:
                        return

                    if _stop_signal.wait(timeout=2):
                        return

                except Exception:
                    logger.exception("Caught exception while waiting for kubectl process")
                    if not _running.is_set() or _generation[0] != thread_generation:
                        break
                    if _stop_signal.wait(timeout=2):
                        break

        def trigger():
            with _lock:
                if not _running.is_set():
                    logger.debug("Starting background wait thread.")
                    _ready_signal.clear()
                    _stop_signal.clear()
                    _running.set()
                    thread_generation = _generation[0]
                    _wait_thread[0] = threading.Thread(
                        target=run_kubectl_wait_loop,
                        args=(thread_generation,),
                        daemon=True,
                        name="run_kubectl_wait_loop",
                    )
                    _wait_thread[0].start()

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
    get_artifact_content: Callable[[str, str, str, str], Optional[str]] = field(
        default=lambda *_: None
    )


def make_argo_service(argo_url: str, insecure: bool, token: str) -> ArgoWorkflowInterface:
    def _get_workflow_data_internal(service, name, namespace) -> tuple[str, dict]:
        return load_slim_workflow(
            service,
            name,
            namespace,
            argo_url=argo_url,
            token=token,
            insecure=insecure,
            request_get=requests.get,
        )

    def approve(namespace: str, workflow_name: str, node_data: dict) -> WorkflowApproveResult:
        gate_name = None
        for param in node_data.get("inputs", {}).get("parameters", []):
            if param.get("name") in ("resourceName", "name"):
                gate_name = param.get("value")
                break

        if not gate_name:
            return WorkflowApproveResult(
                success=False,
                workflow_name=workflow_name,
                namespace=namespace,
                message="Could not determine approval gate name for this node",
                error="missing approval gate name",
            )

        if approve_gate(namespace, gate_name):
            return WorkflowApproveResult(
                success=True,
                workflow_name=workflow_name,
                namespace=namespace,
                message=f"Approved {gate_name}",
                error=None,
            )

        return WorkflowApproveResult(
            success=False,
            workflow_name=workflow_name,
            namespace=namespace,
            message=f"Failed to approve {gate_name}",
            error=f"failed to approve {gate_name}",
        )

    return ArgoWorkflowInterface(
        get_workflow=lambda name, namespace: _get_workflow_data_internal(WorkflowService(), name, namespace),
        approve_step=approve,
        get_artifact_content=lambda workflow_name, node_id, artifact_name, namespace: WorkflowService()
        .get_artifact_content(
            workflow_name=workflow_name,
            node_id=node_id,
            artifact_name=artifact_name,
            namespace=namespace,
            argo_server=argo_url,
            token=token,
            insecure=insecure,
        )
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
