"""
Integration tests for workflow CLI commands using Kubernetes clusters.

These tests support three execution modes:

1. **GitHub Actions (CI)**: Uses a pre-configured Kind cluster set up by the workflow.
   The cluster is created before tests run and is automatically detected.

2. **Local with existing cluster**: Auto-detects and uses any accessible Kubernetes
   cluster (minikube, k3s, kind, etc.) configured in your kubeconfig.

3. **Local without cluster**: Falls back to creating a k3s container using
   testcontainers-python for a lightweight, isolated test environment.

The test suite automatically detects which mode to use based on cluster availability.
No manual configuration is required - the tests adapt to the environment.

## Running Tests Locally

### With existing cluster (fastest):
```bash
# Start minikube, kind, or k3s first
minikube start  # or: kind create cluster
pytest tests/workflow-tests/test_workflow_integration.py
```

### Without existing cluster (automatic fallback):
```bash
# Tests will automatically create k3s container
pytest tests/workflow-tests/test_workflow_integration.py
```

## Architecture

The cluster detection logic is implemented in helper functions:
- `_detect_existing_kubernetes_cluster()`: Checks for accessible cluster
- `_get_kubernetes_client()`: Returns configured client for existing cluster

The `k3s_container` fixture conditionally creates a k3s container only when
no existing cluster is detected, ensuring tests work in all environments.
"""

import logging
import os
import pytest
import tempfile
import time
import uuid
import requests
from click.testing import CliRunner
from kubernetes import client, config
from kubernetes.client.rest import ApiException
from testcontainers.k3s import K3SContainer
from console_link.workflow.cli import workflow_cli
from console_link.workflow.models.config import WorkflowConfig
from console_link.workflow.models.store import WorkflowConfigStore
from testcontainers.core.container import DockerContainer
from kubernetes import utils

logger = logging.getLogger(__name__)


# ============================================================================
# Kubernetes Diagnostic Helper Classes
# ============================================================================

class PodDiagnostics:
    """Encapsulates pod diagnostic information"""

    def __init__(self, pod):
        self.pod = pod
        self.name = pod.metadata.name
        self.phase = pod.status.phase

    def get_container_issues(self):
        """Returns list of container issues with reasons"""
        issues = []
        for cs in self.pod.status.container_statuses or []:
            if cs.state.waiting:
                issues.append({
                    'container': cs.name,
                    'reason': cs.state.waiting.reason or 'Unknown',
                    'message': cs.state.waiting.message or '',
                    'image': cs.image
                })
        return issues

    def has_image_pull_error(self):
        """Check if pod has image pull issues"""
        return any(
            issue['reason'] in ['ImagePullBackOff', 'ErrImagePull']
            for issue in self.get_container_issues()
        )

    def get_summary(self):
        """Get one-line summary of pod status"""
        issues = self.get_container_issues()
        if not issues:
            return f"{self.name}: {self.phase}"

        reasons = [i['reason'] for i in issues]
        return f"{self.name}: {self.phase} ({', '.join(set(reasons))})"


