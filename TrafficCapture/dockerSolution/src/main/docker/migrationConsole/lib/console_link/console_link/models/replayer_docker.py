from typing import Dict
from console_link.models.command_result import CommandResult
from console_link.models.replayer_base import Replayer, ReplayStatus

import logging

logger = logging.getLogger(__name__)


class DockerReplayer(Replayer):
    def __init__(self, config: Dict) -> None:
        super().__init__(config)

    def start(self, *args, **kwargs) -> CommandResult:
        logger.warning("Start command is not implemented for Docker Replayer")
        return CommandResult(success=True, value="No action performed, action is unimplemented")

    def stop(self, *args, **kwargs) -> CommandResult:
        logger.warning("Stop command is not implemented for Docker Replayer")
        return CommandResult(success=True, value="No action performed, action is unimplemented")

    def get_status(self, *args, **kwargs) -> ReplayStatus:
        raise NotImplementedError()

    def scale(self, units: int, *args, **kwargs) -> CommandResult:
        raise NotImplementedError()
