"""Service layer for workflow operations.

This module provides a service layer that encapsulates all workflow business logic,
making it reusable by CLI commands, REST APIs, or other interfaces.
"""

import logging
import time
from typing import Dict, Any, Optional, Tuple, TypedDict, List

import requests
from kubernetes import client


logger = logging.getLogger(__name__)

# Terminal workflow phases
ENDING_PHASES = ["Succeeded", "Failed", "Error", "Stopped", "Terminated"]

# Constants
FALLBACK_MESSAGE = "Falling back to default hello-world workflow"
CONTENT_TYPE_JSON = "application/json"


class WorkflowTemplateResult(TypedDict):
    """Result of template loading operation."""
    success: bool
    workflow_spec: Dict[str, Any]
    source: str
    error: Optional[str]


class WorkflowSubmitResult(TypedDict):
    """Result of workflow submission operation."""
    success: bool
    workflow_name: str
    workflow_uid: str
    namespace: str
    phase: Optional[str]
    output_message: Optional[str]
    error: Optional[str]


class WorkflowStopResult(TypedDict):
    """Result of workflow stop operation."""
    success: bool
    workflow_name: str
    namespace: str
    message: str
    error: Optional[str]


class WorkflowApproveResult(TypedDict):
    """Result of workflow approve/resume operation."""
    success: bool
    workflow_name: str
    namespace: str
    message: str
    error: Optional[str]


class WorkflowListResult(TypedDict):
    """Result of workflow list operation."""
    success: bool
    workflows: List[str]
    count: int
    error: Optional[str]


class WorkflowNode(TypedDict):
    """Represents a workflow node with its relationships."""
    id: str
    name: str
    display_name: str
    phase: str
    type: str
    started_at: Optional[str]
    finished_at: Optional[str]
    boundary_id: Optional[str]
    children: List[str]
    parent: Optional[str]
    depth: int


class WorkflowStatusResult(TypedDict):
    """Result of workflow status operation."""
    success: bool
    workflow_name: str
    namespace: str
    phase: str
    progress: str
    started_at: Optional[str]
    finished_at: Optional[str]
    steps: List[Dict[str, str]]  # Keep for backward compatibility
    step_tree: List[WorkflowNode]  # NEW: Hierarchical representation
    error: Optional[str]