class K8sClusterDiagnostics:
    """Handles all Kubernetes cluster diagnostics"""

    def __init__(self, namespace):
        self.namespace = namespace
        self.v1 = client.CoreV1Api()
        self.apps_v1 = client.AppsV1Api()

    def get_deployment_status(self, deployment_name):
        """Get deployment readiness status"""
        try:
            deployment = self.apps_v1.read_namespaced_deployment(
                name=deployment_name,
                namespace=self.namespace
            )
            return {
                'ready': deployment.status.ready_replicas or 0,
                'desired': deployment.status.replicas or 0,
                'available': deployment.status.available_replicas or 0,
                'is_ready': (deployment.status.ready_replicas or 0) >= 1
            }
        except ApiException:
            return None

    def get_pod_diagnostics(self):
        """Get diagnostics for all pods in namespace"""
        pods = self.v1.list_namespaced_pod(namespace=self.namespace)
        return [PodDiagnostics(pod) for pod in pods.items]

    def get_critical_events(self, limit=10):
        """Get recent warning events"""
        try:
            events = self.v1.list_namespaced_event(
                namespace=self.namespace,
                limit=limit,
                field_selector="type=Warning"
            )
            return [
                {
                    'object': event.involved_object.name,
                    'reason': event.reason,
                    'message': event.message,
                    'time': event.last_timestamp
                }
                for event in events.items
            ]
        except Exception:
            return []

    def log_status_summary(self, logger_obj, deployments):
        """Log concise status summary"""
        logger_obj.info("\n=== Cluster Status ===")

        # Deployment status
        for dep_name in deployments:
            status = self.get_deployment_status(dep_name)
            if status:
                ready_str = f"{status['ready']}/{status['desired']}"
                logger_obj.info(f"  {dep_name}: {ready_str} ready")

        # Pod summaries
        logger_obj.info("\n  Pods:")
        for pod_diag in self.get_pod_diagnostics():
            logger_obj.info(f"    {pod_diag.get_summary()}")

        # Critical events
        events = self.get_critical_events(limit=5)
        if events:
            logger_obj.info("\n  Recent Warnings:")
            for event in events:
                logger_obj.info(f"    {event['object']}: {event['reason']}")
                if event['message']:
                    logger_obj.info(f"      {event['message']}")

    def detect_failure_reasons(self):
        """Detect specific failure patterns"""
        pod_diags = self.get_pod_diagnostics()

        # Check for image pull errors
        image_pull_errors = [p for p in pod_diags if p.has_image_pull_error()]
        if image_pull_errors:
            return {
                'type': 'ImagePullError',
                'pods': [p.name for p in image_pull_errors],
                'details': [issue for p in image_pull_errors for issue in p.get_container_issues()]
            }

        # Check for pending pods
        pending_pods = [p for p in pod_diags if p.phase == 'Pending']
        if pending_pods:
            return {
                'type': 'PodsPending',
                'pods': [p.name for p in pending_pods],
                'details': [issue for p in pending_pods for issue in p.get_container_issues()]
            }

        return None


class ArgoWorkflowsWaiter:
    """Handles waiting for Argo Workflows to be ready"""

    def __init__(self, namespace, timeout=300, check_interval=15):
        self.namespace = namespace
        self.timeout = timeout
        self.check_interval = check_interval
        self.diagnostics = K8sClusterDiagnostics(namespace)
        self.deployments = ["argo-server", "workflow-controller"]

    def wait_for_ready(self, logger_obj):
        """Wait for Argo Workflows to be ready"""
        logger_obj.info(f"Waiting for Argo Workflows (timeout: {self.timeout}s)...")

        start_time = time.time()
        last_log_time = 0

        while time.time() - start_time < self.timeout:
            elapsed = time.time() - start_time

            # Check if deployments are ready
            statuses = [
                self.diagnostics.get_deployment_status(dep)
                for dep in self.deployments
            ]

            all_ready = all(
                status and status['is_ready']
                for status in statuses
            )

            if all_ready:
                logger_obj.info(f"\n[{elapsed:.0f}s] ✓ Argo Workflows is ready!")
                return True

            # Log status periodically
            if elapsed - last_log_time >= self.check_interval:
                logger_obj.info(f"\n[{elapsed:.0f}s] Status check:")
                self.diagnostics.log_status_summary(logger_obj, self.deployments)
                last_log_time = elapsed

            time.sleep(5)

        # Timeout - provide detailed failure info
        logger_obj.error("\n❌ Argo Workflows did not become ready in time")
        self.diagnostics.log_status_summary(logger_obj, self.deployments)

        # Detect and report specific failure
        failure = self.diagnostics.detect_failure_reasons()
        if failure:
            logger_obj.error(f"\n🔍 Detected issue: {failure['type']}")
            logger_obj.error(f"   Affected pods: {', '.join(failure['pods'])}")
            for detail in failure['details']:
                logger_obj.error(f"   - {detail['container']}: {detail['reason']}")
                if detail['message']:
                    logger_obj.error(f"     {detail['message']}")
                logger_obj.error(f"     Image: {detail['image']}")

        return False


# ============================================================================
# Cluster Detection Helper Functions
# ============================================================================

