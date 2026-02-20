from enum import Enum
from typing import Dict
from abc import ABC, abstractmethod

from console_link.models.schema_tools import contains_one_of
from console_link.models.command_result import CommandResult

from cerberus import Validator

DOCKER_REPLAY_SCHEMA = {
    "type": "dict",
    "nullable": True,
    "schema": {
        "socket": {"type": "string", "required": False}
    }
}

ECS_REPLAY_SCHEMA = {
    "type": "dict",
    "schema": {
        "cluster_name": {"type": "string", "required": True},
        "service_name": {"type": "string", "required": True},
        "aws_region": {"type": "string", "required": False}
    }
}

K8S_REPLAY_SCHEMA = {
    "type": "dict",
    "schema": {
        "namespace": {"type": "string", "required": True},
        "deployment_name": {"type": "string", "required": True}
    }
}

SCHEMA = {
    "replay": {
        "type": "dict",
        "schema": {
            "docker": DOCKER_REPLAY_SCHEMA,
            "ecs": ECS_REPLAY_SCHEMA,
            "k8s": K8S_REPLAY_SCHEMA,
            "scale": {"type": "integer", "required": False, "min": 1}
        },
        "check_with": contains_one_of({"docker", "ecs", "k8s"})
    }
}


ReplayStatus = Enum("ReplayStatus", ["NOT_STARTED", "STARTING", "RUNNING", "TERMINATING", "STOPPED", "FAILED"])


class Replayer(ABC):
    """
    Interface for replaying data from kafka to a target cluster.
    """

    def __init__(self, config: Dict) -> None:
        v = Validator(SCHEMA)
        self.config = config
        if not v.validate({"replay": self.config}):
            raise ValueError("Invalid config file for replay", v.errors)
        self.default_scale = self.config.get("scale", 1)

    @abstractmethod
    def start(self, *args, **kwargs) -> CommandResult:
        """Begin running the replayer. After running start, the user should be able to assume that--barring exceptions
        or failures--their data will begin playing against the target cluster."""
        pass

    @abstractmethod
    def stop(self, *args, **kwargs) -> CommandResult:
        """Stop or pause the replay. This does not make guarantees about resumeability."""
        pass

    @abstractmethod
    def get_status(self, *args, **kwargs) -> CommandResult:
        """Return a status"""
        pass

    @abstractmethod
    def scale(self, units: int, *args, **kwargs) -> CommandResult:
        pass

    def describe(self) -> Dict:
        return self.config