class WorkflowService:
    """Service class for workflow operations.

    This class encapsulates all workflow business logic including:
    - Template loading from file system or environment
    - Parameter injection from configuration
    - Workflow submission to Argo
    - Workflow status monitoring

    The service is stateless and can be reused by any interface (CLI, API, scripts).
    """

    def __init__(self):
        """Initialize the WorkflowService."""

    def submit_workflow_to_argo(
        self,
        workflow_spec: Dict[str, Any],
        namespace: str,
        argo_server: str,
        token: Optional[str] = None,
        insecure: bool = False
    ) -> WorkflowSubmitResult:
        """Submit workflow to Argo Workflows via REST API.

        Args:
            workflow_spec: Complete workflow specification
            namespace: Kubernetes namespace
            argo_server: Argo Server URL
            token: Bearer token for authentication
            insecure: Whether to skip TLS verification

        Returns:
            WorkflowSubmitResult dict with success status, workflow_name, workflow_uid, and error
        """
        try:
            # Prepare the request body
            request_body = {
                "namespace": namespace,
                "serverDryRun": False,
                "workflow": workflow_spec
            }

            # Ensure namespace is set in workflow metadata
            if 'metadata' not in workflow_spec:
                request_body['workflow']['metadata'] = {}
            request_body['workflow']['metadata']['namespace'] = namespace

            # Prepare headers
            headers = {
                "Content-Type": CONTENT_TYPE_JSON
            }

            if token:
                headers["Authorization"] = f"Bearer {token}"

            # Submit the workflow
            url = f"{argo_server}/api/v1/workflows/{namespace}"

            logger.info(f"Submitting workflow to {url}")
            logger.debug(f"Workflow spec: {workflow_spec}")

            response = requests.post(
                url,
                json=request_body,
                headers=headers,
                verify=not insecure
            )

            response.raise_for_status()

            # Parse response
            result = response.json()
            workflow_name = result.get("metadata", {}).get("name", "unknown")
            workflow_uid = result.get("metadata", {}).get("uid", "unknown")

            logger.info(f"Workflow {workflow_name} submitted successfully")

            return WorkflowSubmitResult(
                success=True,
                workflow_name=workflow_name,
                workflow_uid=workflow_uid,
                namespace=namespace,
                phase=None,
                output_message=None,
                error=None
            )

        except requests.exceptions.RequestException as e:
            error_msg = f"Failed to submit workflow: {e}"
            logger.error(error_msg)

            if hasattr(e, 'response') and e.response is not None:
                try:
                    error_detail = e.response.json()
                    error_msg = f"Failed to submit workflow: {error_detail}"
                except Exception:
                    error_msg = f"Failed to submit workflow: {e.response.text}"

            return WorkflowSubmitResult(
                success=False,
                workflow_name="",
                workflow_uid="",
                namespace=namespace,
                phase=None,
                output_message=None,
                error=error_msg
            )

        except Exception as e:
            error_msg = f"Unexpected error submitting workflow: {e}"
            logger.exception(error_msg)

            return WorkflowSubmitResult(
                success=False,
                workflow_name="",
                workflow_uid="",
                namespace=namespace,
                phase=None,
                output_message=None,
                error=error_msg
            )

    def list_workflows(
        self,
        namespace: str,
        argo_server: str,
        token: Optional[str] = None,
        insecure: bool = False,
        exclude_completed: bool = False,
        phase_filter: Optional[str] = None
    ) -> WorkflowListResult:
        """List workflows in a namespace via Argo Workflows REST API.

        Args:
            namespace: Kubernetes namespace
            argo_server: Argo Server URL
            token: Bearer token for authentication
            insecure: Whether to skip TLS verification
            exclude_completed: Only return running workflows
            phase_filter: Filter by specific phase

        Returns:
            WorkflowListResult dict with success status, workflows list, count, and error
        """
        try:
            headers = self._prepare_headers(token)
            url = f"{argo_server}/api/v1/workflows/{namespace}"

            logger.info(f"Listing workflows in namespace {namespace} (exclude_completed={exclude_completed})")
            logger.debug(f"List request URL: {url}")

            response = requests.get(url, headers=headers, verify=not insecure)

            if response.status_code == 200:
                result = response.json()
                items = result.get("items", [])
                workflow_names = self._filter_workflows(items, exclude_completed, phase_filter)

                logger.info(f"Found {len(workflow_names)} workflows in namespace {namespace}")
                return WorkflowListResult(
                    success=True,
                    workflows=workflow_names,
                    count=len(workflow_names),
                    error=None
                )
            else:
                error_msg = self._format_error_message("list workflows", response)
                logger.error(error_msg)
                return WorkflowListResult(
                    success=False,
                    workflows=[],
                    count=0,
                    error=error_msg
                )

        except requests.exceptions.RequestException as e:
            error_msg = f"Network error listing workflows: {e}"
            logger.error(error_msg)
            return WorkflowListResult(
                success=False,
                workflows=[],
                count=0,
                error=str(e)
            )

        except Exception as e:
            error_msg = f"Unexpected error listing workflows: {e}"
            logger.exception(error_msg)
            return WorkflowListResult(
                success=False,
                workflows=[],
                count=0,
                error=str(e)
            )

    def get_workflow_status(
        self,
        workflow_name: str,
        namespace: str,
        argo_server: str,
        token: Optional[str] = None,
        insecure: bool = False
    ) -> WorkflowStatusResult:
        """Get detailed status of a specific workflow.

        Args:
            workflow_name: Name of the workflow
            namespace: Kubernetes namespace
            argo_server: Argo Server URL
            token: Optional bearer token for authentication
            insecure: Whether to skip TLS verification

        Returns:
            WorkflowStatusResult with detailed status information
        """
        try:
            headers = self._prepare_headers(token)
            url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"

            logger.info(f"Getting status for workflow {workflow_name}")
            logger.debug(f"Request URL: {url}")

            response = requests.get(url, headers=headers, verify=not insecure)

            if response.status_code != 200:
                error_msg = self._format_error_message("get workflow status", response)
                logger.error(error_msg)
                return self._create_error_status_result(workflow_name, namespace, error_msg)

            workflow = response.json()
            status = workflow.get("status", {})

            phase = status.get("phase", "Unknown")
            progress = status.get("progress", "0/0")
            started_at = status.get("startedAt")
            finished_at = status.get("finishedAt")

            nodes_dict = status.get("nodes", {})
            steps = self._extract_workflow_steps(nodes_dict)

            # Build hierarchical tree structure
            step_tree = self._build_workflow_tree(nodes_dict)
            step_tree = self._sort_nodes_intelligently(step_tree)

            return WorkflowStatusResult(
                success=True,
                workflow_name=workflow_name,
                namespace=namespace,
                phase=phase,
                progress=progress,
                started_at=started_at,
                finished_at=finished_at,
                steps=steps,
                step_tree=step_tree,
                error=None
            )

        except Exception as e:
            error_msg = f"Error getting workflow status: {e}"
            logger.error(error_msg)
            return self._create_error_status_result(workflow_name, namespace, error_msg)

    def stop_workflow(
        self,
        workflow_name: str,
        namespace: str,
        argo_server: str,
        token: Optional[str] = None,
        insecure: bool = False
    ) -> WorkflowStopResult:
        """Stop a running workflow via Argo Workflows REST API.

        Args:
            workflow_name: Name of the workflow to stop
            namespace: Kubernetes namespace
            argo_server: Argo Server URL
            token: Bearer token for authentication
            insecure: Whether to skip TLS verification

        Returns:
            WorkflowStopResult dict with success status, message, and error
        """
        try:
            headers = self._prepare_headers(token)

            # Construct URL for stop endpoint
            url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}/stop"

            logger.info(f"Stopping workflow {workflow_name} in namespace {namespace}")
            logger.debug(f"Stop request URL: {url}")

            # Make PUT request to stop the workflow
            response = requests.put(
                url,
                headers=headers,
                verify=not insecure
            )

            # Handle response
            if response.status_code == 200:
                logger.info(f"Workflow {workflow_name} stopped successfully")
                return WorkflowStopResult(
                    success=True,
                    workflow_name=workflow_name,
                    namespace=namespace,
                    message=f"Workflow {workflow_name} stopped successfully",
                    error=None
                )
            elif response.status_code == 404:
                error_msg = f"Workflow {workflow_name} not found in namespace {namespace}"
                logger.error(error_msg)
                return WorkflowStopResult(
                    success=False,
                    workflow_name=workflow_name,
                    namespace=namespace,
                    message=error_msg,
                    error=error_msg
                )
            else:
                error_msg = f"Failed to stop workflow: HTTP {response.status_code}"
                try:
                    error_detail = response.json()
                    error_msg = f"Failed to stop workflow: {error_detail}"
                except Exception:
                    error_msg = f"Failed to stop workflow: {response.text}"

                logger.error(error_msg)
                return WorkflowStopResult(
                    success=False,
                    workflow_name=workflow_name,
                    namespace=namespace,
                    message=error_msg,
                    error=error_msg
                )

        except requests.exceptions.RequestException as e:
            error_msg = f"Network error stopping workflow: {e}"
            logger.error(error_msg)

            return WorkflowStopResult(
                success=False,
                workflow_name=workflow_name,
                namespace=namespace,
                message=error_msg,
                error=str(e)
            )

        except Exception as e:
            error_msg = f"Unexpected error stopping workflow: {e}"
            logger.exception(error_msg)

            return WorkflowStopResult(
                success=False,
                workflow_name=workflow_name,
                namespace=namespace,
                message=error_msg,
                error=str(e)
            )

    def approve_workflow(
        self,
        workflow_name: str,
        namespace: str,
        argo_server: str,
        token: Optional[str] = None,
        insecure: bool = False
    ) -> WorkflowApproveResult:
        """Approve/resume a suspended workflow via Argo Workflows REST API.

        Args:
            workflow_name: Name of the workflow to approve
            namespace: Kubernetes namespace
            argo_server: Argo Server URL
            token: Bearer token for authentication
            insecure: Whether to skip TLS verification

        Returns:
            WorkflowApproveResult dict with success status, message, and error
        """
        try:
            headers = self._prepare_headers(token)

            # Construct URL for resume endpoint
            url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}/resume"

            logger.info(f"Resuming workflow {workflow_name} in namespace {namespace}")
            logger.debug(f"Resume request URL: {url}")

            # Make PUT request to resume the workflow
            response = requests.put(
                url,
                headers=headers,
                verify=not insecure
            )

            # Handle response
            if response.status_code == 200:
                logger.info(f"Workflow {workflow_name} resumed successfully")
                return WorkflowApproveResult(
                    success=True,
                    workflow_name=workflow_name,
                    namespace=namespace,
                    message=f"Workflow {workflow_name} resumed successfully",
                    error=None
                )
            elif response.status_code == 404:
                error_msg = f"Workflow {workflow_name} not found in namespace {namespace}"
                logger.error(error_msg)
                return WorkflowApproveResult(
                    success=False,
                    workflow_name=workflow_name,
                    namespace=namespace,
                    message=error_msg,
                    error=error_msg
                )
            else:
                error_msg = f"Failed to resume workflow: HTTP {response.status_code}"
                try:
                    error_detail = response.json()
                    error_msg = f"Failed to resume workflow: {error_detail}"
                except Exception:
                    error_msg = f"Failed to resume workflow: {response.text}"

                logger.error(error_msg)
                return WorkflowApproveResult(
                    success=False,
                    workflow_name=workflow_name,
                    namespace=namespace,
                    message=error_msg,
                    error=error_msg
                )

        except requests.exceptions.RequestException as e:
            error_msg = f"Network error resuming workflow: {e}"
            logger.error(error_msg)

            return WorkflowApproveResult(
                success=False,
                workflow_name=workflow_name,
                namespace=namespace,
                message=error_msg,
                error=str(e)
            )

        except Exception as e:
            error_msg = f"Unexpected error resuming workflow: {e}"
            logger.exception(error_msg)

            return WorkflowApproveResult(
                success=False,
                workflow_name=workflow_name,
                namespace=namespace,
                message=error_msg,
                error=str(e)
            )

    def _prepare_headers(self, token: Optional[str] = None) -> Dict[str, str]:
        """Prepare HTTP headers for API requests.

        Args:
            token: Optional bearer token for authentication

        Returns:
            Dictionary of HTTP headers
        """
        headers = {"Content-Type": CONTENT_TYPE_JSON}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        return headers

    def _format_error_message(self, operation: str, response) -> str:
        """Format error message from HTTP response.

        Args:
            operation: Description of the operation that failed
            response: HTTP response object

        Returns:
            Formatted error message
        """
        error_msg = f"Failed to {operation}: HTTP {response.status_code}"
        try:
            error_detail = response.json()
            error_msg = f"Failed to {operation}: {error_detail}"
        except Exception:
            error_msg = f"Failed to {operation}: {response.text}"
        return error_msg

    def _filter_workflows(
        self,
        items: List[Dict[str, Any]],
        exclude_completed: bool,
        phase_filter: Optional[str]
    ) -> List[str]:
        """Filter workflow items based on criteria.

        Args:
            items: List of workflow items from API
            exclude_completed: Whether to exclude completed workflows
            phase_filter: Optional phase to filter by

        Returns:
            List of workflow names matching criteria
        """
        workflow_names = []
        # Handle case when items is None (no workflows exist)
        if items is None:
            return workflow_names

        for item in items:
            name = item.get("metadata", {}).get("name", "")
            if not name:
                continue

            phase = item.get("status", {}).get("phase", "Unknown")

            if exclude_completed and phase in ENDING_PHASES:
                continue

            if phase_filter == 'Running':
                if phase != 'Running':
                    continue
                if not self._has_active_suspend(item):
                    continue
            elif phase_filter and phase != phase_filter:
                continue

            workflow_names.append(name)
        return workflow_names

    def _has_active_suspend(self, workflow_item: Dict[str, Any]) -> bool:
        """Check if workflow has an active suspend node.

        Args:
            workflow_item: Workflow item from API

        Returns:
            True if workflow has active suspend node
        """
        nodes = workflow_item.get("status", {}).get("nodes", {})
        for node in nodes.values():
            if node.get("type") == "Suspend" and node.get("phase") == "Running":
                return True
        return False

    def _build_workflow_tree(self, nodes: Dict[str, Any]) -> List[WorkflowNode]:
        """Build a tree structure from Argo workflow nodes.

        Args:
            nodes: Dictionary of workflow nodes from Argo API

        Returns:
            List of WorkflowNode objects representing the tree structure
        """
        # First pass: Create WorkflowNode objects for all relevant nodes
        workflow_nodes: Dict[str, WorkflowNode] = {}

        for node_id, node in nodes.items():
            node_type = node.get("type", "")
            # Include Pod and Suspend steps, plus Skipped steps to show conditional logic
            if node_type in ["Pod", "Suspend", "Skipped"]:
                workflow_nodes[node_id] = WorkflowNode(
                    id=node_id,
                    name=node.get("name", ""),
                    display_name=node.get("displayName", ""),
                    phase=node.get("phase", "Unknown"),
                    type=node_type,
                    started_at=node.get("startedAt"),
                    finished_at=node.get("finishedAt"),
                    boundary_id=node.get("boundaryID"),
                    children=node.get("children", []),
                    parent=None,
                    depth=0
                )

        # Second pass: Establish parent-child relationships
        for node_id, wf_node in workflow_nodes.items():
            # Set parent based on boundaryID
            if wf_node["boundary_id"] and wf_node["boundary_id"] in workflow_nodes:
                wf_node["parent"] = wf_node["boundary_id"]

        # Third pass: Calculate depth levels
        def calculate_depth(node_id: str, visited: set) -> int:
            """Recursively calculate depth for a node."""
            if node_id in visited:
                return 0  # Prevent infinite recursion
            visited.add(node_id)

            node = workflow_nodes.get(node_id)
            if not node:
                return 0

            parent_id = node["parent"]
            if not parent_id or parent_id not in workflow_nodes:
                return 0

            return 1 + calculate_depth(parent_id, visited)

        for node_id in workflow_nodes:
            workflow_nodes[node_id]["depth"] = calculate_depth(node_id, set())

        # Return as list (will be sorted by _sort_nodes_intelligently)
        return list(workflow_nodes.values())

    def _sort_nodes_intelligently(self, nodes: List[WorkflowNode]) -> List[WorkflowNode]:
        """Sort nodes considering both temporal and structural order.

        Args:
            nodes: List of WorkflowNode objects

        Returns:
            Sorted list of WorkflowNode objects
        """
        # Group nodes by depth level
        nodes_by_depth: Dict[int, List[WorkflowNode]] = {}
        for node in nodes:
            depth = node["depth"]
            if depth not in nodes_by_depth:
                nodes_by_depth[depth] = []
            nodes_by_depth[depth].append(node)

        # Sort nodes at each depth level by start time
        for depth in nodes_by_depth:
            nodes_by_depth[depth].sort(
                key=lambda x: (
                    x.get("started_at") or "9999-99-99",  # Primary: start time
                    x.get("finished_at") or "9999-99-99"  # Secondary: finish time
                )
            )

        # Build final sorted list respecting depth hierarchy
        sorted_nodes = []
        for depth in sorted(nodes_by_depth.keys()):
            sorted_nodes.extend(nodes_by_depth[depth])

        return sorted_nodes

    def _extract_workflow_steps(self, nodes: Dict[str, Any]) -> List[Dict[str, str]]:
        """Extract step information from workflow nodes.

        Args:
            nodes: Dictionary of workflow nodes

        Returns:
            List of step dictionaries with name, phase, type, and started_at
        """
        steps = []
        for node in nodes.values():
            node_type = node.get("type", "")
            # Include Pod and Suspend steps, plus Skipped steps to show conditional logic
            if node_type in ["Pod", "Suspend", "Skipped"]:
                steps.append({
                    "name": node.get("displayName", ""),
                    "phase": node.get("phase", "Unknown"),
                    "type": node_type,
                    "started_at": node.get("startedAt", "")
                })

        # Sort chronologically by start time
        steps.sort(key=lambda x: x.get("started_at", "9999-99-99"))
        return steps

    def _create_error_status_result(
        self,
        workflow_name: str,
        namespace: str,
        error_msg: str
    ) -> WorkflowStatusResult:
        """Create an error status result.

        Args:
            workflow_name: Name of the workflow
            namespace: Kubernetes namespace
            error_msg: Error message

        Returns:
            WorkflowStatusResult with error information
        """
        return WorkflowStatusResult(
            success=False,
            workflow_name=workflow_name,
            namespace=namespace,
            phase="Unknown",
            progress="0/0",
            started_at=None,
            finished_at=None,
            steps=[],
            step_tree=[],
            error=error_msg
        )

    def wait_for_workflow_completion(
        self,
        namespace: str,
        workflow_name: str,
        timeout: int = 120,
        interval: int = 2
    ) -> Tuple[str, Optional[str]]:
        """Wait for workflow to reach terminal state and retrieve output.

        Args:
            namespace: Kubernetes namespace
            workflow_name: Name of workflow to monitor
            timeout: Maximum seconds to wait
            interval: Seconds between status checks

        Returns:
            Tuple of (phase, output_message)

        Raises:
            TimeoutError: If timeout is exceeded
        """
        start_time = time.time()
        custom_api = client.CustomObjectsApi()

        while time.time() - start_time < timeout:
            try:
                # Get workflow status
                workflow = custom_api.get_namespaced_custom_object(
                    group="argoproj.io",
                    version="v1alpha1",
                    namespace=namespace,
                    plural="workflows",
                    name=workflow_name
                )

                phase = workflow.get("status", {}).get("phase", "Unknown")
                elapsed = int(time.time() - start_time)

                logger.debug(f"[{elapsed}s] Workflow {workflow_name} phase: {phase}")

                # Check if workflow reached a terminal state
                if phase in ENDING_PHASES:
                    # Extract output parameter
                    output_message = None
                    nodes = workflow.get("status", {}).get("nodes", {})

                    for node_id, node in nodes.items():
                        outputs = node.get("outputs", {})
                        parameters = outputs.get("parameters", [])

                        for param in parameters:
                            if param.get("name") == "message":
                                output_message = param.get("value", "").strip()
                                break

                        if output_message:
                            break

                    logger.info(f"Workflow {workflow_name} completed with phase: {phase}")
                    return phase, output_message

                # Wait before next check
                time.sleep(interval)

            except Exception as e:
                logger.error(f"Error checking workflow status: {e}")
                raise

        # Timeout reached
        error_msg = f"Workflow {workflow_name} did not complete within {timeout} seconds"
        logger.error(error_msg)
        raise TimeoutError(error_msg)