def _detect_existing_kubernetes_cluster():
    """
    Detect if a Kubernetes cluster is already available and accessible.

    This function attempts to load the kubeconfig and connect to a cluster.
    It's used to determine whether to use an existing cluster (e.g., Kind in CI,
    minikube locally) or fall back to creating a k3s container.

    Returns:
        bool: True if an existing cluster is accessible, False otherwise
    """
    try:
        # Try to load kubeconfig from default location or KUBECONFIG env var
        config.load_kube_config()

        # Attempt to connect to the cluster by listing namespaces
        v1 = client.CoreV1Api()
        namespaces = v1.list_namespace(timeout_seconds=10)

        # If we got here, we have a working cluster
        logger.info("✓ Detected existing Kubernetes cluster")
        logger.info(f"  Found {len(namespaces.items)} namespaces")

        # Log cluster context for debugging
        contexts, active_context = config.list_kube_config_contexts()
        if active_context:
            cluster_name = active_context.get('context', {}).get('cluster', 'unknown')
            logger.info(f"  Active context: {active_context.get('name', 'unknown')}")
            logger.info(f"  Cluster: {cluster_name}")

        return True

    except config.ConfigException as e:
        logger.info(f"No kubeconfig found: {e}")
        return False
    except ApiException as e:
        logger.info(f"Kubernetes API error: {e}")
        return False
    except Exception as e:
        logger.info(f"Failed to connect to existing cluster: {e}")
        return False


def _get_kubernetes_client():
    """
    Get a configured Kubernetes client from an existing cluster.

    This function loads the kubeconfig and returns a CoreV1Api client.
    It should only be called after _detect_existing_kubernetes_cluster()
    has confirmed a cluster is available.

    Returns:
        client.CoreV1Api: Configured Kubernetes client, or None if unable to connect
    """
    try:
        # Load kubeconfig (should already be loaded, but ensure it's available)
        config.load_kube_config()

        # Create and return the client
        v1 = client.CoreV1Api()

        # Verify the client works
        v1.list_namespace(timeout_seconds=10)

        return v1

    except Exception as e:
        logger.error(f"Failed to create Kubernetes client: {e}")
        return None


# ============================================================================
# Test Fixtures
# ============================================================================

@pytest.fixture(scope="session")
def k3s_container():
    """
    Set up Kubernetes cluster for all workflow tests.

    This fixture supports three modes:
    1. GitHub Actions: Uses existing Kind cluster set up by the workflow
    2. Local with existing cluster: Auto-detects minikube/k3s/kind
    3. Local without cluster: Falls back to creating k3s container

    The fixture automatically detects which mode to use and configures
    the Kubernetes client accordingly.
    """
    # First, check if an existing Kubernetes cluster is available
    has_existing_cluster = _detect_existing_kubernetes_cluster()

    if has_existing_cluster:
        logger.info("\n=== Using existing Kubernetes cluster ===")
        logger.info("Skipping k3s container creation")

        # Verify we can get a working client
        k8s_client = _get_kubernetes_client()
        if k8s_client is None:
            pytest.fail("Detected existing cluster but failed to create client")

        # Yield a sentinel value to indicate we're using an existing cluster
        # The actual kubeconfig is already loaded by _detect_existing_kubernetes_cluster()
        yield {"mode": "existing-cluster", "container": None}

        # No cleanup needed for existing cluster
        logger.info("\nUsing existing cluster - no cleanup needed")

    else:
        logger.info("\n=== No existing cluster detected ===")
        logger.info("Starting k3s container for workflow tests...")

        # Start k3s container
        container = K3SContainer(image="rancher/k3s:latest")
        container.start()

        # Get kubeconfig from container
        kubeconfig = container.config_yaml()

        # Write kubeconfig to temporary file
        with tempfile.NamedTemporaryFile(mode='w', suffix='.yaml', delete=False) as f:
            f.write(kubeconfig)
            kubeconfig_path = f.name

        # Set KUBECONFIG environment variable
        os.environ['KUBECONFIG'] = kubeconfig_path

        # Load the kubeconfig
        config.load_kube_config(config_file=kubeconfig_path)

        yield {"mode": "k3s-container", "container": container}

        logger.info("\nCleaning up k3s container...")
        # Clean up
        container.stop()
        if os.path.exists(kubeconfig_path):
            os.unlink(kubeconfig_path)
        if 'KUBECONFIG' in os.environ:
            del os.environ['KUBECONFIG']


