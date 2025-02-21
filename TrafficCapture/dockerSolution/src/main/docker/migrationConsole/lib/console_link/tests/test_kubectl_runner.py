import pathlib
import subprocess

from console_link.models.command_result import CommandResult
from console_link.models.kubectl_runner import KubectlRunner
from console_link.models.utils import DeploymentStatus

TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"

TEST_NAMESPACE = "test"
TEST_DEPLOYMENT_NAME = "ma-test-deployment"


def test_kubectl_runner_perform_scale(mocker):
    kubectl_runner = KubectlRunner(TEST_NAMESPACE, TEST_DEPLOYMENT_NAME)

    mock = mocker.patch("subprocess.run")

    desired_count = 0
    cmd_result: CommandResult = kubectl_runner.perform_scale_command(desired_count)

    mock.assert_called_once_with([
        'kubectl',
        "-n", TEST_NAMESPACE,
        "scale",
        "deployment",
        TEST_DEPLOYMENT_NAME,
        "--replicas", str(desired_count),
    ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)

    assert cmd_result.success is True


def test_kubectl_runner_perform_scale_command_error(mocker):
    kubectl_runner = KubectlRunner(TEST_NAMESPACE, TEST_DEPLOYMENT_NAME)

    mock = mocker.patch("subprocess.run", side_effect=subprocess.CalledProcessError(cmd="Test command", returncode=1))

    desired_count = 0
    cmd_result: CommandResult = kubectl_runner.perform_scale_command(desired_count)

    mock.assert_called_once_with([
        'kubectl',
        "-n", TEST_NAMESPACE,
        "scale",
        "deployment",
        TEST_DEPLOYMENT_NAME,
        "--replicas", str(desired_count),
    ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)

    assert cmd_result.success is False


def test_kubectl_retrieve_deployment_status(mocker):
    kubectl_runner = KubectlRunner(TEST_NAMESPACE, TEST_DEPLOYMENT_NAME)
    with open(TEST_DATA_DIRECTORY / "kubectl_describe_deployment_output.json") as f:
        json_output = f.read()
    mock_subprocess_result = subprocess.CompletedProcess(args=[], returncode=0, stdout=json_output, stderr=None)
    mock = mocker.patch("subprocess.run", return_value=mock_subprocess_result)

    deployment_status = kubectl_runner.retrieve_deployment_status()

    mock.assert_called_once_with([
        'kubectl',
        "-n", TEST_NAMESPACE,
        "get",
        "deployment",
        TEST_DEPLOYMENT_NAME,
        "-o", "json",
    ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)

    assert deployment_status == DeploymentStatus(running=1, pending=0, desired=1)


def test_kubectl_retrieve_deployment_status_improper_json(mocker):
    kubectl_runner = KubectlRunner(TEST_NAMESPACE, TEST_DEPLOYMENT_NAME)
    json_output = "{\"spec\": []}"
    mock_subprocess_result = subprocess.CompletedProcess(args=[], returncode=0, stdout=json_output, stderr=None)
    mock = mocker.patch("subprocess.run", return_value=mock_subprocess_result)

    deployment_status = kubectl_runner.retrieve_deployment_status()

    mock.assert_called_once_with([
        'kubectl',
        "-n", TEST_NAMESPACE,
        "get",
        "deployment",
        TEST_DEPLOYMENT_NAME,
        "-o", "json",
    ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)

    assert deployment_status is None
