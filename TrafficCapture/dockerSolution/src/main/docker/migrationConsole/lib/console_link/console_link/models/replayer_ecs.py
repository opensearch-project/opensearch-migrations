import logging
from typing import Dict

from console_link.models.command_result import CommandResult
from console_link.models.ecs_service import ECSService
from console_link.models.replayer_base import Replayer, ReplayStatus

logger = logging.getLogger(__name__)


class ECSReplayer(Replayer):
    def __init__(self, config: Dict) -> None:
        super().__init__(config)
        self.ecs_config = self.config["ecs"]
        self.ecs_client = ECSService(self.ecs_config["cluster_name"], self.ecs_config["service_name"],
                                     self.ecs_config.get("aws_region", None))

    def start(self, *args, **kwargs) -> CommandResult:
        logger.info(f"Starting ECS replayer by setting desired count to {self.default_scale} instances")
        return self.ecs_client.set_desired_count(self.default_scale)

    def stop(self, *args, **kwargs) -> CommandResult:
        logger.info("Stopping ECS replayer by setting desired count to 0 instances")
        return self.ecs_client.set_desired_count(0)

    def get_status(self, *args, **kwargs) -> ReplayStatus:
        raise NotImplementedError()

    def scale(self, units: int, *args, **kwargs) -> CommandResult:
        logger.info(f"Scaling ECS replayer by setting desired count to {units} instances")
        return self.ecs_client.set_desired_count(units)