@pytest.fixture(scope="session")
def argo_workflows(k3s_container):
    """Install Argo Workflows in the k3s cluster"""
    logger.info("\nInstalling Argo Workflows in k3s...")

    # Argo Workflows version to install
    argo_version = "v3.7.3"
    argo_namespace = "argo"

    v1 = client.CoreV1Api()

    # Create argo namespace
    namespace = client.V1Namespace(
        metadata=client.V1ObjectMeta(name=argo_namespace)
    )
    try:
        v1.create_namespace(body=namespace)
        logger.info(f"Created namespace: {argo_namespace}")
    except ApiException as e:
        if e.status != 409:  # Ignore if already exists
            raise
        logger.info(f"Namespace {argo_namespace} already exists")

    # Download and apply the Argo Workflows manifest
    # Using install.yaml instead of quick-start-minimal.yaml for lighter installation
    manifest_url = (
        f"https://github.com/argoproj/argo-workflows/releases/download/"
        f"{argo_version}/quick-start-minimal.yaml"
    )

    try:
        logger.info(f"Downloading Argo Workflows manifest from {manifest_url}")
        response = requests.get(manifest_url, timeout=30)
        response.raise_for_status()
        manifest_content = response.text

        # Write manifest to temporary file
        with tempfile.NamedTemporaryFile(mode='w', suffix='.yaml', delete=False) as f:
            f.write(manifest_content)
            manifest_path = f.name

        # Apply the manifest using Kubernetes Python client
        logger.info("Applying Argo Workflows manifest...")
        k8s_client = client.ApiClient()

        try:
            utils.create_from_yaml(
                k8s_client,
                manifest_path,
                namespace=argo_namespace
            )
            logger.info("Argo Workflows manifest applied successfully")
        except Exception as apply_error:
            # Some resources might already exist, which is okay
            logger.info(f"Note during apply: {apply_error}")
            logger.info("Continuing with installation verification...")

        # Clean up temporary file
        if os.path.exists(manifest_path):
            os.unlink(manifest_path)

    except Exception as e:
        logger.info(f"Error installing Argo Workflows: {e}")
        raise

    # Use the clean waiter class to wait for Argo Workflows to be ready
    waiter = ArgoWorkflowsWaiter(argo_namespace, timeout=300, check_interval=15)

    if not waiter.wait_for_ready(logger):
        raise TimeoutError("Argo Workflows pods did not become ready in time")

    yield {
        "namespace": argo_namespace,
        "version": argo_version
    }

    # Cleanup is handled by k3s_container fixture
    logger.info("\nArgo Workflows cleanup (handled by k3s container cleanup)")


@pytest.fixture(scope="function")
def test_namespace(k3s_container):
    """Create a unique namespace for each test and clean it up afterwards"""
    # Generate a unique namespace name for this test
    namespace_name = f"test-workflow-{uuid.uuid4().hex[:8]}"

    # Create the namespace
    v1 = client.CoreV1Api()
    namespace = client.V1Namespace(
        metadata=client.V1ObjectMeta(name=namespace_name)
    )

    try:
        v1.create_namespace(body=namespace)
        logger.info(f"\nCreated test namespace: {namespace_name}")
    except ApiException as e:
        if e.status != 409:  # Ignore if already exists
            raise

    yield namespace_name

    # Clean up the namespace after the test
    logger.info(f"\nDeleting test namespace: {namespace_name}")
    try:
        v1.delete_namespace(name=namespace_name)
    except ApiException as e:
        # Ignore errors during cleanup
        logger.warning(f"Failed to delete namespace {namespace_name}: {e}")


