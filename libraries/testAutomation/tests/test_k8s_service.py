import sys
import os
import shlex
import subprocess
from unittest.mock import MagicMock, patch

from kubernetes import client

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
                "--transform_image_context=repo.example/context-image@sha256:feedface",
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
    assert "--transform_image_context=repo.example/context-image@sha256:feedface" in shlex.split(script)
    assert "> '/tmp/test log.txt' 2>&1;" in script
    assert "echo $? > '/tmp/test exit.txt'" in script


def test_dump_helm_debug_info_selects_installer_pod_by_release_instance():
    """The installer Job labels its pods app.kubernetes.io/instance=<release>, so the
    pod-log query must select by that. Regression test for a selector that instead
    looked for app.kubernetes.io/name=migrationAssistantWithArgo and never matched,
    silently dropping the installer logs from every failed-install dump."""
    service = _make_service()
    kubectl_gets = []

    def fake_run(cmd, *args, **kwargs):
        if "get" in cmd and "pods" in cmd and "-l" in cmd:
            kubectl_gets.append(cmd[cmd.index("-l") + 1])
        return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

    with patch("k8s_service.subprocess.run", side_effect=fake_run):
        service._dump_helm_debug_info(release_name="ma")

    # The pod-log query must select by the release-instance label the installer Job stamps.
    assert "app.kubernetes.io/instance=ma" in kubectl_gets
    # The mangled selector from the original bug must never be emitted.
    assert all("migrationAssistantWithArgo" not in s for s in kubectl_gets)


def test_wait_for_all_healthy_pods_logs_diagnostics_before_timeout(caplog):
    service = _make_service()
    pod = client.V1Pod(
        metadata=client.V1ObjectMeta(name="fluent-bit-abc", uid="pod-uid"),
        status=client.V1PodStatus(
            phase="Running",
            container_statuses=[
                client.V1ContainerStatus(
                    image="fluent-bit:4.0.7",
                    image_id="fluent-bit@sha256:123",
                    name="fluent-bit",
                    ready=False,
                    restart_count=1,
                    state=client.V1ContainerState(
                        waiting=client.V1ContainerStateWaiting(
                            reason="CrashLoopBackOff",
                            message="back-off restarting failed container",
                        )
                    ),
                    last_state=client.V1ContainerState(
                        terminated=client.V1ContainerStateTerminated(
                            exit_code=1,
                            reason="Error",
                        )
                    ),
                )
            ],
        ),
    )
    service.k8s_client.list_namespaced_pod = MagicMock(
        return_value=MagicMock(items=[pod])
    )
    service.k8s_client.read_namespaced_pod_log = MagicMock(
        side_effect=["current output", "previous output"]
    )
    event = MagicMock(type="Warning", reason="BackOff", message="Back-off restarting container")
    service.k8s_client.list_namespaced_event = MagicMock(
        return_value=MagicMock(items=[event])
    )

    with patch("k8s_service.time.monotonic", side_effect=[0, 0, 2]), \
            patch("k8s_service.time.sleep"):
        try:
            service.wait_for_all_healthy_pods(timeout=1)
            assert False, "Expected wait_for_all_healthy_pods to time out"
        except TimeoutError:
            pass

    assert "state=(waiting=CrashLoopBackOff" in caplog.text
    assert "lastState=(terminated=Error, exitCode=1)" in caplog.text
    assert "current output" in caplog.text
    assert "previous output" in caplog.text
    assert "Event for fluent-bit-abc: type=Warning, reason=BackOff" in caplog.text
    assert service.k8s_client.read_namespaced_pod_log.call_count == 2


def test_unhealthy_pod_diagnostics_do_not_mask_log_errors(caplog):
    service = _make_service()
    pod = client.V1Pod(
        metadata=client.V1ObjectMeta(name="pending-pod", uid="pod-uid"),
        status=client.V1PodStatus(phase="Pending"),
    )
    service.k8s_client.list_namespaced_pod = MagicMock(
        return_value=MagicMock(items=[pod])
    )
    service.k8s_client.list_namespaced_event = MagicMock(
        side_effect=RuntimeError("events unavailable")
    )

    with patch("k8s_service.time.monotonic", side_effect=[0, 0, 2]), \
            patch("k8s_service.time.sleep"):
        try:
            service.wait_for_all_healthy_pods(timeout=1)
            assert False, "Expected wait_for_all_healthy_pods to time out"
        except TimeoutError as e:
            assert "Unhealthy pods: pending-pod" in str(e)

    assert "Unable to collect complete diagnostics for pod pending-pod" in caplog.text
