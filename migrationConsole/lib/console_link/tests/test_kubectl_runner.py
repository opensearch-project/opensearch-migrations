import pathlib
from types import SimpleNamespace

import pytest

from console_link.models.command_result import CommandResult
from console_link.models.kubectl_runner import KubectlRunner
from kubernetes import config
from kubernetes.client import V1Secret

TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"

TEST_NAMESPACE = "test"
TEST_DEPLOYMENT_NAME = "ma-test-deployment"


@pytest.fixture
def kubectl_runner(monkeypatch):
    # Prevent actual config loading
    monkeypatch.setattr(config, "load_incluster_config", lambda: None)
    monkeypatch.setattr(config, "load_kube_config", lambda: None)
    instance = KubectlRunner(namespace=TEST_NAMESPACE, deployment_name=TEST_DEPLOYMENT_NAME)
    return instance


def test_perform_scale_command_success(kubectl_runner):
    def mock_patch_namespaced_deployment_scale(name, namespace, body):
        return None

    kubectl_runner.k8s_apps.patch_namespaced_deployment_scale = mock_patch_namespaced_deployment_scale
    result = kubectl_runner.perform_scale_command(replicas=3)

    assert isinstance(result, CommandResult)
    assert result.success is True


def test_perform_scale_command_failure(kubectl_runner):
    def mock_patch_namespaced_deployment_scale(name, namespace, body):
        raise Exception("Scale error")

    kubectl_runner.k8s_apps.patch_namespaced_deployment_scale = mock_patch_namespaced_deployment_scale
    result = kubectl_runner.perform_scale_command(replicas=3)

    assert isinstance(result, CommandResult)
    assert result.success is False


def test_retrieve_deployment_status_success(kubectl_runner):
    pod1 = SimpleNamespace(status=SimpleNamespace(phase="Running"),
                           metadata=SimpleNamespace(deletion_timestamp=None))
    pod2 = SimpleNamespace(status=SimpleNamespace(phase="Pending"),
                           metadata=SimpleNamespace(deletion_timestamp=None))
    pods = SimpleNamespace(items=[pod1, pod2])

    def mock_list_namespaced_pod(namespace, label_selector):
        return pods
    kubectl_runner.k8s_core.list_namespaced_pod = mock_list_namespaced_pod

    def mock_read_namespaced_deployment(name, namespace):
        return SimpleNamespace(spec=SimpleNamespace(replicas=2))
    kubectl_runner.k8s_apps.read_namespaced_deployment = mock_read_namespaced_deployment

    status = kubectl_runner.retrieve_deployment_status()
    assert status is not None
    assert status.running == 1
    assert status.pending == 1
    assert status.terminating == 0
    assert status.desired == 2


def test_retrieve_deployment_status_terminating(kubectl_runner):
    pod1 = SimpleNamespace(status=SimpleNamespace(phase="Running"),
                           metadata=SimpleNamespace(deletion_timestamp="1234:5678"))
    pods = SimpleNamespace(items=[pod1])

    def mock_list_namespaced_pod(namespace, label_selector):
        return pods
    kubectl_runner.k8s_core.list_namespaced_pod = mock_list_namespaced_pod

    def mock_read_namespaced_deployment(name, namespace):
        return SimpleNamespace(spec=SimpleNamespace(replicas=0))
    kubectl_runner.k8s_apps.read_namespaced_deployment = mock_read_namespaced_deployment

    status = kubectl_runner.retrieve_deployment_status()
    assert status is not None
    assert status.running == 0
    assert status.pending == 0
    assert status.terminating == 1
    assert status.desired == 0


def test_retrieve_deployment_status_failure_pod_list(kubectl_runner):
    def mock_list_namespaced_pod(namespace, label_selector):
        raise Exception("Pod list error")
    kubectl_runner.k8s_core.list_namespaced_pod = mock_list_namespaced_pod

    status = kubectl_runner.retrieve_deployment_status()
    assert status is None


def test_retrieve_deployment_status_failure_deployment_read(kubectl_runner):
    pod1 = SimpleNamespace(status=SimpleNamespace(phase="Running"),
                           metadata=SimpleNamespace(deletion_timestamp="1234:5678"))
    pods = SimpleNamespace(items=[pod1])

    def mock_list_namespaced_pod(namespace, label_selector):
        return pods
    kubectl_runner.k8s_core.list_namespaced_pod = mock_list_namespaced_pod

    def mock_read_namespaced_deployment(name, namespace):
        raise Exception("Read deployment error")
    kubectl_runner.k8s_apps.read_namespaced_deployment = mock_read_namespaced_deployment

    status = kubectl_runner.retrieve_deployment_status()
    assert status is None


def test_read_secret(kubectl_runner):
    import base64

    def mock_read_namespaced_secret(name, namespace):
        return V1Secret(data={"key": base64.b64encode(b"value").decode("utf-8")})
    kubectl_runner.k8s_core.read_namespaced_secret = mock_read_namespaced_secret

    secret = kubectl_runner.read_secret("test-secret")
    assert secret is not None
    assert secret == {"key": "value"}