@pytest.fixture
def k8s_workflow_store(test_namespace):
    """Create a WorkflowConfigStore connected to k3s Kubernetes in the test namespace"""
    try:
        # Create a Kubernetes client using the already loaded configuration
        # The kubeconfig from k3s already has admin permissions
        v1 = client.CoreV1Api()

        # Verify the connection is working before creating the store
        try:
            v1.list_namespace()
        except Exception as e:
            pytest.skip(f"Kubernetes connection lost: {e}")

        # Create the WorkflowConfigStore with the pre-configured client for the test namespace
        store = WorkflowConfigStore(
            namespace=test_namespace,
            config_map_prefix="workflow-test",
            k8s_client=v1
        )

        # Clean up any existing test ConfigMaps in this namespace
        try:
            config_maps = v1.list_namespaced_config_map(
                namespace=test_namespace,
                label_selector="app=migration-assistant,component=workflow-config"
            )
            for cm in config_maps.items:
                if cm.metadata.name.startswith("workflow-test"):
                    v1.delete_namespaced_config_map(name=cm.metadata.name, namespace=test_namespace)
        except ApiException:
            pass

        yield store
    except Exception as e:
        pytest.skip(f"Failed to create WorkflowConfigStore: {e}")


@pytest.fixture
def runner():
    """CLI runner for testing"""
    return CliRunner()


@pytest.fixture
def sample_workflow_config():
    """Sample workflow configuration for testing"""
    data = {
        "targets": {
            "production": {
                "endpoint": "https://target-os.example.com:9200",
                "auth": {
                    "username": "admin",
                    "password": "test-password"
                },
                "allow_insecure": False
            }
        },
        "source-migration-configurations": [
            {
                "source": {
                    "endpoint": "https://source-es.example.com:9200",
                    "auth": {
                        "username": "admin",
                        "password": "test-password"
                    },
                    "allow_insecure": True
                }
            }
        ]
    }

    return WorkflowConfig(data)


