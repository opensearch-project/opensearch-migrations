"""
Integration tests for workflow CLI commands using real k3s test containers.

These tests use testcontainers-python with k3s to provide a lightweight
Kubernetes environment for testing.
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

logger = logging.getLogger(__name__)


@pytest.fixture(scope="session")
def k3s_container():
    """Set up k3s container for all workflow tests"""
    logger.info("\nStarting k3s container for workflow tests...")

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

    yield container

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
    argo_version = "v3.5.12"
    argo_namespace = "argo"

    v1 = client.CoreV1Api()
    apps_v1 = client.AppsV1Api()

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
        f"{argo_version}/install.yaml"
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
        from kubernetes import utils
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

    # Wait for Argo Workflows pods to be ready
    logger.info("Waiting for Argo Workflows pods to be ready...")
    max_wait_time = 300  # 5 minutes
    start_time = time.time()
    check_interval = 5
    last_status_log = 0

    while time.time() - start_time < max_wait_time:
        elapsed = time.time() - start_time
        
        try:
            # Check if argo-server deployment is ready
            server_deployment = apps_v1.read_namespaced_deployment(
                name="argo-server",
                namespace=argo_namespace
            )

            # Check if workflow-controller deployment is ready
            controller_deployment = apps_v1.read_namespaced_deployment(
                name="workflow-controller",
                namespace=argo_namespace
            )

            server_ready = (
                server_deployment.status.ready_replicas is not None and
                server_deployment.status.ready_replicas >= 1
            )

            controller_ready = (
                controller_deployment.status.ready_replicas is not None and
                controller_deployment.status.ready_replicas >= 1
            )

            # Log detailed status every 15 seconds
            if elapsed - last_status_log >= 15:
                logger.info(f"\n[{elapsed:.0f}s] Deployment Status Check:")
                logger.info(f"  argo-server: {server_deployment.status.ready_replicas or 0}/"
                           f"{server_deployment.status.replicas or 0} ready, "
                           f"{server_deployment.status.available_replicas or 0} available, "
                           f"{server_deployment.status.unavailable_replicas or 0} unavailable")
                logger.info(f"  workflow-controller: {controller_deployment.status.ready_replicas or 0}/"
                           f"{controller_deployment.status.replicas or 0} ready, "
                           f"{controller_deployment.status.available_replicas or 0} available, "
                           f"{controller_deployment.status.unavailable_replicas or 0} unavailable")
                
                # Get pod status details
                pods = v1.list_namespaced_pod(namespace=argo_namespace)
                logger.info(f"  Total pods in namespace: {len(pods.items)}")
                
                for pod in pods.items:
                    pod_name = pod.metadata.name
                    phase = pod.status.phase
                    
                    # Get container statuses
                    container_statuses = []
                    if pod.status.container_statuses:
                        for cs in pod.status.container_statuses:
                            state = "Unknown"
                            reason = ""
                            if cs.state.running:
                                state = "Running"
                            elif cs.state.waiting:
                                state = "Waiting"
                                reason = cs.state.waiting.reason or ""
                            elif cs.state.terminated:
                                state = "Terminated"
                                reason = cs.state.terminated.reason or ""
                            
                            container_statuses.append(f"{cs.name}:{state}{':'+reason if reason else ''}")
                    
                    logger.info(f"    - {pod_name}: {phase} [{', '.join(container_statuses) if container_statuses else 'no containers'}]")
                
                # Get recent events for debugging
                try:
                    events = v1.list_namespaced_event(
                        namespace=argo_namespace,
                        limit=10,
                        field_selector="type=Warning"
                    )
                    if events.items:
                        logger.info("  Recent Warning Events:")
                        for event in events.items[:5]:  # Show last 5 warnings
                            logger.info(f"    - {event.involved_object.name}: {event.reason} - {event.message}")
                except Exception as e:
                    logger.debug(f"Could not fetch events: {e}")
                
                last_status_log = elapsed

            if server_ready and controller_ready:
                logger.info(f"\n[{elapsed:.0f}s] ✓ Argo Workflows is ready!")
                break

        except ApiException as e:
            # Log API exceptions during deployment checks
            if elapsed - last_status_log >= 15:
                logger.info(f"[{elapsed:.0f}s] Deployments not yet available: {e.reason}")
                last_status_log = elapsed

        time.sleep(check_interval)
    else:
        # Argo failed to start - print logs for debugging
        logger.error("Argo Workflows pods did not become ready in time")
        logger.error("Printing container logs for debugging:")
        
        try:
            # Get all pods in the argo namespace
            pods = v1.list_namespaced_pod(namespace=argo_namespace)
            
            for pod in pods.items:
                pod_name = pod.metadata.name
                logger.error(f"\n{'='*80}")
                logger.error(f"Logs for pod: {pod_name}")
                logger.error(f"Status: {pod.status.phase}")
                logger.error(f"{'='*80}")
                
                # Get logs for each container in the pod
                if pod.spec.containers:
                    for container in pod.spec.containers:
                        container_name = container.name
                        try:
                            logger.error(f"\nContainer: {container_name}")
                            logger.error("-" * 80)
                            logs = v1.read_namespaced_pod_log(
                                name=pod_name,
                                namespace=argo_namespace,
                                container=container_name,
                                tail_lines=100  # Last 100 lines
                            )
                            logger.error(logs)
                        except ApiException as log_error:
                            logger.error(f"Failed to get logs for container {container_name}: {log_error}")
                
                # Also check init containers if they exist
                if pod.spec.init_containers:
                    for init_container in pod.spec.init_containers:
                        container_name = init_container.name
                        try:
                            logger.error(f"\nInit Container: {container_name}")
                            logger.error("-" * 80)
                            logs = v1.read_namespaced_pod_log(
                                name=pod_name,
                                namespace=argo_namespace,
                                container=container_name,
                                tail_lines=100  # Last 100 lines
                            )
                            logger.error(logs)
                        except ApiException as log_error:
                            logger.error(f"Failed to get logs for init container {container_name}: {log_error}")
                
                logger.error("")  # Empty line between pods
                
        except Exception as e:
            logger.error(f"Failed to retrieve pod logs: {e}")
        
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

    def test_workflow_configure_view_json_format(self, runner, k8s_workflow_store):
        """Test workflow configure view with JSON format"""
        session_name = "default"

        # Clean up any existing test data
        try:
            k8s_workflow_store.delete_config(session_name)
        except ApiException:
            pass  # Ignore if doesn't exist

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

        result = runner.invoke(workflow_cli, ['configure', 'view', '--format', 'json'],
                               obj={'store': k8s_workflow_store, 'namespace': k8s_workflow_store.namespace})

        assert result.exit_code == 0
        assert '"targets"' in result.output
        assert '"test"' in result.output

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
