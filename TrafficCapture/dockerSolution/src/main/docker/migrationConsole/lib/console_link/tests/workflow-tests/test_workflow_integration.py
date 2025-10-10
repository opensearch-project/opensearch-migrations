"""
Integration tests for workflow CLI commands using real k3s test containers.

These tests use testcontainers-python with k3s to provide a lightweight
Kubernetes environment for testing.
"""

import logging
import os
import pytest
import tempfile
import uuid
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
    print("\nStarting k3s container for workflow tests...")

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

    print("\nCleaning up k3s container...")
    # Clean up
    container.stop()
    if os.path.exists(kubeconfig_path):
        os.unlink(kubeconfig_path)
    if 'KUBECONFIG' in os.environ:
        del os.environ['KUBECONFIG']


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
        print(f"\nCreated test namespace: {namespace_name}")
    except ApiException as e:
        if e.status != 409:  # Ignore if already exists
            raise

    yield namespace_name

    # Clean up the namespace after the test
    print(f"\nDeleting test namespace: {namespace_name}")
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


def test_k3s_container_support():
    """Test that k3s container support is available"""
    try:
        # Just verify the import works
        assert DockerContainer is not None
    except ImportError:
        pytest.skip("testcontainers not installed - run: pip install testcontainers")