@pytest.mark.slow
class TestWorkflowCLICommands:
    """Integration tests for workflow CLI commands using real k3s"""

    def test_workflow_help(self, runner):
        """Test workflow help command"""
        result = runner.invoke(workflow_cli, ['--help'])
        assert result.exit_code == 0
        assert "Workflow-based migration management CLI" in result.output
        assert "configure" in result.output
        assert "util" in result.output

    def test_workflow_configure_view_no_config(self, runner, k8s_workflow_store):
        """Test workflow configure view when no config exists"""
        session_name = "default"

        # Clean up any existing test data
        try:
            k8s_workflow_store.delete_config(session_name)
        except ApiException:
            pass  # Ignore if doesn't exist

        result = runner.invoke(workflow_cli, ['configure', 'view'], obj={
                               'store': k8s_workflow_store, 'namespace': k8s_workflow_store.namespace})

        assert result.exit_code == 0
        assert "No configuration found" in result.output

    def test_workflow_configure_view_existing_config(self, runner, k8s_workflow_store, sample_workflow_config):
        """Test workflow configure view with existing config"""
        session_name = "default"

        # Clean up any existing test data
        try:
            k8s_workflow_store.delete_config(session_name)
        except ApiException:
            pass  # Ignore if doesn't exist

        # Create a test config
        data = {
            "targets": {
                "test": {
                    "endpoint": "https://test.com:9200",
                    "auth": {
                        "username": "admin",
                        "password": "password"
                    }
                }
            }
        }
        config = WorkflowConfig(data)

        # Save config to k3s
        message = k8s_workflow_store.save_config(config, session_name)
        assert "created" in message or "updated" in message

        result = runner.invoke(workflow_cli, ['configure', 'view'], obj={
                               'store': k8s_workflow_store, 'namespace': k8s_workflow_store.namespace})

        assert result.exit_code == 0
        assert "targets:" in result.output
        assert "test:" in result.output
        # Parse YAML output to verify structure
        import yaml as yaml_parser
        config_data = yaml_parser.safe_load(result.output)
        endpoint = config_data['targets']['test']['endpoint']
        assert endpoint == "https://test.com:9200"

        # Cleanup
        try:
            k8s_workflow_store.delete_config(session_name)
        except ApiException:
            pass

    def test_workflow_configure_clear_with_confirmation(self, runner, k8s_workflow_store, sample_workflow_config):
        """Test workflow configure clear with confirmation"""
        session_name = "default"

        # Create a config to clear
        message = k8s_workflow_store.save_config(sample_workflow_config, session_name)
        assert "created" in message or "updated" in message

        result = runner.invoke(workflow_cli, ['configure', 'clear', '--confirm'],
                               obj={'store': k8s_workflow_store, 'namespace': k8s_workflow_store.namespace})

        assert result.exit_code == 0
        assert f"Cleared workflow configuration for session: {session_name}" in result.output

        # Verify config was cleared (should be empty)
        config = k8s_workflow_store.load_config(session_name)
        # Config should exist but be empty
        assert config is not None
        assert config.data == {}

    def test_workflow_configure_edit_with_stdin_json(self, runner, k8s_workflow_store):
        """Test workflow configure edit with JSON input from stdin"""
        session_name = "default"

        # Clean up any existing test data
        try:
            k8s_workflow_store.delete_config(session_name)
        except ApiException:
            pass  # Ignore if doesn't exist

        # Prepare JSON input
        json_input = '{"targets": {"test": {"endpoint": "https://test.com:9200"}}}'

        result = runner.invoke(workflow_cli, ['configure', 'edit', '--stdin'], input=json_input,
                               obj={'store': k8s_workflow_store, 'namespace': k8s_workflow_store.namespace})

        assert result.exit_code == 0
        assert "Configuration" in result.output

        # Verify config was saved
        config = k8s_workflow_store.load_config(session_name)
        assert config is not None
        assert config.get("targets")["test"]["endpoint"] == "https://test.com:9200"

        # Cleanup
        try:
            k8s_workflow_store.delete_config(session_name)
        except ApiException:
            pass

    def test_workflow_configure_edit_with_stdin_yaml(self, runner, k8s_workflow_store):
        """Test workflow configure edit with YAML input from stdin"""
        session_name = "default"

        # Clean up any existing test data
        try:
            k8s_workflow_store.delete_config(session_name)
        except ApiException:
            pass  # Ignore if doesn't exist

        # Prepare YAML input
        yaml_input = """targets:
  test:
    endpoint: https://test.com:9200
    auth:
      username: admin
      password: password
"""

        result = runner.invoke(workflow_cli, ['configure', 'edit', '--stdin'], input=yaml_input,
                               obj={'store': k8s_workflow_store, 'namespace': k8s_workflow_store.namespace})

        assert result.exit_code == 0
        assert "Configuration" in result.output

        # Verify config was saved
        config = k8s_workflow_store.load_config(session_name)
        assert config is not None
        assert config.get("targets")["test"]["endpoint"] == "https://test.com:9200"
        assert config.get("targets")["test"]["auth"]["username"] == "admin"

        # Cleanup
        try:
            k8s_workflow_store.delete_config(session_name)
        except ApiException:
            pass

    def test_workflow_configure_edit_with_stdin_empty(self, runner, k8s_workflow_store):
        """Test workflow configure edit with empty stdin input"""
        result = runner.invoke(workflow_cli, ['configure', 'edit', '--stdin'], input='',
                               obj={'store': k8s_workflow_store, 'namespace': k8s_workflow_store.namespace})

        assert result.exit_code != 0
        assert "Configuration was empty, a value is required" in result.output

    def test_workflow_configure_edit_with_stdin_invalid(self, runner, k8s_workflow_store):
        """Test workflow configure edit with invalid stdin input"""
        invalid_input = "this is not valid JSON or YAML: {{{["

        result = runner.invoke(workflow_cli, ['configure', 'edit', '--stdin'], input=invalid_input,
                               obj={'store': k8s_workflow_store, 'namespace': k8s_workflow_store.namespace})

        assert result.exit_code != 0
        assert "Failed to parse input" in result.output

    def test_workflow_util_completions_bash(self, runner):
        """Test workflow util completions for bash"""
        result = runner.invoke(workflow_cli, ['util', 'completions', 'bash'])
        assert result.exit_code == 0
        assert "_workflow_completion" in result.output.lower() or "complete" in result.output.lower()

    def test_workflow_util_completions_zsh(self, runner):
        """Test workflow util completions for zsh"""
        result = runner.invoke(workflow_cli, ['util', 'completions', 'zsh'])
        assert result.exit_code == 0
        assert "compdef" in result.output or "#compdef" in result.output

    def test_workflow_util_completions_fish(self, runner):
        """Test workflow util completions for fish"""
        result = runner.invoke(workflow_cli, ['util', 'completions', 'fish'])
        assert result.exit_code == 0
        assert "complete" in result.output


