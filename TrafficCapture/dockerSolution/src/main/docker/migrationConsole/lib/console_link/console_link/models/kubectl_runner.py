import json
import logging

from typing import Optional

from console_link.models.command_result import CommandResult
from console_link.models.command_runner import CommandRunner, CommandRunnerError, FlagOnlyArgument
from console_link.models.utils import DeploymentStatus

logger = logging.getLogger(__name__)


class KubectlRunner:
    def __init__(self, namespace: str, deployment_name: str):
        self.namespace = namespace
        self.deployment_name = deployment_name

    def perform_scale_command(self, replicas: int) -> CommandResult:
        command = "kubectl"
        args = {
            "-n": f"{self.namespace}",
            "scale": FlagOnlyArgument,
            "deployment": f"{self.deployment_name}",
            "--replicas": f"{replicas}"
        }
        command_runner = CommandRunner(command_root=command, command_args=args)
        try:
            command_runner.run(print_to_console=False)
            return CommandResult(True, f"The {self.deployment_name} deployment has been set "
                                       f"to {replicas} desired count.")
        except CommandRunnerError as e:
            logger.error(f"Performing kubectl command to set replica count failed: {e}")
            return CommandResult(success=False, value=f"Kubernetes command failed: {e}")

    def retrieve_deployment_status(self) -> Optional[DeploymentStatus]:
        command = "kubectl"
        args = {
            "-n": f"{self.namespace}",
            "get": FlagOnlyArgument,
            "deployment": FlagOnlyArgument,
            self.deployment_name: FlagOnlyArgument,
            "-o": "json"
        }
        command_runner = CommandRunner(command_root=command, command_args=args)
        try:
            cmd_result = command_runner.run(print_to_console=False)
            json_output = json.loads(cmd_result.output.stdout)
            desired = int(json_output["spec"]["replicas"])
            running = int(json_output["status"].get("readyReplicas", "0"))
            pending = (desired - running) if desired > running else 0
            return DeploymentStatus(
                running=running,
                pending=pending,
                desired=desired
            )
        except Exception as e:
            logger.error(f"Performing kubectl command to get deployment status failed: {e}")
            return None
