from typing import Dict, Optional
from console_link.models.client_options import ClientOptions
from console_link.models.command_result import CommandResult
from console_link.models.ecs_service import ECSService
from console_link.models.replayer_base import Replayer, ReplayStatus

import logging

logger = logging.getLogger(__name__)


class ECSReplayer(Replayer):
    def __init__(self, config: Dict, client_options: Optional[ClientOptions] = None) -> None:
        super().__init__(config)
        self.client_options = client_options
        self.ecs_config = self.config["ecs"]
        self.ecs_client = ECSService(cluster_name=self.ecs_config["cluster_name"],
                                     service_name=self.ecs_config["service_name"],
                                     aws_region=self.ecs_config.get("aws_region", None),
                                     client_options=self.client_options)

    def start(self, *args, **kwargs) -> CommandResult:
        logger.info(f"Starting ECS replayer by setting desired count to {self.default_scale} instances")
        return self.ecs_client.set_desired_count(self.default_scale)

    def stop(self, *args, **kwargs) -> CommandResult:
        logger.info("Stopping ECS replayer by setting desired count to 0 instances")
        return self.ecs_client.set_desired_count(0)

    def get_status(self, *args, **kwargs) -> CommandResult:
        # Simple implementation that only checks ECS service status currently
        instance_statuses = self.ecs_client.get_instance_statuses()
        if not instance_statuses:
            return CommandResult(False, "Failed to get instance statuses")

        status_string = str(instance_statuses)

        if instance_statuses.running > 0:
            return CommandResult(True, (ReplayStatus.RUNNING, status_string))
        elif instance_statuses.pending > 0:
            return CommandResult(True, (ReplayStatus.STARTING, status_string))
        return CommandResult(True, (ReplayStatus.STOPPED, status_string))

    def scale(self, units: int, *args, **kwargs) -> CommandResult:
        logger.info(f"Scaling ECS replayer by setting desired count to {units} instances")
        return self.ecs_client.set_desired_count(units)