@pytest.mark.slow
class TestArgoWorkflows:
    """Integration tests for Argo Workflows installation in k3s"""

    def test_argo_workflows_installation(self, argo_workflows):
        """Test that Argo Workflows is properly installed in k3s"""
        argo_namespace = argo_workflows["namespace"]
        argo_version = argo_workflows["version"]

        logger.info(f"\nVerifying Argo Workflows {argo_version} installation in namespace {argo_namespace}")

        v1 = client.CoreV1Api()
        apps_v1 = client.AppsV1Api()

        # Verify argo namespace exists
        namespaces = v1.list_namespace()
        namespace_names = [ns.metadata.name for ns in namespaces.items]
        assert argo_namespace in namespace_names, f"Argo namespace {argo_namespace} not found"
        logger.info(f"✓ Namespace {argo_namespace} exists")

        # Verify argo-server deployment exists and is ready
        server_deployment = apps_v1.read_namespaced_deployment(
            name="argo-server",
            namespace=argo_namespace
        )
        assert server_deployment is not None, "argo-server deployment not found"
        assert server_deployment.status.ready_replicas >= 1, "argo-server deployment not ready"
        logger.info(f"✓ argo-server deployment is ready ({server_deployment.status.ready_replicas} replicas)")

        # Verify workflow-controller deployment exists and is ready
        controller_deployment = apps_v1.read_namespaced_deployment(
            name="workflow-controller",
            namespace=argo_namespace
        )
        assert controller_deployment is not None, "workflow-controller deployment not found"
        assert controller_deployment.status.ready_replicas >= 1, "workflow-controller deployment not ready"
        logger.info(
            "✓ workflow-controller deployment is ready (%s replicas)",
            controller_deployment.status.ready_replicas
        )

        # Verify argo-server service exists
        services = v1.list_namespaced_service(namespace=argo_namespace)
        service_names = [svc.metadata.name for svc in services.items]
        assert "argo-server" in service_names, "argo-server service not found"
        logger.info("✓ argo-server service exists")

        # Verify pods are running
        pods = v1.list_namespaced_pod(namespace=argo_namespace)
        running_pods = [pod for pod in pods.items if pod.status.phase == "Running"]
        assert len(running_pods) >= 2, f"Expected at least 2 running pods, found {len(running_pods)}"
        logger.info(f"✓ Found {len(running_pods)} running pods in {argo_namespace} namespace")

        for pod in running_pods:
            logger.info(f"  - {pod.metadata.name}: {pod.status.phase}")

        logger.info(f"\n✓ Argo Workflows {argo_version} is successfully installed and running!")

    def test_workflow_submit_hello_world(self, argo_workflows):
        """Test submitting a hello-world workflow to Argo via Kubernetes API with output verification"""
        argo_namespace = argo_workflows["namespace"]

        logger.info(f"\nTesting workflow submission to Argo in namespace {argo_namespace}")

        # Create unique message for this test
        test_message = f"hello world from test {uuid.uuid4().hex[:8]}"

        # Create workflow specification as a Kubernetes custom resource with output parameter
        workflow_spec = {
            "apiVersion": "argoproj.io/v1alpha1",
            "kind": "Workflow",
            "metadata": {
                "generateName": "test-hello-world-",
                "namespace": argo_namespace,
                "labels": {
                    "workflows.argoproj.io/completed": "false"
                }
            },
            "spec": {
                # Use default service account which has executor role bound in quickstart
                # The executor role grants permission to create workflowtaskresults
                "templates": [
                    {
                        "name": "hello-world",
                        "outputs": {
                            "parameters": [
                                {
                                    "name": "message",
                                    "valueFrom": {
                                        "path": "/tmp/message.txt"
                                    }
                                }
                            ]
                        },
                        "container": {
                            "image": "busybox",
                            "command": ["sh", "-c"],
                            "args": [f'echo "{test_message}" | tee /tmp/message.txt']
                        }
                    }
                ],
                "entrypoint": "hello-world"
            }
        }

        # Submit workflow using Kubernetes API
        custom_api = client.CustomObjectsApi()

        try:
            logger.info("Submitting workflow via Kubernetes API...")

            # Create the workflow custom resource
            result = custom_api.create_namespaced_custom_object(
                group="argoproj.io",
                version="v1alpha1",
                namespace=argo_namespace,
                plural="workflows",
                body=workflow_spec
            )

            workflow_name = result.get("metadata", {}).get("name")
            workflow_uid = result.get("metadata", {}).get("uid")

            assert workflow_name is not None, "Workflow name not returned"
            assert workflow_name.startswith("test-hello-world-"), f"Unexpected workflow name: {workflow_name}"
            assert workflow_uid is not None, "Workflow UID not returned"

            logger.info("✓ Workflow submitted successfully!")
            logger.info(f"  Name: {workflow_name}")
            logger.info(f"  UID: {workflow_uid}")

            # Wait for workflow to complete
            logger.info("Waiting for workflow to complete...")
            max_wait = 60  # 60 seconds timeout
            start_time = time.time()
            workflow_phase = "Unknown"

            while time.time() - start_time < max_wait:
                workflow = custom_api.get_namespaced_custom_object(
                    group="argoproj.io",
                    version="v1alpha1",
                    namespace=argo_namespace,
                    plural="workflows",
                    name=workflow_name
                )

                workflow_phase = workflow.get("status", {}).get("phase", "Unknown")
                logger.info(f"  Workflow phase: {workflow_phase}")

                # Check if workflow reached a terminal state
                if workflow_phase in ["Succeeded", "Failed", "Error"]:
                    break

                time.sleep(2)

            assert workflow is not None, "Workflow not found in Kubernetes"
            assert workflow["metadata"]["name"] == workflow_name
            logger.info("✓ Workflow verified in Kubernetes")

            # If workflow failed or errored, get detailed information
            if workflow_phase in ["Failed", "Error"]:
                logger.error(f"Workflow ended in {workflow_phase} phase")

                # Get workflow status message
                status_message = workflow.get("status", {}).get("message", "No message available")
                logger.error(f"Status message: {status_message}")

                # Get node details to understand what failed
                nodes = workflow.get("status", {}).get("nodes", {})
                logger.error(f"Workflow has {len(nodes)} nodes")

                for node_id, node in nodes.items():
                    node_name = node.get("displayName", node.get("name", "unknown"))
                    node_phase = node.get("phase", "unknown")
                    node_message = node.get("message", "")
                    node_type = node.get("type", "unknown")

                    logger.error(f"\nNode: {node_name}")
                    logger.error(f"  ID: {node_id}")
                    logger.error(f"  Type: {node_type}")
                    logger.error(f"  Phase: {node_phase}")
                    if node_message:
                        logger.error(f"  Message: {node_message}")

                    # Try to get pod logs if this is a pod node
                    if node_type == "Pod":
                        try:
                            v1 = client.CoreV1Api()
                            pod_name = node.get("id", node_id)
                            logger.error(f"  Attempting to get logs for pod: {pod_name}")

                            # Get pod logs
                            logs = v1.read_namespaced_pod_log(
                                name=pod_name,
                                namespace=argo_namespace,
                                tail_lines=50
                            )
                            logger.error(f"  Pod logs:\n{logs}")
                        except Exception as log_error:
                            logger.error(f"  Could not retrieve pod logs: {log_error}")

            # Verify workflow succeeded
            assert workflow_phase == "Succeeded", f"Workflow did not succeed, phase: {workflow_phase}"
            logger.info(f"✓ Workflow completed successfully with phase: {workflow_phase}")

            # Extract and verify output parameter
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

            assert output_message is not None, "Could not retrieve workflow output"
            assert test_message in output_message, \
                f"Output doesn't match expected message. Expected: '{test_message}', Got: '{output_message}'"

            logger.info(f"✓ Container output verified: {output_message}")
            logger.info("✓ Output verification successful - container executed correctly!")

        except ApiException as e:
            pytest.fail(f"Failed to submit workflow via Kubernetes API: {e}")


def test_k3s_container_support():
    """Test that k3s container support is available"""
    try:
        # Just verify the import works
        assert DockerContainer is not None
    except ImportError:
        pytest.skip("testcontainers not installed - run: pip install testcontainers")
