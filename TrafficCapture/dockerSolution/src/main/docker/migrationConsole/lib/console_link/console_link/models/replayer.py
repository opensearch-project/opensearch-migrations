from enum import Enum
from typing import Dict
import boto3
from cerberus import Validator

DeploymentType = Enum('DeploymentType', ['DOCKER', 'ECS'])

SCHEMA = {
    'deployment_type': {
        'type': 'string',
        'required': True
    }
}


class BaseReplayer():
    """
    The Replayer base class
    """
    def __init__(self, config: Dict) -> None:
        self.config = config
        v = Validator(SCHEMA)
        if not v.validate(config):
            raise ValueError("Invalid config file for replayer", v.errors)

    def start_replayer(self):
        """
        Starts the replayer.
        """
        raise NotImplementedError

    def stop_replayer(self):
        """
        Stops the replayer.
        """
        raise NotImplementedError


class LocalReplayer(BaseReplayer):
    def start_replayer(self):
        pass

    def stop_replayer(self):
        pass


ECS_SCHEMA = {
    'cluster_name': {
        'type': 'string',
        'required': True
    },
    'service_name': {
        'type': 'string',
        'required': True
    }
}


class ECSReplayer(BaseReplayer):
    client = boto3.client('ecs')

    def __init__(self, config: Dict) -> None:
        super().__init__(config)
        v = Validator(ECS_SCHEMA)
        if not v.validate(config):
            raise ValueError("Invalid config file for replayer", v.errors)
        self.cluster_name = config['cluster_name']
        self.service_name = config['service_name']
    
    def start_replayer(self) -> None:
        self.client.update_service(
            cluster=self.cluster_name,
            service=self.service_name,
            desiredCount=1
        )

    def stop_replayer(self) -> None:
        self.client.update_service(
            cluster=self.cluster_name,
            service=self.service_name,
            desiredCount=0
        )
