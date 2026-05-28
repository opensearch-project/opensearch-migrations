import sys
import os
import shlex
from unittest.mock import MagicMock, patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'testAutomation'))

from k8s_service import K8sService, MigrationConsolePodIdentity


def _make_service():
    with patch("k8s_service.config.load_kube_config"):
        service = K8sService(namespace="ma")
    service.get_migration_console_pod_identity = MagicMock(
        return_value=MigrationConsolePodIdentity(
            name="migration-console-0",
            uid="migration-console-uid",
        )
    )
    return service


def test_exec_background_cmd_preserves_single_quoted_arguments():
    service = _make_service()
    service.k8s_client.connect_get_namespaced_pod_exec = MagicMock()
    executed_commands = []

    def fake_exec(command_list, unbuffered=True, console_pod_id=None):
        assert console_pod_id == "migration-console-0"
        executed_commands.append(command_list)
        if "test -f" in command_list[-1]:
            return "ok"
        return ""

    service.exec_migration_console_cmd = MagicMock(side_effect=fake_exec)

    with patch("k8s_service.time.sleep"):
        service.exec_background_cmd(
            command_list=[
                "pipenv",
                "run",
                "pytest",
                "--transform_image_basic=repo.example/image@sha256:abc123",
                "--transform_image_sequence=repo.example/image with spaces@sha256:def456",
            ],
            log_file="/tmp/test log.txt",
            exit_code_file="/tmp/test exit.txt",
        )

    wrapper_command = executed_commands[1]
    assert wrapper_command[:2] == ["sh", "-c"]

    wrapper = wrapper_command[2]
    assert wrapper.startswith("nohup sh -c ")
    assert wrapper.endswith(" > /dev/null 2>&1 &")

    script_arg = wrapper.removeprefix("nohup sh -c ").removesuffix(" > /dev/null 2>&1 &")
    script = shlex.split(script_arg)[0]
    assert "--transform_image_basic=repo.example/image@sha256:abc123" in shlex.split(script)
    assert "--transform_image_sequence=repo.example/image with spaces@sha256:def456" in shlex.split(script)
    assert "> '/tmp/test log.txt' 2>&1;" in script
    assert "echo $? > '/tmp/test exit.txt'" in script
